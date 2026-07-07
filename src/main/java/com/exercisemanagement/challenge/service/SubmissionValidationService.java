package com.exercisemanagement.challenge.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.exercisemanagement.challenge.common.ChallengeStatus;
import com.exercisemanagement.challenge.common.ParticipationStatus;
import com.exercisemanagement.challenge.common.SubmissionStatus;
import com.exercisemanagement.challenge.dto.request.SubmissionCreateRequest;
import com.exercisemanagement.challenge.dto.response.SubmissionCreateResponse;
import com.exercisemanagement.challenge.entity.Challenge;
import com.exercisemanagement.challenge.entity.Submission;
import com.exercisemanagement.challenge.exception.ChallengeApiException;
import com.exercisemanagement.challenge.exception.ErrorCode;
import com.exercisemanagement.challenge.repository.ChallengeRepository;
import com.exercisemanagement.challenge.repository.ParticipationRepository;
import com.exercisemanagement.challenge.repository.SubmissionRepository;
import com.exercisemanagement.challenge.support.IdGenerator;
import com.exercisemanagement.challenge.support.PhotoMetadataValidator;
import com.exercisemanagement.challenge.support.PhotoStorage;

/**
 * 인증 제출과 시스템 기계 검증 (F005, 명세 8.2.1).
 * 다섯 항목을 순서대로 검사하고, 하나라도 걸리면 거부한다.
 * 통과한 제출만 확인 대기(PENDING)로 남아 팀원 확인을 기다린다.
 */
@Service
public class SubmissionValidationService {

    /** 인정(집계 대상) 제출 상태: 반려·만료가 아닌 것 (변경사항 문서 4-5·4-6) */
    private static final List<SubmissionStatus> COUNTABLE =
            List.of(SubmissionStatus.PENDING, SubmissionStatus.CONFIRMED);

    private final ChallengeRepository challengeRepository;
    private final ParticipationRepository participationRepository;
    private final SubmissionRepository submissionRepository;
    private final PhotoStorage photoStorage;
    private final PhotoMetadataValidator metadataValidator;
    private final IdGenerator idGenerator;

    public SubmissionValidationService(ChallengeRepository challengeRepository,
                                       ParticipationRepository participationRepository,
                                       SubmissionRepository submissionRepository,
                                       PhotoStorage photoStorage,
                                       PhotoMetadataValidator metadataValidator,
                                       IdGenerator idGenerator) {
        this.challengeRepository = challengeRepository;
        this.participationRepository = participationRepository;
        this.submissionRepository = submissionRepository;
        this.photoStorage = photoStorage;
        this.metadataValidator = metadataValidator;
        this.idGenerator = idGenerator;
    }

