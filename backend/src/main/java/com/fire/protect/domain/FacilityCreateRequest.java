package com.fire.protect.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 点位新增动作幂等台账（只追加，一行 = 一次已完成的新增动作）。
 *
 * <p>{@code actionKey} 是客户端为一次「新增点位」动作生成的唯一标识：
 * 超时重试、重复点击、网络重发、服务重启后重放都必须复用同一个值。
 * 主键唯一约束保证同一动作在库内只会落成一条点位。
 *
 * <p>{@code pointId/pointCode} 在 DDL 上可空，但仅在「台账占位 → 行锁取号 → 建点位」
 * 同一事务内的写入瞬间为空，事务提交时必然已写齐；保存中途失败整体回滚，
 * 库里不可能留下「只有台账/编号没有点位」的半成品。
 *
 * <p>{@code requestFingerprint} 为首次请求内容的 SHA-256 指纹：同一动作标识重放时若
 * 表单内容已被改成另一条点位，服务端拒绝并提示这是另一个动作（避免错把 B 内容记到 A 上）。
 */
@Data
@Entity
@Table(name = "facility_create_request")
public class FacilityCreateRequest {

    /** 客户端动作标识（幂等键），最长 64 */
    @Id
    @Column(name = "action_key", length = 64)
    private String actionKey;

    @Column(name = "building_id", nullable = false)
    private Long buildingId;

    @Column(name = "floor_id", nullable = false)
    private Long floorId;

    /** 首次请求内容指纹（SHA-256，十六进制 64 位） */
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    /** 本动作落成的点位 id；与点位同事务写入，提交后必非空 */
    @Column(name = "point_id")
    private Long pointId;

    /** 本动作实际分配到的点位编号（回放凭据，同时便于人工追踪） */
    @Column(name = "point_code", length = 32)
    private String pointCode;

    /** 提交人显示名（X-User-Id 解析；匿名调用时为空） */
    @Column(name = "created_by", length = 32)
    private String createdBy;

    /** 服务端首次受理时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
