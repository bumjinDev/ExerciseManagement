package com.exercisemanagement.challenge.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exercisemanagement.challenge.common.ConfirmationDecision;
import com.exercisemanagement.challenge.common.ParticipationStatus;
import com.exercisemanagement.challenge.common.SubmissionStatus;
import com.exercisemanagement.challenge.dto.response.ConfirmationResponse;
import com.exercisemanagement.challenge.entity.Challenge;
import com.exercisemanagement.challenge.entity.Confirmation;
import com.exercisemanagement.challenge.entity.Participation;
import com.exercisemanagement.challenge.entity.Submission;
import com.exercisemanagement.challenge.exception.ChallengeApiException;
import com.exercisemanagement.challenge.exception.ErrorCode;
import com.exercisemanagement.challenge.repository.ChallengeRepository;
import com.exercisemanagement.challenge.repository.ConfirmationRepository;
import com.exercisemanagement.challenge.repository.ParticipationRepository;
import com.exercisemanagement.challenge.repository.SubmissionRepository;
import com.exercisemanagement.challenge.support.IdGenerator;

/**
 * 팀원 확인과 점수 반영 (F006, 명세 8.2.2).
 * 처리 순서: 권한 검사 → 판정 기록(append) → 상태 전이 → 누적 반영.
 * 정족수는 1 — 첫 확인이 확인 완료로 넘긴 뒤에는 그 제출이 더 이상 확인 대상이 아니다.
 * 기준 구현의 누적은 확인 완료 제출의 DB 집계로 계산하므로(8.3.3 미확정 유지),
 * 이 서비스의 "반영"은 상태 전이가 곧 반영이다.
 */
@Service
public class PeerConfirmationService {

    private final ChallengeRepository challengeRepository;
    private final ParticipationRepository participationRepository;
    private final SubmissionRepository submissionRepository;
    private final ConfirmationRepository confirmationRepository;
    private final IdGenerator idGenerator;

    public PeerConfirmationService(ChallengeRepository challengeRepository,
                                   ParticipationRepository participationRepository,
                                   SubmissionRepository submissionRepository,
                                   ConfirmationRepository confirmationRepository,
                                   IdGenerator idGenerator) {
        this.challengeRepository = challengeRepository;
        this.participationRepository = participationRepository;
        this.submissionRepository = submissionRepository;
        this.confirmationRepository = confirmationRepository;
        this.idGenerator = idGenerator;
    }

    @Transactional
    public ConfirmationResponse confirm(String submissionId, String confirmerId, ConfirmationDecision decision) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ChallengeApiException(ErrorCode.E_SUB_NOT_FOUND));

        Challenge challenge = challengeRepository.findById(submission.getChallengeId())
                .orElseThrow(() -> new ChallengeApiException(ErrorCode.E_CHL_NOT_FOUND));

        // 윈도우 종료 뒤 들어오는 확인은 종료 시각과 비교해 거부 (F008)
        if (LocalDateTime.now().isAfter(challenge.confirmWindowEnd())) {
            throw new ChallengeApiException(ErrorCode.E_CFM_WINDOW_CLOSED);
        }

        // 1. 권한 검사 (8.2.2 순서 그대로: 같은 팀 → 본인 아님 → 확인 대기 상태)
        Participation confirmer = participationRepository
                .findByChallengeIdAndParticipantId(submission.getChallengeId(), confirmerId)
                .filter(p -> p.getStatus() == ParticipationStatus.ACTIVE)
                .orElseThrow(() -> new ChallengeApiException(ErrorCode.E_CFM_NOT_TEAMMATE));

        Participation submitter = participationRepository
                .findByChallengeIdAndParticipantId(submission.getChallengeId(), submission.getParticipantId())
                .orElseThrow(() -> new ChallengeApiException(ErrorCode.E_CFM_NOT_TEAMMATE));

        if (confirmer.getTeamId() == null || !confirmer.getTeamId().equals(submitter.getTeamId())) {
            throw new ChallengeApiException(ErrorCode.E_CFM_NOT_TEAMMATE);
        }
        if (confirmerId.equals(submission.getParticipantId())) {
            throw new ChallengeApiException(ErrorCode.E_CFM_SELF_CONFIRM);
        }
        if (submission.getStatus() != SubmissionStatus.PENDING) {
            throw new ChallengeApiException(ErrorCode.E_CFM_ALREADY_TERMINAL);
        }
        // 이중 확인 방어: 기록이 이미 있으면 종착 처리된 것 (무제약 스키마 기간의 애플리케이션 방어)
        if (confirmationRepository.findBySubmissionId(submissionId).isPresent()) {
            throw new ChallengeApiException(ErrorCode.E_CFM_ALREADY_TERMINAL);
        }

        // 2. 판정 기록 — 고치거나 지우지 않고 쌓기만 한다
        confirmationRepository.save(Confirmation.builder()
                .confirmationId(idGenerator.confirmationId())
                .submissionId(submissionId)
                .confirmerId(confirmerId)
                .decision(decision)
                .confirmedAt(LocalDateTime.now())
                .build());

        // 3. 상태 전이 (정족수 1) + 4. 누적 반영
        boolean confirmed = decision == ConfirmationDecision.CONFIRM;
        submission.setStatus(confirmed ? SubmissionStatus.CONFIRMED : SubmissionStatus.REJECTED);
        submissionRepository.save(submission);

        return ConfirmationResponse.builder()
                .submissionId(submissionId)
                .resultStatus(submission.getStatus())
                .volumeApplied(confirmed)
                .appliedVolume(confirmed ? submission.getVolume() : BigDecimal.ZERO)
                .build();
    }
}