    @Transactional
    public SubmissionCreateResponse submit(String challengeId, String participantId,
                                           SubmissionCreateRequest meta, MultipartFile photo) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new ChallengeApiException(ErrorCode.E_CHL_NOT_FOUND));

        // 요청자가 이 챌린지의 진행 중 참가자인지 (권한체계 설계안 2계층)
        participationRepository.findByChallengeIdAndParticipantId(challengeId, participantId)
                .filter(p -> p.getStatus() == ParticipationStatus.ACTIVE)
                .orElseThrow(() -> new ChallengeApiException(ErrorCode.E_SUB_NOT_PARTICIPANT));

        LocalDateTime now = LocalDateTime.now();
        LocalDate performedDate = meta.getPerformedDate();

        // 1. 제출 완전 마감 — 가장 싼 검사를 맨 앞에 (8.2.1)
        if (challenge.getStatus() == ChallengeStatus.RECRUITING || now.isBefore(challenge.getPerformStart())) {
            throw new ChallengeApiException(ErrorCode.E_SUB_BEFORE_START);
        }
        if (now.isAfter(challenge.submissionFullDeadline()) || challenge.getStatus() != ChallengeStatus.STARTED) {
            throw new ChallengeApiException(ErrorCode.E_SUB_AFTER_DEADLINE);
        }

        // 2. 사진 해시 중복 (1차 대조. 2차는 저장 계층 유니크 제약 — 무제약 스키마 기간에는 1차만 동작)
        byte[] photoBytes = readBytes(photo);
        String photoHash = photoStorage.sha256Hex(photoBytes);
        if (submissionRepository.existsByChallengeIdAndPhotoHash(challengeId, photoHash)) {
            throw new ChallengeApiException(ErrorCode.E_SUB_DUP_HASH);
        }

        // 3. 사진 메타데이터 정합성
        metadataValidator.validate(photoBytes, performedDate);

        // 4. 소급 등록 유예: 미래 날짜 불가, 오늘 또는 어제(24시간)까지만, 수행 기간 안의 날짜만
        LocalDate today = now.toLocalDate();
        if (performedDate.isAfter(today) || performedDate.isBefore(today.minusDays(1))) {
            throw new ChallengeApiException(ErrorCode.E_SUB_BACKDATE_EXCEEDED);
        }
        if (performedDate.isBefore(challenge.getPerformStart().toLocalDate())
                || performedDate.isAfter(challenge.getPerformEnd().toLocalDate())) {
            throw new ChallengeApiException(ErrorCode.E_SUB_DATE_OUT_OF_PERIOD);
        }

        // 5. 시간 윈도우: 하루 상한과 인증 주기 — 판정 기준일은 수행 날짜 (8.2.1)
        validateFrequency(challenge, participantId, performedDate);

        // 저장: 확인 대기 진입
        String submissionId = idGenerator.submissionId();
        String photoPath = photoStorage.save(challengeId, submissionId, photoBytes, photo.getOriginalFilename());
        BigDecimal volume = meta.getWeight().multiply(BigDecimal.valueOf(meta.getReps()));

        Submission submission = Submission.builder()
                .submissionId(submissionId)
                .challengeId(challengeId)
                .participantId(participantId)
                .weight(meta.getWeight())
                .reps(meta.getReps())
                .volume(volume)
                .photoHash(photoHash)
                .photoPath(photoPath)
                .registeredAt(now)
                .linkedDate(performedDate)
                .status(SubmissionStatus.PENDING)
                .baseline(false)
                .build();
        submissionRepository.save(submission);

        return SubmissionCreateResponse.builder()
                .submissionId(submissionId)
                .status(SubmissionStatus.PENDING)
                .linkedDate(performedDate)
                .registeredAt(now)
                .build();
    }

    /**
     * 하루 상한·인증 주기 판정 (변경사항 문서 4-5·4-6).
     * - 하루 상한: 같은 수행 날짜의 인정 제출 수 < daily_cap
     * - 며칠에 한 번(n): 다른 인증 날짜와의 간격이 n일 미만이면 위반
     * - 주 며칠(k): 그 수행 날짜가 속한 달력 주(월요일 시작)의 인증 날짜 수가 k 이상이면 위반
     */
    private void validateFrequency(Challenge challenge, String participantId, LocalDate performedDate) {
        long sameDay = submissionRepository.countDailySubmissions(
                challenge.getChallengeId(), participantId, performedDate, COUNTABLE);
        if (sameDay >= challenge.getDailyCap()) {
            throw new ChallengeApiException(ErrorCode.E_SUB_FREQ_VIOLATION);
        }

        List<LocalDate> countedDates = submissionRepository.findCountableDates(
                challenge.getChallengeId(), participantId, COUNTABLE);

        switch (challenge.getCycleMode()) {
            case EVERY_N_DAYS -> {
                int n = challenge.getCycleInterval();
                boolean violation = countedDates.stream()
                        .filter(d -> !d.equals(performedDate)) // 같은 날짜 추가 제출은 하루 상한이 관할
                        .anyMatch(d -> Math.abs(ChronoUnit.DAYS.between(d, performedDate)) < n);
                if (violation) {
                    throw new ChallengeApiException(ErrorCode.E_SUB_FREQ_VIOLATION);
                }
            }
            case N_PER_WEEK -> {
                int k = challenge.getCycleInterval();
                // 달력 주(월요일 시작) 판정: 같은 주 = 그 주 월요일이 같음
                LocalDate weekStart = performedDate.minusDays(performedDate.getDayOfWeek().getValue() - 1L);
                LocalDate weekEnd = weekStart.plusDays(6);
                long daysInWeek = countedDates.stream()
                        .filter(d -> !d.equals(performedDate))
                        .filter(d -> !d.isBefore(weekStart) && !d.isAfter(weekEnd))
                        .count();
                if (daysInWeek >= k) {
                    throw new ChallengeApiException(ErrorCode.E_SUB_FREQ_VIOLATION);
                }
            }
        }
    }

    private byte[] readBytes(MultipartFile photo) {
        try {
            return photo.getBytes();
        } catch (Exception e) {
            throw new ChallengeApiException(ErrorCode.E_REQ_INVALID, "인증샷 파일을 읽을 수 없습니다.");
        }
    }
}
