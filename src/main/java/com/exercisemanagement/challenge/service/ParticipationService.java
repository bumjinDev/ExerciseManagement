package com.exercisemanagement.challenge.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.exercisemanagement.challenge.common.ChallengeStatus;
import com.exercisemanagement.challenge.common.ParticipationStatus;
import com.exercisemanagement.challenge.common.SubmissionStatus;
import com.exercisemanagement.challenge.dto.request.BaselineMeasurementRequest;
import com.exercisemanagement.challenge.dto.request.ParticipationApplyRequest;
import com.exercisemanagement.challenge.dto.response.ParticipationCompleteResponse;
import com.exercisemanagement.challenge.entity.Challenge;
import com.exercisemanagement.challenge.entity.Participation;
import com.exercisemanagement.challenge.entity.PendingApplication;
import com.exercisemanagement.challenge.entity.Submission;
import com.exercisemanagement.challenge.entity.Team;
import com.exercisemanagement.challenge.exception.ChallengeApiException;
import com.exercisemanagement.challenge.exception.ErrorCode;
import com.exercisemanagement.challenge.repository.ChallengeRepository;
import com.exercisemanagement.challenge.repository.ParticipationRepository;
import com.exercisemanagement.challenge.repository.PendingApplicationRepository;
import com.exercisemanagement.challenge.repository.SubmissionRepository;
import com.exercisemanagement.challenge.repository.TeamRepository;
import com.exercisemanagement.challenge.config.ChallengeProperties;
import com.exercisemanagement.challenge.support.IdGenerator;
import com.exercisemanagement.challenge.support.PhotoMetadataValidator;
import com.exercisemanagement.challenge.support.PhotoStorage;

/**
 * 참가 신청 (F002)·기준 측정 (F003)·편성 실행 조건 (F004).
 *
 * 신청 흐름(명세 6.2): 최근 30일 확인 완료 기록이 있으면 즉시 완료(201),
 * 없으면 개인 목표를 보관하고 기준 측정을 요청(202). 기준 측정 통과 시 완료.
 * 예치금은 신청이 완료되는 시점에 차감한다.
 */
@Service
public class ParticipationService {

    private static final Logger logger = LoggerFactory.getLogger(ParticipationService.class);

    /** 편성 실력 집계 윈도우(일). 잠정값 (명세 12.3) */
    private static final int SKILL_WINDOW_DAYS = 30;

    private final ChallengeRepository challengeRepository;
    private final ParticipationRepository participationRepository;
    private final PendingApplicationRepository pendingRepository;
    private final SubmissionRepository submissionRepository;
    private final TeamRepository teamRepository;
    private final DepositService depositService;
    private final TeamFormationEngine formationEngine;
    private final ChallengeProperties properties;
    private final PhotoStorage photoStorage;
    private final PhotoMetadataValidator metadataValidator;
    private final IdGenerator idGenerator;

    /** 인원 충족 판정과 편성 시작을 단일 실행으로 묶는 잠금 (변경사항 문서 4-12) */
    private final Object formationLock = new Object();

    public ParticipationService(ChallengeRepository challengeRepository,
                                ParticipationRepository participationRepository,
                                PendingApplicationRepository pendingRepository,
                                SubmissionRepository submissionRepository,
                                TeamRepository teamRepository,
                                DepositService depositService,
                                TeamFormationEngine formationEngine,
                                ChallengeProperties properties,
                                PhotoStorage photoStorage,
                                PhotoMetadataValidator metadataValidator,
                                IdGenerator idGenerator) {
        this.challengeRepository = challengeRepository;
        this.participationRepository = participationRepository;
        this.pendingRepository = pendingRepository;
        this.submissionRepository = submissionRepository;
        this.teamRepository = teamRepository;
        this.depositService = depositService;
        this.formationEngine = formationEngine;
        this.properties = properties;
        this.photoStorage = photoStorage;
        this.metadataValidator = metadataValidator;
        this.idGenerator = idGenerator;
    }

    /**
     * 참가 신청 (6.2.1).
     * @return 완료 응답(201 경로) 또는 null — null이면 기준 측정 필요(202 경로)
     */
    @Transactional
    public ParticipationCompleteResponse apply(String challengeId, String participantId,
                                               ParticipationApplyRequest request) {
        Challenge challenge = loadChallenge(challengeId);
        validateRecruiting(challenge);

        if (participationRepository.findByChallengeIdAndParticipantId(challengeId, participantId).isPresent()) {
            throw new ChallengeApiException(ErrorCode.E_APP_ALREADY_APPLIED);
        }

        BigDecimal coefficient = request.getIntensityCoefficient() == null
                ? BigDecimal.ONE : request.getIntensityCoefficient();
        validateGoalFrequency(challenge, request);

        // 최근 30일 확인 완료 기록에서 편성 실력·개인 목표 기준값 확정 시도 (F003)
        Optional<BigDecimal> skill = resolveSkillFromHistory(participantId, challenge.getExercise());

        if (skill.isEmpty()) {
            // 기록 없음 — 개인 목표를 보관하고 기준 측정을 요청한다 (202)
            PendingApplication pending = pendingRepository
                    .findByChallengeIdAndParticipantId(challengeId, participantId)
                    .orElseGet(() -> PendingApplication.builder()
                            .pendingId(idGenerator.pendingId())
                            .challengeId(challengeId)
                            .participantId(participantId)
                            .build());
            pending.setIntensityCoefficient(coefficient);
            pending.setGoalCycleMode(request.getGoalCycleMode());
            pending.setGoalCycleInterval(request.getGoalCycleInterval());
            pending.setCreatedAt(LocalDateTime.now());
            pendingRepository.save(pending);
            return null;
        }

        return completeParticipation(challenge, participantId, skill.get(),
                coefficient, request.getGoalCycleMode().name(), request.getGoalCycleInterval());
    }

