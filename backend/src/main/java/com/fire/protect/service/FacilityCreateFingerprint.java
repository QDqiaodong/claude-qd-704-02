package com.fire.protect.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.fire.protect.dto.FacilityDtos;

/**
 * 新增点位请求内容指纹。
 *
 * <p>动作标识（actionKey）标识「一次动作」，指纹标识「动作的内容」：
 * 同一动作标识重放但内容已变，说明客户端把新表单误挂到了旧动作上，必须拒绝，
 * 不能拿旧动作的编号去承载另一条点位。字段口径固定，null 与空白统一归为空串。
 */
final class FacilityCreateFingerprint {

    private FacilityCreateFingerprint() {
    }

    static String of(FacilityDtos.CreateReq req) {
        String canonical = String.join("",
                nz(String.valueOf(req.getBuildingId())),
                nz(String.valueOf(req.getFloorId())),
                nz(req.getFacilityType()),
                nz(req.getSpec()),
                nz(req.getLocationDesc()),
                req.getInstallDate() == null ? "" : req.getInstallDate().toString(),
                req.getCheckCycleDays() == null ? "" : String.valueOf(req.getCheckCycleDays()),
                req.getPlanX() == null ? "" : String.valueOf(req.getPlanX()),
                req.getPlanY() == null ? "" : String.valueOf(req.getPlanY()),
                nz(req.getStatus()));
        return sha256(canonical);
    }

    private static String nz(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
