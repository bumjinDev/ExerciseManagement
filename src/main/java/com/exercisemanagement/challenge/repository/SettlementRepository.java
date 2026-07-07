package com.exercisemanagement.challenge.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.exercisemanagement.challenge.entity.Settlement;

public interface SettlementRepository extends JpaRepository<Settlement, String> {

    boolean existsByChallengeId(String challengeId);

    Optional<Settlement> findByChallengeId(String challengeId);
}
