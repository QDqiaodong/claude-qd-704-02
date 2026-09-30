package com.fire.protect.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fire.protect.common.ApiResponse;
import com.fire.protect.dto.FacilityDtos;
import com.fire.protect.dto.MaintenanceDtos;
import com.fire.protect.dto.Views;
import com.fire.protect.service.FacilityCreateOutcome;
import com.fire.protect.service.FacilityService;

import jakarta.validation.Valid;

/** 模块二：消防设施点位（平面图上的气泡就是一个点位）。 */
@RestController
@RequestMapping("/api/facilities")
public class FacilityController {

    private final FacilityService facilityService;

    public FacilityController(FacilityService facilityService) {
        this.facilityService = facilityService;
    }

    /** 点位列表，可按建筑 / 楼层 / 设施类型过滤 */
    @GetMapping
    public ApiResponse<List<Views.FacilityView>> list(
            @RequestParam(required = false) Long buildingId,
            @RequestParam(required = false) Long floorId,
            @RequestParam(required = false) String type) {
        return ApiResponse.success(facilityService.list(buildingId, floorId, type));
    }

    /** 平面图主视觉：楼层 + 点位 + 状态统计 */
    @GetMapping("/plan")
    public ApiResponse<Views.PlanViewDTO> plan(@RequestParam Long buildingId) {
        return ApiResponse.success(facilityService.plan(buildingId));
    }

    /**
     * 新增点位：请求必须携带客户端动作标识 actionKey（幂等键）。
     * 编号由数据库序列行锁分配，同动作重发只回放第一次创建出的那条点位，不重复占号。
     */
    @PostMapping
    public ApiResponse<Views.FacilityView> create(@Valid @RequestBody FacilityDtos.CreateReq req) {
        FacilityCreateOutcome outcome = facilityService.create(req);
        String pointCode = outcome.view().pointCode();
        String message = outcome.replay()
                ? "该新增请求此前已处理，返回首次创建的点位 " + pointCode + "（未重复占用编号）"
                : "点位已建档，系统分配编号：" + pointCode;
        return ApiResponse.success(message, outcome.view());
    }

    /** 标记已检：下次检查日由 {@code @PreUpdate} 按上次检查日 + 周期重算 */
    @PostMapping("/{id}/check")
    public ApiResponse<Views.FacilityView> markChecked(@PathVariable Long id) {
        return ApiResponse.success("已标记检查，下次到期日同步刷新", facilityService.markChecked(id, null));
    }

    /** 图上直接报修 */
    @PostMapping("/{id}/report")
    public ApiResponse<Views.MaintenanceOrderView> report(@PathVariable Long id,
                                                          @RequestBody(required = false) MaintenanceDtos.CreateReq req) {
        return ApiResponse.success("维保单已生成", facilityService.report(id, req));
    }
}
