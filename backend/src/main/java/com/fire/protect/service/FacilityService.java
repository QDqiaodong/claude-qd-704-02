package com.fire.protect.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fire.protect.common.BizException;
import com.fire.protect.common.ConflictException;
import com.fire.protect.domain.Building;
import com.fire.protect.domain.BuildingFloor;
import com.fire.protect.domain.DisablePermit;
import com.fire.protect.domain.FacilityCreateRequest;
import com.fire.protect.domain.FacilityPoint;
import com.fire.protect.domain.FireDict;
import com.fire.protect.dto.FacilityDtos;
import com.fire.protect.dto.MaintenanceDtos;
import com.fire.protect.dto.Views;
import com.fire.protect.repository.BuildingFloorRepository;
import com.fire.protect.repository.BuildingRepository;
import com.fire.protect.repository.DisablePermitRepository;
import com.fire.protect.repository.FacilityCreateRequestRepository;
import com.fire.protect.repository.FacilityPointRepository;
import com.fire.protect.security.CurrentUserHolder;
import com.fire.protect.domain.SysUser;

/**
 * 模块二：消防设施点位。
 *
 * <p>写入两侧的通用计算（点位编码、下次检查日、过期回落状态）全部在
 * {@link com.fire.protect.listener.FacilityLifecycleListener} 里由 {@code @PrePersist}/{@code @PreUpdate} 完成，
 * 这里只负责校验、挑出实体、改业务字段然后 save。
 */
@Service
public class FacilityService {

    private final FacilityPointRepository facilityPointRepository;
    private final FacilityCreateRequestRepository createRequestRepository;
    private final BuildingRepository buildingRepository;
    private final BuildingFloorRepository buildingFloorRepository;
    private final BuildingService buildingService;
    private final MaintenanceService maintenanceService;
    private final DisablePermitRepository disablePermitRepository;
    private final FacilityCreateTx createTx;

    public FacilityService(FacilityPointRepository facilityPointRepository,
                           FacilityCreateRequestRepository createRequestRepository,
                           BuildingRepository buildingRepository,
                           BuildingFloorRepository buildingFloorRepository,
                           BuildingService buildingService,
                           MaintenanceService maintenanceService,
                           DisablePermitRepository disablePermitRepository,
                           FacilityCreateTx createTx) {
        this.facilityPointRepository = facilityPointRepository;
        this.createRequestRepository = createRequestRepository;
        this.buildingRepository = buildingRepository;
        this.buildingFloorRepository = buildingFloorRepository;
        this.buildingService = buildingService;
        this.maintenanceService = maintenanceService;
        this.disablePermitRepository = disablePermitRepository;
        this.createTx = createTx;
    }

    /** 当前挂着许可停用标记的许可 id → 许可编号，供平面图 / 台账标注。 */
    private Map<Long, String> permitNoMap() {
        Map<Long, String> map = new HashMap<>();
        for (DisablePermit permit : disablePermitRepository.findAll()) {
            map.put(permit.getId(), permit.getPermitNo());
        }
        return map;
    }

    /** 点位列表：可按建筑 / 楼层 / 设施类型过滤。 */
    @Transactional(readOnly = true)
    public List<Views.FacilityView> list(Long buildingId, Long floorId, String facilityType) {
        List<FacilityPoint> points;
        if (buildingId != null && floorId != null) {
            points = facilityPointRepository.findAllByBuildingIdAndFloorId(buildingId, floorId, Sort.by("id"));
        } else if (buildingId != null) {
            points = facilityPointRepository.findAllByBuildingId(buildingId, Sort.by("id"));
        } else {
            points = facilityPointRepository.findAll(Sort.by("id"));
        }
        Map<Long, String> permitNoMap = permitNoMap();
        List<Views.FacilityView> list = new ArrayList<>();
        Map<Long, Building> buildingMap = new HashMap<>();
        Map<Long, BuildingFloor> floorMap = new HashMap<>();
        for (FacilityPoint point : points) {
            if (facilityType != null && !facilityType.isBlank() && !facilityType.equals(point.getFacilityType())) {
                continue;
            }
            Building building = buildingMap.computeIfAbsent(point.getBuildingId(), id -> buildingRepository.findById(id).orElse(null));
            BuildingFloor floor = floorMap.computeIfAbsent(point.getFloorId(), id -> buildingFloorRepository.findById(id).orElse(null));
            list.add(toView(point, building, floor, permitNoMap));
        }
        return list;
    }

