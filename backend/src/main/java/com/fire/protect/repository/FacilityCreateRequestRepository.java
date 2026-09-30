package com.fire.protect.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fire.protect.domain.FacilityCreateRequest;

public interface FacilityCreateRequestRepository extends JpaRepository<FacilityCreateRequest, String> {

    Optional<FacilityCreateRequest> findByActionKey(String actionKey);

    /** 防御性校验：每个点位只可能由一个动作创建 */
    Optional<FacilityCreateRequest> findByPointId(Long pointId);
}
