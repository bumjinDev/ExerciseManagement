package com.exercisemanagement.challenge.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.exercisemanagement.challenge.common.ChallengeStatus;
import com.exercisemanagement.challenge.entity.Challenge;

public interface ChallengeRepository extends JpaRepository<Challenge, String> {

    List<Challenge> findByStatus(ChallengeStatus status);

    /** B-01 모집 종료 감지: 모집 종료 시각이 지난 모집 상태 챌린지 */
    List<Challenge> findByStatusAndRecruitEndBefore(ChallengeStatus status, LocalDateTime now);
}
