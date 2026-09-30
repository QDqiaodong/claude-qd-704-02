package com.fire.protect.listener;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.fire.protect.domain.BaseFacility;
import com.fire.protect.domain.Extinguisher;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/**
 * 消防设施实体的生命周期回调（{@code BaseFacility} 及其子类通过 {@code @EntityListeners} 挂上来）。
 *
 * <ul>
 *   <li>{@code @PrePersist}：点位编号正常由服务端通过 {@code facility_point_sequence}
 *       流水表行锁预分配（FAC/EXT-&lt;建筑编号&gt;-&lt;四位序号&gt;，并发不撞号、删除不复用、
 *       重放不重号），已经带编号的实体这里不再覆盖；只有绕过服务直接落库时才走「最大尾号 +1」兜底，
 *       并补齐安装日期、按周期算出下次检查日；</li>
 *   <li>{@code @PreUpdate}：每次写入都按上次检查日重算下次检查日，
 *       已经过期且当前仍为「正常」的自动回落（点位→待更换，气瓶→待充装），到报废日期直接置「报废」。</li>
 * </ul>
 *
 * 因此「新增设施」「标记已检」「完成巡检」三条写入路径在 service 里没有任何日期算式。
 */
public class FacilityLifecycleListener {

    @PrePersist
    public void prePersist(BaseFacility facility) {
        LocalDateTime now = LocalDateTime.now();
        if (facility.getCreatedAt() == null) {
            facility.setCreatedAt(now);
        }
        fillPointCode(facility);
        reschedule(facility, LocalDate.now());
        facility.setUpdatedAt(now);
    }

    @PreUpdate
    public void preUpdate(BaseFacility facility) {
        reschedule(facility, LocalDate.now());
        facility.setUpdatedAt(LocalDateTime.now());
    }

    private void fillPointCode(BaseFacility facility) {
        if (facility.getPointCode() != null && !facility.getPointCode().isBlank()) {
            return;
        }
        FacilityLifecycleSupport support = FacilityLifecycleSupport.instance();
        String buildingCode = support == null ? "DEF" : support.buildingCode(facility.getBuildingId());
        String prefix = facility.codePrefix() + "-" + buildingCode + "-";
        long sequence = support == null ? 1L : support.nextSequence(facility, prefix);
        facility.setPointCode(prefix + String.format("%04d", sequence));
    }

    private void reschedule(BaseFacility facility, LocalDate today) {
        if (facility.getCheckCycleDays() == null || facility.getCheckCycleDays() <= 0) {
            facility.setCheckCycleDays(facility.defaultCycleDays());
        }
        if (facility.getInstallDate() == null) {
            facility.setInstallDate(today);
        }
        LocalDate last = facility.getLastCheckDate() == null ? facility.getInstallDate() : facility.getLastCheckDate();
        facility.setLastCheckDate(last);
        facility.setNextCheckDate(last.plusDays(facility.getCheckCycleDays()));

        String status = facility.getStatus();
        if (status == null || status.isBlank()) {
            status = "NORMAL";
        }
        // 过期且状态仍为正常 → 自动回落；故障 / 停用 / 报废等人工判定状态不会被覆盖
        if ("NORMAL".equals(status) && facility.getNextCheckDate().isBefore(today)) {
            status = facility.overdueStatus();
        }
        facility.setStatus(status);

        if (facility instanceof Extinguisher cylinder && cylinder.getScrapDate() != null
                && !cylinder.getScrapDate().isAfter(today)) {
            facility.setStatus("SCRAP");
        }
    }
}
