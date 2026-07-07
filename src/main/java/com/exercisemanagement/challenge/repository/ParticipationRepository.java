package com.exercisemanagement.challenge.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.exercisemanagement.challenge.common.ParticipationStatus;
import com.exercisemanagement.challenge.entity.Participation;

public interface ParticipationRepository extends JpaRepository<Participation, String> {

    Optional<Participation> findByChallengeIdAndParticipantId(String challengeId, String participantId);

    List<Participation> findByChallengeId(String challengeId);

    List<Participation> findByChallengeIdAndStatus(String challengeId, ParticipationStatus status);

    long countByChallengeIdAndStatus(String challengeId, ParticipationStatus status);

    List<Participation> findByTeamIdAndStatus(String teamId, ParticipationStatus status);

    List<Participation> findByParticipantId(String participantId);
}
