package com.exercisemanagement.challenge.service;

import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exercisemanagement.challenge.common.ChallengeStatus;
import com.exercisemanagement.challenge.common.ParticipationStatus;
import com.exercisemanagement.challenge.dto.response.ChallengeDetailResponse;
import com.exercisemanagement.challenge.dto.response.ChallengeListResponse;
import com.exercisemanagement.challenge.dto.response.ChallengeSummaryResponse;
import com.exercisemanagement.challenge.dto.response.PeriodResponse;
import com.exercisemanagement.challenge.entity.Challenge;
import com.exercisemanagement.challenge.exception.ChallengeApiException;
import com.exercisemanagement.challenge.exception.ErrorCode;
import com.exercisemanagement.challenge.repository.ChallengeRepository;
import com.exercisemanagement.challenge.repository.ParticipationRepository;

/** 챌린지 목록·상세 조회 (명세 6.5). 공개 읽기 — 요청자 식별 불필요. */
@Service
public class ChallengeQueryService {

    private final ChallengeRepository challengeRepository;
    private final ParticipationRepository participationRepository;

    public ChallengeQueryService(ChallengeRepository challengeRepository,
                                 ParticipationRepository participationRepository) {
        this.challengeRepository = challengeRepository;
        this.participationRepository = participationRepository;
    }

    @Transactional(readOnly = true)
    public ChallengeListResponse list(ChallengeStatus status) {
        List<Challenge> challenges = (status == null)
                ? challengeRepository.findAll()
                : challengeRepository.findByStatus(status);

        List<ChallengeSummaryResponse> summaries = challenges.stream()
                .map(c -> ChallengeSummaryResponse.builder()
                        .challengeId(c.getChallengeId())
                        .category(c.getCategory())
                        .exercise(c.getExercise())
                        .status(c.getStatus())
                        .targetParticipants(c.targetParticipants())
                        .currentParticipants(currentParticipants(c.getChallengeId()))
                        .recruitPeriod(new PeriodResponse(c.getRecruitStart(), c.getRecruitEnd()))
                        .performPeriod(new PeriodResponse(c.getPerformStart(), c.getPerformEnd()))
                        .build())
                .toList();
        return new ChallengeListResponse(summaries);
    }

    @Transactional(readOnly = true)
    public ChallengeDetailResponse detail(String challengeId) {
        Challenge c = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new ChallengeApiException(ErrorCode.E_CHL_NOT_FOUND));

        return ChallengeDetailResponse.builder()
                .challengeId(c.getChallengeId())
                .category(c.getCategory())
                .exercise(c.getExercise())
                .teamCapacity(c.getTeamCapacity())
                .teamCount(c.getTeamCount())
                .prizeTeamCount(c.getPrizeTeamCount())
                .depositAmount(c.getDepositAmount())
                .basePrizePool(c.getBasePrizePool())
                .recruitPeriod(new PeriodResponse(c.getRecruitStart(), c.getRecruitEnd()))
                .performPeriod(new PeriodResponse(c.getPerformStart(), c.getPerformEnd()))
                .confirmWindowLength(Duration.ofSeconds(c.getConfirmWindowSeconds()))
                .dailyCap(c.getDailyCap())
                .cycleMode(c.getCycleMode())
                .cycleInterval(c.getCycleInterval())
                .status(c.getStatus())
                .targetParticipants(c.targetParticipants())
                .currentParticipants(currentParticipants(c.getChallengeId()))
                .build();
    }

    private int currentParticipants(String challengeId) {
        return (int) participationRepository.countByChallengeIdAndStatus(challengeId, ParticipationStatus.ACTIVE);
    }
}
