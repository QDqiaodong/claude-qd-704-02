package com.fire.protect.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 模块二（消防设施点位）入参。 */
public final class FacilityDtos {

    private FacilityDtos() {
    }

    @Data
    public static class CreateReq {

        /**
         * 客户端动作标识（幂等键）：打开新增表单时生成一次，超时重试 / 重复点击 / 网络重发
         * 必须复用同一个值，只有真正另起一次新增时才换新值。服务重启后仍据此识别已处理动作。
         */
        @NotBlank(message = "缺少动作标识 actionKey，请刷新页面后重试")
        @Size(max = 64, message = "动作标识长度不超过 64 个字符")
        private String actionKey;

        @NotNull(message = "所属建筑不能为空")
        private Long buildingId;

        @NotNull(message = "所属楼层不能为空")
        private Long floorId;

        @NotBlank(message = "设施类型不能为空")
        private String facilityType;

        @Size(max = 64, message = "规格型号不超过 64 个字符")
        private String spec;

        @Size(max = 128, message = "位置描述不超过 128 个字符")
        private String locationDesc;

        /** 可空：留空时 @PrePersist 会按今天算起 */
        private LocalDate installDate;

        /** 可空：留空时按设施类型的缺省周期（30/90/180 天） */
        private Integer checkCycleDays;

        /** 平面图坐标 0~1000 / 0~660 */
        private Integer planX;

        private Integer planY;

        /** 可空：初始状态，默认正常 */
        private String status;
    }
}
