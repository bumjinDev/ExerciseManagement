package com.exercisemanagement.challenge.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.exercisemanagement.challenge.entity.DepositLedgerEntry;

public interface DepositLedgerRepository extends JpaRepository<DepositLedgerEntry, String> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<DepositLedgerEntry> findByParticipantIdAndChallengeId(String participantId, String challengeId);
}
