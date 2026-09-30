package com.fire.protect.service;

import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fire.protect.common.BizException;
import com.fire.protect.common.ConflictException;
import com.fire.protect.domain.Building;
import com.fire.protect.domain.BuildingFloor;
import com.fire.protect.domain.FacilityCreateRequest;
import com.fire.protect.domain.FacilityPoint;
import com.fire.protect.domain.FireDict;
import com.fire.protect.dto.FacilityDtos;
import com.fire.protect.repository.BuildingFloorRepository;
import com.fire.protect.repository.FacilityCreateRequestRepository;
import com.fire.protect.repository.FacilityPointRepository;

/**
 * 「新增点位」事务执行体。
 *
 * <p>刻意独立成一个 Bean 并要求 {@code REQUIRES_NEW}：让 {@link FacilityService}
 * 能在事务外判断幂等重放——事务一旦在建点中途失败（数据库把事务标记为 rollback-only），
 * 外层仍可在新事务里正常读取/回放，不会被污染。
 *
 * <p>一次新增在同一个数据库事务内完成四件事，任何一步失败整体回滚：
 * <pre>
 *   1. 业务校验（建筑 / 楼层 / 类型 / 状态）
 *   2. 幂等台账占位（uk 主键裁决：同一动作只有一个请求进入取号）
 *   3. facility_point_sequence 行锁取号
 *   4. 建点位 → 回写台账（point_id / point_code）→ 提交
 * </pre>
 * 因此库里不可能出现「只有编号没有点位、只有点位没有编号、台账与平面图数量不一致」的状态。
 */
@Component
public class FacilityCreateTx {

    /** 执行结果：把建成的点位连同建筑/楼层快照交给外层组装视图 */
    public record Result(FacilityPoint point, Building building, BuildingFloor floor) {
    }

    private final FacilityPointRepository facilityPointRepository;
    private final FacilityCreateRequestRepository createRequestRepository;
    private final BuildingFloorRepository buildingFloorRepository;
    private final BuildingService buildingService;
    private final FacilityNumberService numberService;

    public FacilityCreateTx(FacilityPointRepository facilityPointRepository,
                            FacilityCreateRequestRepository createRequestRepository,
                            BuildingFloorRepository buildingFloorRepository,
                            BuildingService buildingService,
                            FacilityNumberService numberService) {
        this.facilityPointRepository = facilityPointRepository;
        this.createRequestRepository = createRequestRepository;
        this.buildingFloorRepository = buildingFloorRepository;
        this.buildingService = buildingService;
        this.numberService = numberService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result run(FacilityDtos.CreateReq req, String fingerprint, String operatorName) {
        // ---- 1. 业务校验（先于台账占位，非法请求不留任何痕迹） ----
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

        // ---- 2. 幂等台账占位：同一 actionKey 并发时主键唯一约束只放行一个 ----
        FacilityCreateRequest ledger = new FacilityCreateRequest();
        ledger.setActionKey(req.getActionKey());
        ledger.setBuildingId(building.getId());
        ledger.setFloorId(floor.getId());
        ledger.setRequestFingerprint(fingerprint);
        ledger.setCreatedBy(operatorName);
        ledger.setCreatedAt(LocalDateTime.now());
        try {
            createRequestRepository.saveAndFlush(ledger);
        } catch (DataIntegrityViolationException e) {
            // 同动作已有记录：先到的请求正在创建或已创建，本请求不得再占用编号
            throw new ConflictException(
                    "该新增动作正在处理或已处理，请稍后使用原动作标识重放获取点位编号（本次未重复创建）");
        }

        // ---- 3. 数据库行锁取号（同建筑并发在此排队，多实例共用该行锁） ----
        String pointCode;
        try {
            pointCode = numberService.nextPointCode(building.getId());
        } catch (RuntimeException e) {
            throw new ConflictException(
                    "点位编号分配繁忙（并发冲突），请稍后用同一动作标识重试，编号与点位都未生成", e);
        }

        // ---- 4. 建点位。编号由服务端分配，@PrePersist 只补日期/状态，不再自行算号 ----
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
        FacilityPoint saved;
        try {
            saved = facilityPointRepository.saveAndFlush(point);
        } catch (DataIntegrityViolationException e) {
            // uk_point_fp_no 等约束兜底：整体回滚后客户端用同一动作标识安全重试
            throw new ConflictException(
                    "点位保存时发生唯一约束冲突，本次未生成点位，请用同一动作标识安全重试", e);
        }

        // ---- 5. 回写台账并随事务提交：提交后台账、编号、点位严格一致 ----
        ledger.setPointId(saved.getId());
        ledger.setPointCode(pointCode);
        createRequestRepository.saveAndFlush(ledger);
        return new Result(saved, building, floor);
    }
}
