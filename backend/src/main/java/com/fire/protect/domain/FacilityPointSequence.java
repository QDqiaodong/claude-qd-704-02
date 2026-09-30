package com.fire.protect.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 点位编号流水（每栋建筑一行）。
 *
 * <p>取号走 {@code SELECT ... FOR UPDATE} 行锁：同一建筑的并发新增在数据库层串行，
 * 不同建筑各锁各行互不阻塞；多个应用实例共用同一把 InnoDB 行锁，不依赖任何进程内内存。
 *
 * <p>{@code nextSeq} 单调递增、只进不退：
 * <ul>
 *   <li>序号随取号即向前推进，已用编号（含被删除点位的编号）永远不会再次分配；</li>
 *   <li>事务回滚时连推进一起回退，不留下「只有编号没有点位」的半成品；</li>
 *   <li>初始化基线取该建筑既有编号的最大尾号 + 1，老编号格式与种子数据完全兼容。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "facility_point_sequence")
public class FacilityPointSequence {

    /** 所属建筑（= building.id，一对一） */
    @Id
    @Column(name = "building_id")
    private Long buildingId;

    /** 该建筑下一个可分配的 FAC 尾号（四位序号） */
    @Column(name = "next_seq", nullable = false)
    private long nextSeq;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
