package com.exercisemanagement.challenge.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.exercisemanagement.challenge.common.ConfirmationDecision;
import com.exercisemanagement.challenge.entity.Confirmation;

public interface ConfirmationRepository extends JpaRepository<Confirmation, String> {

    Optional<Confirmation> findBySubmissionId(String submissionId);

    /** 팀 순위 동점 판정용: 챌린지의 확인 기록 (제출과 조인) */
    @Query("SELECT c FROM Confirmation c, Submission s WHERE c.submissionId = s.submissionId "
            + "AND s.challengeId = :challengeId AND c.decision = :decision")
    List<Confirmation> findByChallengeAndDecision(@Param("challengeId") String challengeId,
                                                  @Param("decision") ConfirmationDecision decision);
}