    /** 平面图主视觉：建筑 + 楼层标签 + 全部点位 + 状态统计。 */
    @Transactional(readOnly = true)
    public Views.PlanViewDTO plan(Long buildingId) {
        Building building = buildingService.ensureBuilding(buildingId);
        List<Views.FloorView> floors = buildingService.floorsOf(buildingId);
        List<Views.FacilityView> points = list(buildingId, null, null);

        long total = points.size();
        long normal = 0;
        long replace = 0;
        long fault = 0;
        long disabled = 0;
        long overdue = 0;
        long permitDisabled = 0;
        for (Views.FacilityView point : points) {
            switch (point.status()) {
                case "NORMAL" -> normal++;
                case "REPLACE" -> replace++;
                case "FAULT" -> fault++;
                case "DISABLED" -> disabled++;
                default -> {
                }
            }
            if (point.overdue()) {
                overdue++;
            }
            if (point.permitDisabled()) {
                permitDisabled++;
            }
        }
        return new Views.PlanViewDTO(
                building.getId(), building.getName(), FireDict.buildingTypeName(building.getBuildingType()),
                floors, points,
                new Views.PlanSummary(total, normal, replace, fault, disabled, overdue, permitDisabled));
    }

    /**
     * 新增点位（并发安全 + 动作幂等）。
     *
     * <p>请求必须带客户端动作标识 {@code actionKey}：同一动作（超时重试 / 重复点击 /
     * 网络重发 / 重启后重放）只返回第一次创建的点位，不再占用新编号；不同动作即使内容相同，
     * 也各自落成一条有效点位并拿到不同编号。
     *
     * <p>取号（{@code facility_point_sequence} 行锁）、建点位、幂等台账回写在同一个数据库
     * 事务里提交，任何一步失败整体回滚；可重试冲突统一抛 {@link ConflictException}（HTTP 409），
     * 客户端用同一动作标识安全重试即可。
     *
     * <p>本方法本身不开事务：幂等回放走只读自动提交，写路径全部在 {@link FacilityCreateTx}
     * 的独立事务内，写事务失败/回滚不会污染外层，请求仍可立刻重放。
     */
    public Views.FacilityView create(FacilityDtos.CreateReq req) {
        // 1. 无事务副作用的重放检查放在最前：已完成动作直接回放首次结果（重启后同样成立）
        FacilityCreateRequest existing = createRequestRepository.findById(req.getActionKey()).orElse(null);
        if (existing != null) {
            return replay(existing, req);
        }

        // 2. 独立事务执行：占位台账 → 行锁取号 → 建点位 → 回写台账
        SysUser operator = CurrentUserHolder.get();
        String operatorName = operator == null ? null : operator.getDisplayName();
        String fingerprint = FacilityCreateFingerprint.of(req);
        FacilityCreateTx.Result result = createTx.run(req, fingerprint, operatorName);
        return toView(result.point(), result.building(), result.floor(), permitNoMap());
    }

    /**
     * 同动作标识再次到达：核对内容指纹后回放首次点位，绝不二次创建。
     * 指纹不一致 = 新动作误挂旧标识，拒绝并让客户端换新标识重提（不重试）。
     */
    private Views.FacilityView replay(FacilityCreateRequest ledger, FacilityDtos.CreateReq req) {
        if (!ledger.getRequestFingerprint().equals(FacilityCreateFingerprint.of(req))) {
            throw new BizException("该动作标识已用于另一条点位（表单内容不一致），请刷新后以新动作重新提交");
        }
        FacilityPoint point = ledger.getPointId() == null
                ? null
                : facilityPointRepository.findById(ledger.getPointId()).orElse(null);
        if (point == null) {
            // 正常路径不可达：台账与点位同事务，提交即同时存在。仅在数据被外部破坏时出现。
            throw new ConflictException(
                    "该动作的点位结果暂不可用，请联系管理员核对点位台账与编号流水后再处理，勿用同一标识重复建档");
        }
        Building building = buildingRepository.findById(point.getBuildingId()).orElse(null);
        BuildingFloor floor = point.getFloorId() == null
                ? null : buildingFloorRepository.findById(point.getFloorId()).orElse(null);
        return toView(point, building, floor, permitNoMap());
    }

