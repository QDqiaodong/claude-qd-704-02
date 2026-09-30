package com.fire.protect.service;

import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fire.protect.common.ConflictException;
import com.fire.protect.domain.FacilityPointAction;
import com.fire.protect.repository.FacilityPointActionRepository;

/**
 * 新增点位动作标识的「预留」（两阶段幂等的第一阶段）。
 *
 * <p>在一个很短的独立事务（REQUIRES_NEW）里只做一件事：往动作台账插入一行
 * status=PROCESSING 的预留行。{@code uk_point_action_key} 是整个新增流程真正的
 * 并发串行化点 —— 同一动作标识的并发请求只有一个能插入成功，输的一方立刻回滚短事务，
 * 根本不会进入后续取号，因此不会浪费 / 抢占编号。
 *
 * <p>预留行已独立提交：之后即使创建事务失败，动作标识也已被占用；
 * 重试会先看到它。处理进程崩溃则留下 PROCESSING 行，重试复用同一动作标识继续即可。
 */
@Service
public class FacilityActionReservation {

    private final FacilityPointActionRepository actionRepository;

    public FacilityActionReservation(FacilityPointActionRepository actionRepository) {
        this.actionRepository = actionRepository;
    }

    /**
     * 预留动作标识。
     *
     * @return 新插入的 PROCESSING 预留行；若该动作标识已存在则返回既有行（由调用方回放 / 判定）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FacilityPointAction reserve(String actionKey, Long buildingId, String fingerprint, String operator) {
        FacilityPointAction existing = actionRepository.findByActionKey(actionKey).orElse(null);
        if (existing != null) {
            return existing;
        }
        FacilityPointAction action = new FacilityPointAction();
        action.setActionKey(actionKey);
        action.setBuildingId(buildingId);
        action.setStatus("PROCESSING");
        action.setRequestFingerprint(fingerprint);
        action.setOperator(operator);
        action.setCreatedAt(LocalDateTime.now());
        try {
            actionRepository.saveAndFlush(action);
            return action;
        } catch (DataIntegrityViolationException e) {
            // uk_point_action_key：并发下另一请求已预留（事务可能还在提交中），交给调用方判定 / 重试
            throw new ConflictException("该新增动作正在另一请求中处理，请稍后用同一动作标识重试（本次未生成点位与编号）");
        }
    }
}
