package com.fire.protect.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fire.protect.common.ConflictException;
import com.fire.protect.domain.Extinguisher;
import com.fire.protect.domain.FacilityCodeSequence;
import com.fire.protect.domain.FacilityPoint;
import com.fire.protect.repository.ExtinguisherRepository;
import com.fire.protect.repository.FacilityCodeSequenceRepository;
import com.fire.protect.repository.FacilityPointRepository;

/**
 * 编号序列行的初始化（与 {@link FacilityCodeAllocator} 分开，
 * 保证 {@code REQUIRES_NEW} 经 Spring 代理生效）。
 *
 * <p>序列行通常由建楼流程 / 初始化脚本预置；这里只负责历史库、直连数据等
 * 「序列行缺失」场景下按既有编号最大尾号补齐，初始值不会压到历史编号。
 */
@Service
public class FacilitySequenceBootstrap {

    private final FacilityCodeSequenceRepository sequenceRepository;
    private final FacilityPointRepository facilityPointRepository;
    private final ExtinguisherRepository extinguisherRepository;

    public FacilitySequenceBootstrap(FacilityCodeSequenceRepository sequenceRepository,
                                     FacilityPointRepository facilityPointRepository,
                                     ExtinguisherRepository extinguisherRepository) {
        this.sequenceRepository = sequenceRepository;
        this.facilityPointRepository = facilityPointRepository;
        this.extinguisherRepository = extinguisherRepository;
    }

    /**
     * 确保序列行存在。独立事务提交：调用方事务回滚不影响初始化结果，
     * 初始化已提交后调用方重试也能直接取到行锁。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FacilityCodeSequence ensure(String seqKey, String prefix) {
        return sequenceRepository.findBySeqKey(seqKey)
                .orElseGet(() -> {
                    LocalDateTime now = LocalDateTime.now();
                    FacilityCodeSequence sequence = new FacilityCodeSequence();
                    sequence.setSeqKey(seqKey);
                    sequence.setNextValue(maxExistingTail(prefix, seqKey + "-"));
                    sequence.setCreatedAt(now);
                    sequence.setUpdatedAt(now);
                    try {
                        return sequenceRepository.saveAndFlush(sequence);
                    } catch (DataIntegrityViolationException e) {
                        // 并发下另一个事务已建好该行：以库里那行为准
                        return sequenceRepository.findBySeqKey(seqKey)
                                .orElseThrow(() -> new ConflictException("编号序列初始化冲突，请重试本次操作"));
                    }
                });
    }

    /**
     * 同前缀既有编号的最大尾号 + 1。只统计形如 前缀-建筑-四位数字 的尾号，
     * 非数字历史编码不计入。
     */
    private long maxExistingTail(String prefix, String fullPrefix) {
        long max = 0L;
        List<String> codes = "EXT".equals(prefix)
                ? extinguisherRepository.findByPointCodeStartingWith(fullPrefix).stream()
                        .map(Extinguisher::getPointCode).toList()
                : facilityPointRepository.findByPointCodeStartingWith(fullPrefix).stream()
                        .map(FacilityPoint::getPointCode).toList();
        for (String code : codes) {
            if (code == null || code.length() < 4) {
                continue;
            }
            try {
                max = Math.max(max, Long.parseLong(code.substring(code.length() - 4)));
            } catch (NumberFormatException ignored) {
                // 编码尾部不是数字（导入的历史数据），跳过不计入序号
            }
        }
        return max + 1;
    }
}
