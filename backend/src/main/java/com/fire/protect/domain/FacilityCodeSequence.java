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
 * 设施编号单调序列（每个「前缀-建筑编号」一行，如 FAC-B02 / EXT-B01）。
 *
 * <p>取号在事务内以 {@code PESSIMISTIC_WRITE}（SELECT ... FOR UPDATE）锁住该行：
 * <ul>
 *   <li>同一建筑并发新增由数据库行锁串行化，每个请求拿到不同序号，不依赖单机内存锁，
 *       多个应用实例同时处理也安全；</li>
 *   <li>序号只增不减，点位删除后旧序号也不会被重新分配；</li>
 *   <li>序列状态在数据库里，服务重启后照常续号。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "facility_code_sequence")
public class FacilityCodeSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 序列键：<前缀>-<建筑编号>，如 FAC-B02 */
    @Column(name = "seq_key", nullable = false, length = 24, unique = true, updatable = false)
    private String seqKey;

    /** 下一个待分配序号（只增不减） */
    @Column(name = "next_value", nullable = false)
    private long nextValue;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