    /**
     * 기준 측정 제출 (6.2.2). 팀원 확인 없이 기계 검증(해시 중복·메타데이터)만 거치고,
     * 통과하면 그 1회 볼륨으로 기준값을 확정하면서 신청을 완료한다.
     */
    @Transactional
    public ParticipationCompleteResponse submitBaseline(String challengeId, String participantId,
                                                        BaselineMeasurementRequest meta, MultipartFile photo) {
        Challenge challenge = loadChallenge(challengeId);
        validateRecruiting(challenge);

        PendingApplication pending = pendingRepository
                .findByChallengeIdAndParticipantId(challengeId, participantId)
                .orElseThrow(() -> new ChallengeApiException(ErrorCode.E_APP_NO_PENDING_MEASUREMENT));

        byte[] photoBytes = readBytes(photo);

        // 기계 검증: 해시 중복(1차 대조)과 메타데이터 정합성 (8.2.1 항목 2·3)
        String photoHash = photoStorage.sha256Hex(photoBytes);
        if (submissionRepository.existsByChallengeIdAndPhotoHash(challengeId, photoHash)) {
            throw new ChallengeApiException(ErrorCode.E_SUB_DUP_HASH);
        }
        LocalDate today = LocalDate.now();
        metadataValidator.validate(photoBytes, today);

        // 기준 측정 행: 검증 통과 시점에 확인 완료로 저장, linked_date는 등록 시점의 날짜 (7.1.4)
        String submissionId = idGenerator.submissionId();
        BigDecimal volume = meta.getWeight().multiply(BigDecimal.valueOf(meta.getReps()));
        String photoPath = photoStorage.save(challengeId, submissionId, photoBytes, photo.getOriginalFilename());

        submissionRepository.save(Submission.builder()
                .submissionId(submissionId)
                .challengeId(challengeId)
                .participantId(participantId)
                .weight(meta.getWeight())
                .reps(meta.getReps())
                .volume(volume)
                .photoHash(photoHash)
                .photoPath(photoPath)
                .registeredAt(LocalDateTime.now())
                .linkedDate(today)
                .status(SubmissionStatus.CONFIRMED)
                .baseline(true)
                .build());

        ParticipationCompleteResponse response = completeParticipation(challenge, participantId, volume,
                pending.getIntensityCoefficient(), pending.getGoalCycleMode().name(), pending.getGoalCycleInterval());

        pendingRepository.delete(pending);
        return response;
    }

    /**
     * 신청 완료 공통 처리: 참가 생성 → 예치 차감 → 인원 도달 판정·편성 실행 (F002·F004).
     */
    private ParticipationCompleteResponse completeParticipation(Challenge challenge, String participantId,
                                                                BigDecimal skill, BigDecimal coefficient,
                                                                String goalCycleModeName, int goalCycleInterval) {
        String participationId = idGenerator.participationId();

        Participation participation = Participation.builder()
                .participationId(participationId)
                .challengeId(challenge.getChallengeId())
                .participantId(participantId)
                .formationSkill(skill)
                .goalBaseline(skill)   // 편성 실력과 동일 값, 쓰이는 자리만 다름 (F003)
                .intensityCoefficient(coefficient)
                .goalCycleMode(com.exercisemanagement.challenge.common.CycleMode.valueOf(goalCycleModeName))
                .goalCycleInterval(goalCycleInterval)
                .depositAmount(challenge.getDepositAmount())
                .status(ParticipationStatus.ACTIVE)
                .build();

        // 예치 차감 — 잔액 부족이면 여기서 예외로 전체 롤백
        depositService.debitForJoin(participantId, challenge.getChallengeId(),
                challenge.getDepositAmount(), participationId);

        participationRepository.save(participation);

        boolean teamAssigned = checkFullAndFormTeams(challenge);

        return ParticipationCompleteResponse.builder()
                .participationId(participationId)
                .formationSkill(skill)
                .goalBaseline(skill)
                .intensityFloor(skill.multiply(coefficient))
                .depositCharged(challenge.getDepositAmount())
                .teamAssigned(teamAssigned)
                .build();
    }

