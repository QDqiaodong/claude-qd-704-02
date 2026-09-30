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
import com.fire.protect.common.ForbiddenException;
import com.fire.protect.domain.Building;
import com.fire.protect.domain.BuildingFloor;
import com.fire.protect.domain.DisablePermit;
import com.fire.protect.domain.FacilityPoint;
import com.fire.protect.domain.FacilityPointAction;
import com.fire.protect.domain.FireDict;
import com.fire.protect.dto.FacilityDtos;
import com.fire.protect.dto.MaintenanceDtos;
import com.fire.protect.dto.Views;
import com.fire.protect.repository.BuildingFloorRepository;
import com.fire.protect.repository.BuildingRepository;
import com.fire.protect.repository.DisablePermitRepository;
import com.fire.protect.repository.FacilityPointActionRepository;
import com.fire.protect.repository.FacilityPointRepository;
import com.fire.protect.security.CurrentUserHolder;

/**
 * 模块二：消防设施点位。
 *
 * <p>写入两侧的通用计算（下次检查日、过期回落状态）在
 * {@link com.fire.protect.listener.FacilityLifecycleListener} 里由 {@code @PrePersist}/{@code @PreUpdate} 完成；
 * 点位编号不再由「查当前最大序号」生成，而由 {@link FacilityCodeAllocator} 走数据库序列行锁分配，
 * 新增的并发取号、动作幂等与事务一致性见 {@link #create(FacilityDtos.CreateReq)}。
 */
@Service
public class FacilityService {

    private final FacilityPointRepository facilityPointRepository;
    private final BuildingRepository buildingRepository;
    private final BuildingFloorRepository buildingFloorRepository;
    private final BuildingService buildingService;
    private final MaintenanceService maintenanceService;
    private final DisablePermitRepository disablePermitRepository;
    private final FacilityPointActionRepository actionRepository;
    private final FacilityActionReservation reservation;
    private final FacilityPointCreateTx createTx;