    /**
     * 标记已检：只写「上次检查日」，下次检查日由 {@code @PreUpdate} 按检查周期重算，
     * 并自动解除「待更换」这类过期回落状态。
     */
    @Transactional
    public Views.FacilityView markChecked(Long id, LocalDate date) {
        FacilityPoint point = ensurePoint(id);
        if ("DISABLED".equals(point.getStatus())) {
            throw new BizException("点位已停用，不能标记已检");
        }
        if (point.getActiveDisablePermitId() != null) {
            throw new BizException("点位处于许可停用期间，待许可关闭复位后再标记检查；期间巡检异常照常记录");
        }
        point.setLastCheckDate(date == null ? LocalDate.now() : date);
        if (!"FAULT".equals(point.getStatus())) {
            point.setStatus("NORMAL");
        }
        // saveAndFlush：让 @PreUpdate 当场跑完，返回的下次检查日就是重算后的值
        FacilityPoint saved = facilityPointRepository.saveAndFlush(point);
        return toView(saved, buildingRepository.findById(saved.getBuildingId()).orElse(null),
                buildingFloorRepository.findById(saved.getFloorId()).orElse(null), permitNoMap());
    }

    /** 图上直接报修：拉张维保单；许可停用期间只登记工单，不改设施原状态。 */
    @Transactional
    public Views.MaintenanceOrderView report(Long id, MaintenanceDtos.CreateReq req) {
        FacilityPoint point = ensurePoint(id);
        MaintenanceDtos.CreateReq body = req == null ? new MaintenanceDtos.CreateReq() : req;
        body.setPointId(point.getId());
        if (body.getOrderType() == null || body.getOrderType().isBlank()) {
            body.setOrderType("REPAIR");
        }
        Views.MaintenanceOrderView order = maintenanceService.create(body);
        boolean frozen = point.getActiveDisablePermitId() != null;
        if (!frozen && body.getFaultDesc() != null && !body.getFaultDesc().isBlank()
                && !"DISABLED".equals(point.getStatus())) {
            point.setStatus("FAULT");
            facilityPointRepository.saveAndFlush(point);
        }
        return order;
    }

    @Transactional(readOnly = true)
    public FacilityPoint ensurePoint(Long id) {
        return facilityPointRepository.findById(id).orElseThrow(() -> new BizException("点位不存在：" + id));
    }

    private Views.FacilityView toView(FacilityPoint point, Building building, BuildingFloor floor,
                                      Map<Long, String> permitNoMap) {
        LocalDate today = LocalDate.now();
        LocalDate next = point.getNextCheckDate();
        Long permitId = point.getActiveDisablePermitId();
        return new Views.FacilityView(
                point.getId(), point.getPointCode(), point.getBuildingId(),
                building == null ? "" : building.getName(),
                point.getFloorId(),
                floor == null ? "" : floor.getFloorCode(),
                floor == null ? "" : floor.getFloorName(),
                point.getFacilityType(), FireDict.facilityTypeName(point.getFacilityType()),
                point.getSpec(), point.getLocationDesc(), point.getInstallDate(),
                point.getCheckCycleDays(), point.getLastCheckDate(), next,
                point.getStatus(), FireDict.pointStatusName(point.getStatus()),
                point.getPlanX(), point.getPlanY(),
                next == null ? null : ChronoUnit.DAYS.between(today, next),
                next != null && next.isBefore(today),
                permitId,
                permitId == null ? null : permitNoMap.getOrDefault(permitId, ""),
                permitId != null);
    }
}
