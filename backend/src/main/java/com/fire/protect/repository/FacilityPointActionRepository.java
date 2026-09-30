package com.fire.protect.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fire.protect.domain.FacilityPointAction;

public interface FacilityPointActionRepository extends JpaRepository<FacilityPointAction, Long> {

    /** 按客户端动作标识查首次处理记录（重放判定） */
    Optional<FacilityPointAction> findByActionKey(String actionKey);

    /**
     * 动作完成：把预留的 PROCESSING 行回填为 DONE（与点位在同一事务提交）。
     * 只有仍是 PROCESSING 的行能被回填，防止重复 / 越权完成。
     */
    @Modifying
    @Query("update FacilityPointAction a set a.pointId = :pointId, a.pointCode = :pointCode, a.status = 'DONE' "
            + "where a.actionKey = :actionKey and a.status = 'PROCESSING'")
    int complete(@Param("actionKey") String actionKey,
                 @Param("pointId") Long pointId,
                 @Param("pointCode") String pointCode);
}