    public FacilityService(FacilityPointRepository facilityPointRepository,
                           BuildingRepository buildingRepository,
                           BuildingFloorRepository buildingFloorRepository,
                           BuildingService buildingService,
                           MaintenanceService maintenanceService,
                           DisablePermitRepository disablePermitRepository,
                           FacilityPointActionRepository actionRepository,
                           FacilityActionReservation reservation,
                           FacilityPointCreateTx createTx) {
        this.facilityPointRepository = facilityPointRepository;
        this.buildingRepository = buildingRepository;
        this.buildingFloorRepository = buildingFloorRepository;
        this.buildingService = buildingService;
        this.maintenanceService = maintenanceService;
        this.disablePermitRepository = disablePermitRepository;
        this.actionRepository = actionRepository;
        this.reservation = reservation;
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
     * 新增点位（并发 + 重试安全），两阶段幂等：
     * <ol>
     *   <li><b>预留</b>：短事务插入动作台账行（PROCESSING），{@code uk_point_action_key}
     *       是真正的并发串行化点。不同动作各自预留成功；同一动作只有一个能预留，其余不再取号。</li>
     *   <li><b>完成</b>：独立事务内 校验→行写锁取号→落点位→回填动作行为 DONE，全部同提交。</li>
     * </ol>
     *
     * <ul>
     *   <li>同一动作因超时 / 重复点击 / 网络重发再次到达，预留行已是 DONE → 直接回放第一次的点位，
     *       不占新编号、不产生第二条记录；服务重启、多实例都只看数据库，行为一致；</li>
     *   <li>同一动作标识携带不同表单内容 → 409，提示换新动作标识；</li>
     *   <li>预留行仍是 PROCESSING（首次处理中 / 对端事务未提交 / 处理进程崩溃）→ 接管继续创建：
     *       最后的动作回填（只允许 PROCESSING→DONE）保证并发接管中只有一个能完成，
     *       输家事务整笔回滚（含序号），再回放赢家结果；</li>
     *   <li>若对端还在处理中（短暂窗口）→ 409 明确提示用同一动作标识稍后重试，本次不落任何数据。</li>
     * </ul>
     */
    public FacilityCreateOutcome create(FacilityDtos.CreateReq req) {
        String fingerprint = FacilityPointCreateTx.fingerprint(req);
        String operator = currentOperator();

        // 0) 已存在动作记录：已完成直接回放；处理中进入接管 / 等待判定
        FacilityPointAction existing = actionRepository.findByActionKey(req.getActionKey()).orElse(null);
        if (existing != null) {
            checkFingerprint(existing, fingerprint);
            if ("DONE".equals(existing.getStatus())) {
                return new FacilityCreateOutcome(toPointView(ensureActionPoint(existing)), true);
            }
            return continueProcessing(req, fingerprint);
        }

        // 1) 预留动作标识（并发串行化点）
        FacilityPointAction reserved;
        try {
            reserved = reservation.reserve(req.getActionKey(), req.getBuildingId(), fingerprint, operator);
        } catch (ConflictException e) {
            // 并发下另一请求刚预留（其短事务可能尚未对本事务可见）：重读一次判定
            reserved = actionRepository.findByActionKey(req.getActionKey()).orElse(null);
            if (reserved == null) {
                throw new ConflictException("该新增动作正在另一请求中处理，请稍后用同一动作标识重试（本次未生成点位与编号）");
            }
        }
        checkFingerprint(reserved, fingerprint);
        if ("DONE".equals(reserved.getStatus())) {
            return new FacilityCreateOutcome(toPointView(ensureActionPoint(reserved)), true);
        }

        // 2) PROCESSING：本事务接管完成创建
        return continueProcessing(req, fingerprint);
    }

    /**
     * 预留行为 PROCESSING：在独立事务里完成创建。
     * 完成时若赢家已抢先把动作置为 DONE（并发接管），则回放赢家点位；
     * 若赢家事务还在进行（动作仍 PROCESSING），给出可安全重试的 409。
     */
    private FacilityCreateOutcome continueProcessing(FacilityDtos.CreateReq req, String fingerprint) {
        FacilityPoint saved;
        try {
            saved = createTx.create(req);
        } catch (ConflictException e) {
            FacilityPointAction winner = actionRepository.findByActionKey(req.getActionKey()).orElse(null);
            if (winner != null && "DONE".equals(winner.getStatus())) {
                checkFingerprint(winner, fingerprint);
                return new FacilityCreateOutcome(toPointView(ensureActionPoint(winner)), true);
            }
            // 另一个处理者还在提交中：本次什么都没落下，用同一动作标识稍后重试即可
            throw new ConflictException("该新增动作正在另一请求中处理，编号尚未落定，请稍后用同一动作标识重试（本次未生成点位与编号）");
        }
        return new FacilityCreateOutcome(toPointView(saved), false);
    }

    private void checkFingerprint(FacilityPointAction action, String fingerprint) {
        if (!fingerprint.equals(action.getRequestFingerprint())) {
            throw new ConflictException("该动作标识已用于其它内容的新增，请使用页面生成的新动作标识重新提交");
        }
    }

    /** DONE 动作指向的点位必须存在（同事务提交保证）；查不到说明数据异常。 */
    private FacilityPoint ensureActionPoint(FacilityPointAction action) {
        if (action.getPointId() == null) {
            throw new BizException("动作 " + action.getActionKey() + " 尚未完成，请稍后用同一动作标识重试");
        }
        return facilityPointRepository.findById(action.getPointId())
                .orElseThrow(() -> new BizException(
                        "动作 " + action.getActionKey() + " 的点位记录缺失，请联系管理员核对点位台账与动作记录"));
    }

    private String currentOperator() {
        try {
            return CurrentUserHolder.require().getDisplayName();
        } catch (ForbiddenException e) {
            // 幂等回放 / 服务端场景不强制身份；实际写库的操作者仅用于动作台账留痕
            return "匿名";
        }
    }

    /** 把已落库点位装成视图（关联建筑 / 楼层 / 许可编号）。 */
    private Views.FacilityView toPointView(FacilityPoint point) {
        return toView(point,
                buildingRepository.findById(point.getBuildingId()).orElse(null),
                point.getFloorId() == null ? null : buildingFloorRepository.findById(point.getFloorId()).orElse(null),
                permitNoMap());
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
