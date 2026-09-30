package com.fire.protect.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fire.protect.repository.FacilityCodeSequenceRepository;

/**
 * 设施编号分配器：FAC/EXT-&lt;建筑编号&gt;-&lt;四位序号&gt;。
 *
 * <p>并发口径：取号在调用方事务内对 {@code facility_code_sequence} 序列行做
 * 原子自增（UPDATE ... SET next_value = next_value + 1）。行级写锁把同建筑的并发新增
 * 串行化（MySQL InnoDB 与 H2 语义一致，不使用 SELECT ... FOR UPDATE 的加锁读）：
 * 后到事务阻塞到先到事务提交，随后在同一事务读回自增后的值，因此序号各自不同、严格递增；
 * 序号与业务行同事务提交，失败一起回滚，不会留下悬空编号。锁与序列状态都在数据库，
 * 多个应用实例、服务重启后都安全，不依赖任何进程内锁。
 *
 * <p>删除口径：序号只增不减，删除点位不回退序列值，旧编号不会被重新分配。
 */
@Service
public class FacilityCodeAllocator {

    private final FacilityCodeSequenceRepository sequenceRepository;
    private final FacilitySequenceBootstrap bootstrap;

    public FacilityCodeAllocator(FacilityCodeSequenceRepository sequenceRepository,
                                 FacilitySequenceBootstrap bootstrap) {
        this.sequenceRepository = sequenceRepository;
        this.bootstrap = bootstrap;
    }

    /**
     * 在当前事务内分配下一个完整编号，如 FAC-B02-0032。
     *
     * @param prefix       编码前缀（FAC / EXT）
     * @param buildingCode 建筑编号（B02）
     */
    @Transactional
    public String allocate(String prefix, String buildingCode) {
        String seqKey = prefix + "-" + buildingCode;
        if (!sequenceRepository.existsBySeqKey(seqKey)) {
            // 序列行通常由建楼 / 初始化脚本预置；缺失（历史库直连等）时由独立事务按既有编号补齐
            bootstrap.ensure(seqKey, prefix);
        }
        // 原子自增：同键并发在此被行写锁串行化；锁持有到本事务提交，读回值不会被插队
        int updated = sequenceRepository.incrementNextValue(seqKey, java.time.LocalDateTime.now());
        if (updated == 0) {
            // 极端并发下序列行刚被并发补齐：按既有行重取一次
            sequenceRepository.incrementNextValue(seqKey, java.time.LocalDateTime.now());
        }
        long nextValue = sequenceRepository.findNextValueBySeqKey(seqKey);
        long allocated = nextValue - 1;
        return seqKey + "-" + String.format("%04d", allocated);
    }

    /** 新建建筑后预置序列行（FAC / EXT 各一行，从 0001 起）。 */
    @Transactional
    public void initForBuilding(String buildingCode) {
        bootstrap.ensure("FAC-" + buildingCode, "FAC");
        bootstrap.ensure("EXT-" + buildingCode, "EXT");
    }
}
