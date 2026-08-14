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
 * 참가 신청 (F002)·기준 측정 (F003)·편성 실행 조건 (F004)을 담당하는 서비스.
 * <p>
 * 신청 흐름(명세 6.2)은 두 갈래다:
 * <ul>
 *   <li>최근 30일 안에 확인 완료된 인증 기록이 있는 사람 → 그 기록으로 실력을 계산해
 *       즉시 신청 완료 (HTTP 201 경로)</li>
 *   <li>기록이 없는 사람 → 신청 내용을 임시 보관(PendingApplication)하고
 *       "기준 측정을 먼저 하라"고 응답 (HTTP 202 경로).
 *       이후 기준 측정을 제출해 통과하면 그때 신청이 완료된다.</li>
 * </ul>
 * 예치금은 신청이 "완료되는 시점"에 차감한다. 202로 대기 중일 때는 돈이 빠지지 않는다.
 */
@Service
public class ParticipationService {

    private static final Logger logger = LoggerFactory.getLogger(ParticipationService.class);

    /** 편성 실력 집계 윈도우(일). "최근 30일 기록"의 그 30이다. 잠정값 (명세 12.3) */
    private static final int SKILL_WINDOW_DAYS = 30;

    private final ChallengeRepository challengeRepository;         // 챌린지 조회·상태 변경
    private final ParticipationRepository participationRepository; // 참가(확정된 신청) 저장소
    private final PendingApplicationRepository pendingRepository;  // 기준 측정 대기 중인 신청 보관소
    private final SubmissionRepository submissionRepository;       // 인증 제출 기록 (실력 계산·해시 중복 검사용)
    private final TeamRepository teamRepository;                   // 편성된 팀 저장소
    private final DepositService depositService;                   // 예치금 차감·반환
    private final TeamFormationEngine formationEngine;             // 실력 기반 팀 배정 알고리즘
    private final ChallengeProperties properties;                  // 설정값 (팀 실력합 상한 % 등)
    private final PhotoStorage photoStorage;                       // 인증샷 파일 저장·해시 계산
    private final PhotoMetadataValidator metadataValidator;        // 사진 메타데이터(촬영 시각 등) 검증
    private final IdGenerator idGenerator;                         // DB 시퀀스 기반 ID 생성기 ("part_N" 등)

