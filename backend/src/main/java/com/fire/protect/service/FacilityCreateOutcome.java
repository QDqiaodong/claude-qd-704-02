package com.fire.protect.service;

import com.fire.protect.dto.Views;

/**
 * 新增点位的处理结果：{@code view} 为点位视图；
 * {@code replay} 为 true 表示本次是同一动作标识的重放，返回的是第一次创建出的那条点位。
 */
public record FacilityCreateOutcome(Views.FacilityView view, boolean replay) {
}
