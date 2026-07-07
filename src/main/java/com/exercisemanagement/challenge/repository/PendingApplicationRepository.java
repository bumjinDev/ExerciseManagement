package com.exercisemanagement.challenge.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.exercisemanagement.challenge.entity.PendingApplication;

public interface PendingApplicationRepository extends JpaRepository<PendingApplication, String> {

    Optional<PendingApplication> findByChallengeIdAndParticipantId(String challengeId, String participantId);
}
