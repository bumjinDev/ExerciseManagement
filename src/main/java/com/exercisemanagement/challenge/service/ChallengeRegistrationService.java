package com.exercisemanagement.challenge.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exercisemanagement.challenge.common.ChallengeStatus;
import com.exercisemanagement.challenge.config.ChallengeProperties;
import com.exercisemanagement.challenge.dto.request.ChallengeCreateRequest;
import com.exercisemanagement.challenge.dto.response.ChallengeCreateResponse;
import com.exercisemanagement.challenge.entity.Challenge;
import com.exercisemanagement.challenge.exception.ChallengeApiException;
import com.exercisemanagement.challenge.exception.ErrorCode;
import com.exercisemanagement.challenge.repository.ChallengeRepository;
import com.exercisemanagement.challenge.support.IdGenerator;

/**
 * 챌린지 등록과 성립 검증 (F001, 명세 6.1).
 * 형식 검증은 DTO(JSR-303)가, 종목 목록 검증은 이 서비스가 담당한다.
 */
@Service
public class ChallengeRegistrationService {

    private final ChallengeRepository challengeRepository;
    private final ChallengeProperties properties;
    private final IdGenerator idGenerator;

    public ChallengeRegistrationService(ChallengeRepository challengeRepository,
                                        ChallengeProperties properties,
                                        IdGenerator idGenerator) {
        this.challengeRepository = challengeRepository;
        this.properties = properties;
        this.idGenerator = idGenerator;
    }

    @Transactional
    public ChallengeCreateResponse register(String creatorId, ChallengeCreateRequest request) {

        // 카테고리·종목은 닫힌 목록 안에서만 (E-REG-UNKNOWN-EXERCISE)
        List<String> exercises = properties.getExerciseCatalog().get(request.getCategory());
        if (exercises == null || !exercises.contains(request.getExercise())) {
            throw new ChallengeApiException(ErrorCode.E_REG_UNKNOWN_EXERCISE);
        }

        Challenge challenge = Challenge.builder()
                .challengeId(idGenerator.challengeId())
                .category(request.getCategory())
                .exercise(request.getExercise())
                .teamCapacity(request.getTeamCapacity())
                .teamCount(request.getTeamCount())
                .prizeTeamCount(request.getPrizeTeamCount())
                .recruitStart(request.getRecruitPeriod().getStart())
                .recruitEnd(request.getRecruitPeriod().getEnd())
                .performStart(request.getPerformPeriod().getStart())
                .performEnd(request.getPerformPeriod().getEnd())
                .confirmWindowSeconds(request.getConfirmWindowLength().getSeconds())
                .dailyCap(request.getDailyCap())
                .cycleMode(request.getCycleMode())
                .cycleInterval(request.getCycleInterval())
                .depositAmount(request.getDepositAmount())
                .basePrizePool(properties.getBasePrizePool())   // 운영 기본 상금풀: 시스템 설정값 (변경사항 문서 4-1)
                .status(ChallengeStatus.RECRUITING)
                .creatorId(creatorId)
                .build();

        challengeRepository.save(challenge);

        return ChallengeCreateResponse.builder()
                .challengeId(challenge.getChallengeId())
                .category(challenge.getCategory())
                .exercise(challenge.getExercise())
                .targetParticipants(challenge.targetParticipants())
                .status(challenge.getStatus())
                .build();
    }
}
