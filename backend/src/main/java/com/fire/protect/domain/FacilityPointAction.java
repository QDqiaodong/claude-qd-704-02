package com.fire.protect.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 「新增消防设施点位」客户端动作台账（幂等记录，只追加不修改）。
 *
 * <p>{@code actionKey} 是客户端为一次新增动作生成的唯一标识（UUID），
 * 超时重发 / 重复点击 / 网络重试必须复用同一个值：
 * <ul>
 *   <li>库级唯一约束 {@code uk_point_action_key} 保证同一动作只落成一条点位，
 *       重复请求只回放第一次创建结果，不再占用新编号；</li>
 *   <li>{@code requestFingerprint} 保存首次请求的业务字段指纹，
 *       同一动作标识却带着不同内容到达时按冲突拒绝，避免把第二次请求误当回放；</li>
 *   <li>记录与点位在同一数据库事务内提交：编号、点位、动作台账要么一起成功要么一起回滚，
 *       不会留下只有编号没有点位、只有点位没有编号或台账 / 平面图数量对不上的半成品；</li>
 *   <li>动作记录持久化在数据库，服务重启、多应用实例下都能识别已处理动作。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "facility_point_action")
public class FacilityPointAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 客户端动作标识（UUID），全局唯一；同一逻辑动作的重发必须复用 */
    @Column(name = "action_key", nullable = false, length = 64, unique = true, updatable = false)
    private String actionKey;

    @Column(name = "building_id", nullable = false, updatable = false)
    private Long buildingId;

    /**
     * 本动作创建出的点位 id：预留阶段为空，点位落库后在同一事务回填。
     * 只要为 PROCESSING 且长期无点位，说明当时在处理中失败，重试用同一动作继续即可。
     */
    @Column(name = "point_id")
    private Long pointId;

    /** 本动作实际分配到的点位编号，完成后回填，便于直接追踪 */
    @Column(name = "point_code", length = 32)
    private String pointCode;

    /** PROCESSING 预留处理中（尚未提交点位） / DONE 已完成（点位与台账一致） */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 首次请求业务字段的 SHA-256 指纹（hex），同一 actionKey 携带不同内容时拒绝 */
    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;

    @Column(name = "operator", nullable = false, length = 32, updatable = false)
    private String operator;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