    /**
     * 목표 인원 도달 시 편성 실행 (F004). 인원 충족 판정과 편성을 잠금으로 묶어
     * 정원 초과·이중 실행을 막는다 (기준 구현: 단일 인스턴스 전제).
     */
    private boolean checkFullAndFormTeams(Challenge challenge) {
        synchronized (formationLock) {
            long active = participationRepository.countByChallengeIdAndStatus(
                    challenge.getChallengeId(), ParticipationStatus.ACTIVE);
            if (active < challenge.targetParticipants()
                    || challenge.getStatus() != ChallengeStatus.RECRUITING) {
                return false;
            }

            List<Participation> participants = participationRepository
                    .findByChallengeIdAndStatus(challenge.getChallengeId(), ParticipationStatus.ACTIVE);

            List<TeamFormationEngine.Member> members = participants.stream()
                    .map(p -> new TeamFormationEngine.Member(p.getParticipationId(), p.getFormationSkill()))
                    .toList();

            List<List<TeamFormationEngine.Member>> assignment = formationEngine.form(
                    members, challenge.getTeamCount(), challenge.getTeamCapacity(),
                    properties.getFormationSumCapPercent());

            Map<String, Participation> byId = participants.stream()
                    .collect(Collectors.toMap(Participation::getParticipationId, p -> p));

            for (List<TeamFormationEngine.Member> teamMembers : assignment) {
                Team team = new Team(idGenerator.teamId(), challenge.getChallengeId());
                teamRepository.save(team);
                for (TeamFormationEngine.Member member : teamMembers) {
                    Participation p = byId.get(member.participationId());
                    p.setTeamId(team.getTeamId());
                    participationRepository.save(p);
                }
            }

            challenge.setStatus(ChallengeStatus.STARTED);
            challengeRepository.save(challenge);
            logger.info("팀 편성 완료: 챌린지 {}, 팀 {}개", challenge.getChallengeId(), assignment.size());
            return true;
        }
    }

    /**
     * 모집 미달 무산 처리 (B-01, 명세 8.4.4).
     * 챌린지를 무산으로 전이하고 이미 예치한 참가자 전원에게 예치금을 전액 반환한다.
     */
    @Transactional
    public void voidChallenge(String challengeId) {
        Challenge challenge = loadChallenge(challengeId);
        if (challenge.getStatus() != ChallengeStatus.RECRUITING) {
            return;
        }
        challenge.setStatus(ChallengeStatus.VOID);
        challengeRepository.save(challenge);

        for (Participation p : participationRepository
                .findByChallengeIdAndStatus(challengeId, ParticipationStatus.ACTIVE)) {
            depositService.credit(p.getParticipantId(), challengeId, p.getDepositAmount(),
                    com.exercisemanagement.challenge.common.DepositEntryType.VOID_RETURN,
                    "void:" + p.getParticipationId());
        }
        logger.info("모집 미달 무산 처리·예치 전액 반환: {}", challengeId);
    }

    /** 최근 30일 확인 완료 제출(기준 측정 행 포함)의 볼륨 1회 평균 (F003). */
    private Optional<BigDecimal> resolveSkillFromHistory(String participantId, String exercise) {
        LocalDate from = LocalDate.now().minusDays(SKILL_WINDOW_DAYS);
        List<Submission> history = submissionRepository.findRecentConfirmedByExercise(
                participantId, exercise, SubmissionStatus.CONFIRMED, from);
        if (history.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal sum = history.stream().map(Submission::getVolume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(sum.divide(BigDecimal.valueOf(history.size()), 4, RoundingMode.HALF_UP));
    }

    private void validateRecruiting(Challenge challenge) {
        LocalDateTime now = LocalDateTime.now();
        if (challenge.getStatus() != ChallengeStatus.RECRUITING
                || now.isBefore(challenge.getRecruitStart())
                || now.isAfter(challenge.getRecruitEnd())) {
            throw new ChallengeApiException(ErrorCode.E_APP_NOT_RECRUITING);
        }
    }

    /**
     * 목표 빈도는 하루 인증 횟수 상한 안으로 (F002).
     * 두 방식 모두 하루 1회 이하를 전제하므로, '주 며칠'의 간격이 7을 넘는 값만 걸러낸다.
     */
    private void validateGoalFrequency(Challenge challenge, ParticipationApplyRequest request) {
        if (request.getGoalCycleMode() == com.exercisemanagement.challenge.common.CycleMode.N_PER_WEEK
                && request.getGoalCycleInterval() > 7) {
            throw new ChallengeApiException(ErrorCode.E_APP_FREQ_OVER_CAP);
        }
    }

    private Challenge loadChallenge(String challengeId) {
        return challengeRepository.findById(challengeId)
                .orElseThrow(() -> new ChallengeApiException(ErrorCode.E_CHL_NOT_FOUND));
    }

    private byte[] readBytes(MultipartFile photo) {
        try {
            return photo.getBytes();
        } catch (Exception e) {
            throw new ChallengeApiException(ErrorCode.E_REQ_INVALID, "인증샷 파일을 읽을 수 없습니다.");
        }
    }
}
