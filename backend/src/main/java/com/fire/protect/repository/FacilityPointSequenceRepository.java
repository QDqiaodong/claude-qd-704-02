package com.fire.protect.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fire.protect.domain.FacilityPointSequence;

import jakarta.persistence.LockModeType;

public interface FacilityPointSequenceRepository extends JpaRepository<FacilityPointSequence, Long> {

    /**
     * 行锁取号：对该建筑的流水行加悲观写锁（MySQL/H2 均为 SELECT ... FOR UPDATE），
     * 锁随事务提交释放。多应用实例并发时由数据库裁决先后，不依赖单机内存锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FacilityPointSequence s where s.buildingId = :buildingId")
    Optional<FacilityPointSequence> findForUpdateByBuildingId(@Param("buildingId") Long buildingId);
}