    /**
     * 인원 충족 판정과 편성 시작을 단일 실행으로 묶는 잠금 (변경사항 문서 4-12).
     * 마지막 자리를 두 명이 동시에 신청하면 둘 다 "정원 도달"로 판정해 편성이
     * 두 번 돌 수 있으므로, 판정~편성 구간을 한 번에 한 스레드만 지나가게 한다.
     */
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
     * 참가 신청 (명세 6.2.1).
     *
     * @param challengeId   신청할 챌린지 ID
     * @param participantId 신청자 ID
     * @param request       신청 내용 (강도 계수, 개인 목표 주기)
     * @return 신청 완료 응답(201 경로) 또는 null — null이면 기준 측정이 필요하다는 뜻(202 경로)
     * @throws ChallengeApiException E_CHL_NOT_FOUND — 챌린지가 없을 때,
     *                               E_APP_NOT_RECRUITING — 모집 중이 아닐 때,
     *                               E_APP_ALREADY_APPLIED — 이미 신청했을 때,
     *                               E_APP_FREQ_OVER_CAP — 목표 빈도가 불가능한 값일 때
     */
    @Transactional
    public ParticipationCompleteResponse apply(String challengeId, String participantId,
                                               ParticipationApplyRequest request) {
        // 1) 챌린지 존재 확인 + 지금이 모집 기간인지 검증
        Challenge challenge = loadChallenge(challengeId);
        validateRecruiting(challenge);

        // 2) 같은 챌린지에 이미 신청한 사람이면 거부
        if (participationRepository.findByChallengeIdAndParticipantId(challengeId, participantId).isPresent()) {
            throw new ChallengeApiException(ErrorCode.E_APP_ALREADY_APPLIED);
        }

        // 3) 강도 계수: 안 보냈으면 기본값 1. 목표 빈도는 달성 가능한 값인지 검사 ("주 8일" 차단)
        BigDecimal coefficient = request.getIntensityCoefficient() == null
                ? BigDecimal.ONE : request.getIntensityCoefficient();
        validateGoalFrequency(challenge, request);

        // 4) 최근 30일 확인 완료 기록으로 편성 실력(=개인 목표 기준값) 계산 시도 (F003)
        Optional<BigDecimal> skill = resolveSkillFromHistory(participantId, challenge.getExercise());

        if (skill.isEmpty()) {
            // 5-a) 기록 없음 → 신청 내용을 PendingApplication에 보관하고 null 반환 (202: 기준 측정 요청).
            //      재신청이면 기존 보관 행을 찾아 덮어쓰고, 처음이면 새로 만든다.
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

        // 5-b) 기록 있음 → 그 실력값으로 바로 신청 완료 (201)
        return completeParticipation(challenge, participantId, skill.get(),
                coefficient, request.getGoalCycleMode().name(), request.getGoalCycleInterval());
    }

    /**
     * 기준 측정 제출 (명세 6.2.2). apply()에서 202를 받은(=운동 기록이 없는) 사람이
     * "지금 실력이 이 정도"라는 측정 1회분을 사진과 함께 제출하는 단계.
     * 일반 인증과 달리 팀원 확인 없이 기계 검증(사진 해시 중복·메타데이터)만 거치고,
     * 통과하면 그 1회 볼륨(무게×횟수)을 기준값으로 확정하면서 보관해둔 신청을 완료한다.
     *
     * @param challengeId   챌린지 ID
     * @param participantId 제출자 ID
     * @param meta          측정 내용 (무게, 횟수)
     * @param photo         측정 인증샷
     * @return 신청 완료 응답
     * @throws ChallengeApiException E_APP_NO_PENDING_MEASUREMENT — 기준 측정 대기 상태가 아닐 때,
     *                               E_SUB_DUP_HASH — 같은 사진이 이미 제출됐을 때
     */
    @Transactional
    public ParticipationCompleteResponse submitBaseline(String challengeId, String participantId,
                                                        BaselineMeasurementRequest meta, MultipartFile photo) {
        // 1) 챌린지 존재·모집 기간 검증 (모집이 끝났으면 기준 측정도 의미 없음)
        Challenge challenge = loadChallenge(challengeId);
        validateRecruiting(challenge);

        // 2) 보관해둔 신청(PendingApplication)이 있어야 한다. 없으면 apply()를 거치지 않은 것
        PendingApplication pending = pendingRepository
                .findByChallengeIdAndParticipantId(challengeId, participantId)
                .orElseThrow(() -> new ChallengeApiException(ErrorCode.E_APP_NO_PENDING_MEASUREMENT));

        byte[] photoBytes = readBytes(photo);

        // 3) 기계 검증 (8.2.1 항목 2·3)
        //    - 해시 중복: 같은 챌린지에 동일한 사진 파일이 이미 있으면 재사용으로 보고 거부
        //    - 메타데이터: 촬영 정보가 오늘 날짜와 맞는지 확인
        String photoHash = photoStorage.sha256Hex(photoBytes);
        if (submissionRepository.existsByChallengeIdAndPhotoHash(challengeId, photoHash)) {
            throw new ChallengeApiException(ErrorCode.E_SUB_DUP_HASH);
        }
        LocalDate today = LocalDate.now();
        metadataValidator.validate(photoBytes, today);

        // 4) 기준 측정도 제출(Submission) 행으로 저장한다.
        //    일반 인증과 다른 점: 확인 대기(PENDING) 없이 바로 CONFIRMED, baseline=true 표시,
        //    linked_date는 등록 시점의 날짜 (7.1.4)
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

        // 5) 측정 1회 볼륨을 실력값으로 삼아, 보관해뒀던 신청 내용(계수·목표 주기)으로 신청 완료
        ParticipationCompleteResponse response = completeParticipation(challenge, participantId, volume,
                pending.getIntensityCoefficient(), pending.getGoalCycleMode().name(), pending.getGoalCycleInterval());

        // 6) 신청이 완료됐으니 임시 보관 행은 삭제
        pendingRepository.delete(pending);
        return response;
    }

    /**
     * 신청 완료 공통 처리. 201 경로(apply)와 기준 측정 경로(submitBaseline)가 마지막에 합류하는 곳.
     * 순서: 참가 행 생성 → 예치금 차감 → 정원 도달이면 팀 편성 (F002·F004).
     *
     * @param challenge         대상 챌린지
     * @param participantId     참가자 ID
     * @param skill             확정된 실력값 (30일 평균 볼륨 또는 기준 측정 1회 볼륨)
     * @param coefficient       강도 계수 (개인 목표 하한 = skill × coefficient)
     * @param goalCycleModeName 개인 목표 주기 방식 이름 (EVERY_N_DAYS / N_PER_WEEK)
     * @param goalCycleInterval 개인 목표 주기 숫자 (N일에 한 번의 N, 주 N일의 N)
     * @return 신청 완료 응답 (참가 ID, 실력값, 차감액, 팀 배정 여부)
     * @throws ChallengeApiException E_APP_INSUFFICIENT_BALANCE — 예치금 잔액 부족 시 (전체 롤백)
     */
    private ParticipationCompleteResponse completeParticipation(Challenge challenge, String participantId,
                                                                BigDecimal skill, BigDecimal coefficient,
                                                                String goalCycleModeName, int goalCycleInterval) {
        // 1) 참가 ID 발급 ("part_N") 후 참가 엔티티 구성
        String participationId = idGenerator.participationId();

        Participation participation = Participation.builder()
                .participationId(participationId)
                .challengeId(challenge.getChallengeId())
                .participantId(participantId)
                .formationSkill(skill)
                .goalBaseline(skill)   // 편성 실력과 같은 값. 편성용/목표 계산용으로 쓰이는 자리만 다르다 (F003)
                .intensityCoefficient(coefficient)
                .goalCycleMode(com.exercisemanagement.challenge.common.CycleMode.valueOf(goalCycleModeName))
                .goalCycleInterval(goalCycleInterval)
                .depositAmount(challenge.getDepositAmount())
                .status(ParticipationStatus.ACTIVE)
                .build();

        // 2) 예치금 차감. 잔액 부족이면 여기서 예외 → @Transactional이 참가 생성까지 전부 롤백
        depositService.debitForJoin(participantId, challenge.getChallengeId(),
                challenge.getDepositAmount(), participationId);

        // 3) 참가 확정 저장
        participationRepository.save(participation);

        // 4) 이 신청으로 정원이 찼는지 확인하고, 찼으면 즉시 팀 편성 실행
        boolean teamAssigned = checkFullAndFormTeams(challenge);    /* challenge 객체 : 맨 처음 챌린지 입장 신청시
                                                                      * 컨트롤러로부터 호출된 "apply()" 에서 요청에 포함된
                                                                        챌린지 id 를 가지고 조회된 챌린지 데이터 행 한줄.
                                                                    */


        return ParticipationCompleteResponse.builder()
                .participationId(participationId)
                .formationSkill(skill)
                .goalBaseline(skill)
                .intensityFloor(skill.multiply(coefficient)) // 개인 목표 하한 = 실력 × 강도 계수
                .depositCharged(challenge.getDepositAmount())
                .teamAssigned(teamAssigned)
                .build();
    }

    /**
     * 목표 인원 도달 시 팀 편성 실행 (F004).
     * 인원 충족 판정과 편성을 formationLock으로 묶어, 동시 신청 시
     * 정원 초과 판정이나 편성 이중 실행을 막는다 (기준 구현: 서버 1대 전제).
     *
     * @param challenge 대상 챌린지
     * @return 이번 호출에서 편성이 실행됐으면 true, 아직 정원 미달 등으로 안 됐으면 false
     */
    private boolean checkFullAndFormTeams(Challenge challenge) {
        synchronized (formationLock) {
            // 1) 활성 참가자 수가 목표 인원에 미달이거나, 이미 모집 상태가 아니면(=이미 편성됨) 종료
            long active = participationRepository.countByChallengeIdAndStatus(
                    challenge.getChallengeId(), ParticipationStatus.ACTIVE);

            /* 모집이 완료 되는 기준은 팀 계획 인원 * 각 팀별 인원, 그리고 현재 챌린지 상태가 모집 중(Recruiting) 이어야 만 한다. */
            if (active < challenge.targetParticipants() || challenge.getStatus() != ChallengeStatus.RECRUITING) {
                return false;
            }

            /* 2) 활성 참가자 전원을 편성 엔진에 넣고 돌리기 위해 가공 하기 위해 해당 챌린지 id 를 가지고 챌린지 참가 내역에서 챌린지 참여 신청한 사람들을 조회한다.
                그리고 여기서 active 값만 조회하는 것은 중간에 챌린지 이탈하지 않은 상태 값의 사용자만 조회하기 위함
             */
            List<Participation> participants = participationRepository
                    .findByChallengeIdAndStatus(challenge.getChallengeId(), ParticipationStatus.ACTIVE);

            /* 현재 챌린지 내 참가 신청을 한 사람들을 배정시키기 위해 참가 신천 사람들 내역을 가져와서 각 사람 별로 유저 id 와 유저 볼륨 값을 가져와서 Member 레코드로 만들기. */
            List<TeamFormationEngine.Member> members = participants.stream()
                    .map(p -> new TeamFormationEngine.Member(p.getParticipationId(), p.getFormationSkill()))
                    .toList();

            // 3) 편성 엔진 실행: 팀 수·팀 정원·팀 실력합 상한(%)을 지켜 팀별 명단을 받는다
            List<List<TeamFormationEngine.Member>> assignment = formationEngine.form(
                    members,                                 // 편성 대상: 활성 참가자 전원을 (참가ID, 편성실력) Member로 변환한 목록. size == teamCount * teamCapacity
                    challenge.getTeamCount(),                // 팀 수 K: 챌린지 등록값(F001). 만들 팀의 개수
                    challenge.getTeamCapacity(),             // 팀 정원 N: 챌린지 등록값(F001). 팀 하나당 인원 수
                    properties.getFormationSumCapPercent()); // 합 상한 r(%): 설정값(ChallengeProperties -> application.yml). 허용폭 = (전체 팀 합 평균 × r%) 계산의 근거

            // 4) 결과 반영: 팀 행을 만들고 각 참가자에게 팀 ID를 기록
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

            // 5) 챌린지를 시작 상태로 전환 (이후 신청은 validateRecruiting에서 거부됨)
            challenge.setStatus(ChallengeStatus.STARTED);
            challengeRepository.save(challenge);
            logger.info("팀 편성 완료: 챌린지 {}, 팀 {}개", challenge.getChallengeId(), assignment.size());
            return true;
        }
    }

    /**
     * 모집 미달 무산 처리 (B-01, 명세 8.4.4).
     * 모집 기간이 끝나도 정원이 안 찬 챌린지를 무산(VOID)으로 전이하고,
     * 이미 예치금을 낸 참가자 전원에게 전액 반환한다.
     *
     * @param challengeId 무산 처리할 챌린지 ID
     * @throws ChallengeApiException E_CHL_NOT_FOUND — 챌린지가 없을 때
     */
    @Transactional
    public void voidChallenge(String challengeId) {
        // 1) 모집 중(RECRUITING)이 아닌 챌린지는 대상이 아니다 (이미 시작·무산된 경우 등)
        Challenge challenge = loadChallenge(challengeId);
        if (challenge.getStatus() != ChallengeStatus.RECRUITING) {
            return;
        }
        // 2) 무산 상태로 전이
        challenge.setStatus(ChallengeStatus.VOID);
        challengeRepository.save(challenge);

        // 3) 활성 참가자 전원에게 예치금 반환.
        //    멱등 키 "void:참가ID" 덕분에 이 메서드가 중복 실행돼도 이중 반환되지 않는다
        for (Participation p : participationRepository
                .findByChallengeIdAndStatus(challengeId, ParticipationStatus.ACTIVE)) {
            depositService.credit(p.getParticipantId(), challengeId, p.getDepositAmount(),
                    com.exercisemanagement.challenge.common.DepositEntryType.VOID_RETURN,
                    "void:" + p.getParticipationId());
        }
        logger.info("모집 미달 무산 처리·예치 전액 반환: {}", challengeId);
    }

    /**
     * 과거 기록으로 실력값 계산 (F003).
     * 최근 30일 동안 확인 완료(CONFIRMED)된 같은 운동 종목의 제출(기준 측정 행 포함)을 모아
     * 볼륨(무게×횟수)의 1회 평균을 낸다.
     *
     * @param participantId 참가자 ID
     * @param exercise      챌린지의 운동 종목 (같은 종목 기록만 실력으로 인정)
     * @return 평균 볼륨 (소수 4자리 반올림). 기록이 하나도 없으면 Optional.empty()
     *         — empty가 곧 "기준 측정 필요"(202 경로)로 이어진다
     */
    private Optional<BigDecimal> resolveSkillFromHistory(String participantId, String exercise) {
        // 1) 오늘부터 30일 전까지의 확인 완료 제출을 조회
        LocalDate from = LocalDate.now().minusDays(SKILL_WINDOW_DAYS);
        List<Submission> history = submissionRepository.findRecentConfirmedByExercise(
                participantId, exercise, SubmissionStatus.CONFIRMED, from);     // 참가자의 운동 종목에서 이미 제출 후 인증된 내역들만 조회.
        if (history.isEmpty()) {
            return Optional.empty();
        }
        // 2) 볼륨 합계 ÷ 건수 = 1회 평균 (소수 4자리, 반올림)
        BigDecimal sum = history.stream().map(Submission::getVolume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(sum.divide(BigDecimal.valueOf(history.size()), 4, RoundingMode.HALF_UP));
    }

    /**
     * 지금 신청을 받을 수 있는 상태인지 검증.
     * 챌린지 상태가 RECRUITING이고, 현재 시각이 모집 시작~마감 사이여야 한다.
     *
     * @param challenge 검증할 챌린지
     * @throws ChallengeApiException E_APP_NOT_RECRUITING — 모집 중이 아니거나 모집 기간 밖일 때
     */
    private void validateRecruiting(Challenge challenge) {
        LocalDateTime now = LocalDateTime.now();
        if (challenge.getStatus() != ChallengeStatus.RECRUITING
                || now.isBefore(challenge.getRecruitStart())
                || now.isAfter(challenge.getRecruitEnd())) {
            throw new ChallengeApiException(ErrorCode.E_APP_NOT_RECRUITING);
        }
    }

    /**
     * 개인 목표 빈도가 달성 가능한 값인지 검증 (F002).
     * <p>
     * "주 며칠"(N_PER_WEEK)의 숫자는 인증 횟수가 아니라 '인증하는 날짜 수'다.
     * 일주일은 7일뿐이므로 "주 8일" 같은 목표는 존재할 수 없어 거부한다.
     * "며칠에 한 번"(EVERY_N_DAYS)은 숫자가 클수록 오히려 덜 자주 하는 목표라
     * 불가능한 값이 없고, 그래서 검사하지 않는다.
     *
     * @param challenge 대상 챌린지 (현재 검사에는 사용하지 않음)
     * @param request   신청 내용 (목표 주기 방식·숫자)
     * @throws ChallengeApiException E_APP_FREQ_OVER_CAP — "주 8일" 이상을 목표로 적었을 때
     */
    private void validateGoalFrequency(Challenge challenge, ParticipationApplyRequest request) {

        if (request.getGoalCycleMode() == com.exercisemanagement.challenge.common.CycleMode.N_PER_WEEK
                && request.getGoalCycleInterval() > 7) {
            throw new ChallengeApiException(ErrorCode.E_APP_FREQ_OVER_CAP);
        }
    }

    /**
     * 챌린지 조회 공통 처리.
     *
     * @param challengeId 챌린지 ID
     * @return 조회된 챌린지
     * @throws ChallengeApiException E_CHL_NOT_FOUND — 해당 ID의 챌린지가 없을 때
     */
    private Challenge loadChallenge(String challengeId) {
        return challengeRepository.findById(challengeId)
                .orElseThrow(() -> new ChallengeApiException(ErrorCode.E_CHL_NOT_FOUND));
    }

    /**
     * 업로드된 사진 파일을 바이트 배열로 읽는다.
     *
     * @param photo 업로드 파일
     * @return 파일 내용 바이트 배열
     * @throws ChallengeApiException E_REQ_INVALID — 파일을 읽을 수 없을 때
     */
    private byte[] readBytes(MultipartFile photo) {
        try {
            return photo.getBytes();
        } catch (Exception e) {
            throw new ChallengeApiException(ErrorCode.E_REQ_INVALID, "인증샷 파일을 읽을 수 없습니다.");
        }
    }
}
