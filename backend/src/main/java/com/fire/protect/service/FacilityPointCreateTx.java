package com.fire.protect.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fire.protect.common.BizException;
import com.fire.protect.common.ConflictException;
import com.fire.protect.domain.Building;
import com.fire.protect.domain.BuildingFloor;
import com.fire.protect.domain.FacilityPoint;
import com.fire.protect.domain.FireDict;
import com.fire.protect.dto.FacilityDtos;
import com.fire.protect.repository.BuildingFloorRepository;
import com.fire.protect.repository.FacilityPointActionRepository;
import com.fire.protect.repository.FacilityPointRepository;

/**
 * 点位新增的事务内执行（两阶段幂等的第二阶段）：校验 → 行写锁取号 → 落点位 →
 * 把第一阶段预留的动作行回填为 DONE，全部在同一个数据库事务。
 *
 * <p>动作预留行已由 {@link FacilityActionReservation} 在短事务里插入（PROCESSING），
 * 同动作标识的并发只有一个能预留成功、走到这里。即便出现「处理进程崩溃后重试接管」，
 * 兜底也在最后一步：{@code complete} 只更新仍是 PROCESSING 的行，
 * 并发 / 接管中只有一个事务能回填成功（返回 1），其余返回 0 并整笔回滚
 * （序号自增随事务回滚，不留下悬空编号，也不会有两条点位），外层据此回放赢家结果。
 *
 * <p>序列自增、facility_point、动作回填一起提交 / 一起回滚：
 * 不会留下只有编号没有点位、只有点位没有编号或平面图与台账数量不一致的半成品。
 */
@Service
public class FacilityPointCreateTx {

    private final FacilityPointRepository facilityPointRepository;
    private final FacilityPointActionRepository actionRepository;
    private final BuildingFloorRepository buildingFloorRepository;
    private final BuildingService buildingService;
    private final FacilityCodeAllocator codeAllocator;

    public FacilityPointCreateTx(FacilityPointRepository facilityPointRepository,
                                 FacilityPointActionRepository actionRepository,
                                 BuildingFloorRepository buildingFloorRepository,
                                 BuildingService buildingService,
                                 FacilityCodeAllocator codeAllocator) {
        this.facilityPointRepository = facilityPointRepository;
        this.actionRepository = actionRepository;
        this.buildingFloorRepository = buildingFloorRepository;
        this.buildingService = buildingService;
        this.codeAllocator = codeAllocator;
    }

    /**
     * 独立事务（REQUIRES_NEW）内完成创建。
     *
     * @return 已落库点位（含后端实际分配的编号）；回填动作行失败（说明赢家已完成）时抛冲突，由外层回放
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FacilityPoint create(FacilityDtos.CreateReq req) {
        Building building = buildingService.ensureBuilding(req.getBuildingId());
        if (!FireDict.isValidFacilityType(req.getFacilityType())) {
            throw new BizException("设施类型只能是：灭火器 / 消火栓 / 喷淋头 / 烟感探测器 / 手动报警按钮 / 应急照明 / 防火门 / 消防泵");
        }
        BuildingFloor floor = buildingFloorRepository.findById(req.getFloorId())
                .orElseThrow(() -> new BizException("楼层不存在：" + req.getFloorId()));
        if (!floor.getBuildingId().equals(building.getId())) {
            throw new BizException("所选楼层不属于该建筑");
        }
        if (req.getStatus() != null && !FireDict.isValidPointStatus(req.getStatus())) {
            throw new BizException("点位状态只能是：正常 / 待更换 / 故障 / 停用");
        }

        // 行写锁原子取号：同建筑并发在此串行化，序号各不相同；与点位同一事务，失败一起回滚
        String pointCode = codeAllocator.allocate(FireDict.FACILITY_CODE_PREFIX, building.getCode());

        FacilityPoint point = new FacilityPoint();
        point.setPointCode(pointCode);
        point.setBuildingId(building.getId());
        point.setFloorId(floor.getId());
        point.setFacilityType(req.getFacilityType());
        point.setSpec(req.getSpec());
        point.setLocationDesc(req.getLocationDesc());
        point.setInstallDate(req.getInstallDate());
        point.setCheckCycleDays(req.getCheckCycleDays());
        point.setPlanX(req.getPlanX() == null ? 500 : req.getPlanX());
        point.setPlanY(req.getPlanY() == null ? 340 : req.getPlanY());
        point.setStatus(req.getStatus() == null ? "NORMAL" : req.getStatus());
        FacilityPoint saved = facilityPointRepository.saveAndFlush(point);

        // 只有仍是 PROCESSING 的预留行能被本事务回填；并发接管的输家返回 0 → 整笔回滚
        int completed = actionRepository.complete(req.getActionKey(), saved.getId(), pointCode);
        if (completed != 1) {
            throw new ConflictException("该新增动作已由另一请求完成，本次不重复建点");
        }
        return saved;
    }

    /**
     * 新增请求业务字段指纹：字段名固定顺序拼接后 SHA-256。
     * 同一 actionKey 携带不同内容到达时用于识别冲突（防止把新请求误当回放）。
     */
    public static String fingerprint(FacilityDtos.CreateReq req) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("buildingId", str(req.getBuildingId()));
        fields.put("floorId", str(req.getFloorId()));
        fields.put("facilityType", trim(req.getFacilityType()));
        fields.put("spec", trim(req.getSpec()));
        fields.put("locationDesc", trim(req.getLocationDesc()));
        fields.put("installDate", str(req.getInstallDate()));
        fields.put("checkCycleDays", str(req.getCheckCycleDays()));
        fields.put("planX", str(req.getPlanX()));
        fields.put("planY", str(req.getPlanY()));
        fields.put("status", trim(req.getStatus()));
        StringBuilder plain = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            plain.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        return sha256(plain.toString());
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
