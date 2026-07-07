package com.exercisemanagement.challenge.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exercisemanagement.challenge.common.ChallengeStatus;
import com.exercisemanagement.challenge.common.DepositEntryType;
import com.exercisemanagement.challenge.common.ParticipationStatus;
import com.exercisemanagement.challenge.common.PrizeEntryType;
import com.exercisemanagement.challenge.entity.Challenge;
import com.exercisemanagement.challenge.entity.MemberPrize;
import com.exercisemanagement.challenge.entity.Participation;
import com.exercisemanagement.challenge.entity.Settlement;
import com.exercisemanagement.challenge.entity.TeamPrize;
import com.exercisemanagement.challenge.common.SubmissionStatus;
import com.exercisemanagement.challenge.entity.Submission;
import com.exercisemanagement.challenge.repository.ChallengeRepository;
import com.exercisemanagement.challenge.repository.MemberPrizeRepository;
import com.exercisemanagement.challenge.repository.ParticipationRepository;
import com.exercisemanagement.challenge.repository.SettlementRepository;
import com.exercisemanagement.challenge.repository.SubmissionRepository;
import com.exercisemanagement.challenge.repository.TeamPrizeRepository;
import com.exercisemanagement.challenge.service.RankingService.TeamAggregate;
import com.exercisemanagement.challenge.support.GoalEvaluator;
import com.exercisemanagement.challenge.support.IdGenerator;

/**
 * 정산 (F009, 명세 8.4). 확인 윈도우 종료 시점에 스케줄 계층이 단 한 번 실행한다.
 *
 * 네 단계: 1) 개인 판정(환급·차감) → 2) 상금풀 확정(기본 상금풀 + 차감분 총합)
 * → 3) 팀 순위 분배(등차 가중, 내림, 나머지 환수) → 4) 팀 내 기여도 분배(비율, 내림, 나머지 환수).
 *
 * 원자성(변경사항 문서 4-9): 전체를 단일 트랜잭션으로 처리하고,
 * 재원 등식 검산이 어긋나면 예외로 전체 롤백한다.
 * 단일 실행: SETTLEMENT 존재 확인(+ 제약 스키마에서는 challenge_id 유니크가 최종 방어).
 */
@Service
public class SettlementService {

    private static final Logger logger = LoggerFactory.getLogger(SettlementService.class);

    private final ChallengeRepository challengeRepository;
    private final ParticipationRepository participationRepository;
    private final SettlementRepository settlementRepository;
    private final SubmissionRepository submissionRepository;
    private final TeamPrizeRepository teamPrizeRepository;
    private final MemberPrizeRepository memberPrizeRepository;
    private final RankingService rankingService;
    private final GoalEvaluator goalEvaluator;
    private final DepositService depositService;
    private final IdGenerator idGenerator;

    public SettlementService(ChallengeRepository challengeRepository,
                             ParticipationRepository participationRepository,
                             SettlementRepository settlementRepository,
                             SubmissionRepository submissionRepository,
                             TeamPrizeRepository teamPrizeRepository,
                             MemberPrizeRepository memberPrizeRepository,
                             RankingService rankingService,
                             GoalEvaluator goalEvaluator,
                             DepositService depositService,
                             IdGenerator idGenerator) {
        this.challengeRepository = challengeRepository;
        this.participationRepository = participationRepository;
        this.settlementRepository = settlementRepository;
        this.submissionRepository = submissionRepository;
        this.teamPrizeRepository = teamPrizeRepository;
        this.memberPrizeRepository = memberPrizeRepository;
        this.rankingService = rankingService;
        this.goalEvaluator = goalEvaluator;
        this.depositService = depositService;
        this.idGenerator = idGenerator;
    }

    /**
     * 윈도우 종료 처리 (B-04 + B-05): 미확인 제출을 만료로 전이한 뒤 정산을 실행한다.
     * 두 처리를 한 트랜잭션으로 묶는다.
     */
    @Transactional
    public void expireAndSettle(String challengeId) {
        List<Submission> pendings = submissionRepository
                .findByChallengeIdAndStatusAndBaselineFalse(challengeId, SubmissionStatus.PENDING);
        for (Submission s : pendings) {
            s.setStatus(SubmissionStatus.EXPIRED);
            submissionRepository.save(s);
        }
        logger.info("만료 처리: 챌린지 {}, {}건", challengeId, pendings.size());
        settle(challengeId);
    }

    @Transactional
    public void settle(String challengeId) {
        if (settlementRepository.existsByChallengeId(challengeId)) {
            logger.info("정산 이미 실행됨 — 건너뜀: {}", challengeId);
            return;
        }
        Challenge challenge = challengeRepository.findById(challengeId).orElseThrow();

        List<Participation> activeParts = participationRepository
                .findByChallengeIdAndStatus(challengeId, ParticipationStatus.ACTIVE);

        // ----- 1단계. 개인 판정: 달성자 환급, 미달자 차감 (차감분 총합이 2단계 입력) -----
        long forfeitTotal = 0;
        long refundTotal = 0;
        long expectedRefundTotal = 0;
        for (Participation p : activeParts) {
            if (goalEvaluator.isAchieved(challenge, p)) {
                depositService.credit(p.getParticipantId(), challengeId, p.getDepositAmount(),
                        DepositEntryType.REFUND, "refund:" + p.getParticipationId());
                refundTotal += p.getDepositAmount();
                expectedRefundTotal += p.getDepositAmount();
            } else {
                depositService.recordForfeit(p.getParticipantId(), challengeId,
                        "forfeit:" + p.getParticipationId());
                forfeitTotal += p.getDepositAmount();
            }
        }

        // ----- 2단계. 상금풀 확정 -----
        long prizePool = challenge.getBasePrizePool() + forfeitTotal;

        // 정산 실행 기록 (챌린지당 한 건)
        Settlement settlement = new Settlement(idGenerator.settlementId(), challengeId, LocalDateTime.now());
        settlementRepository.save(settlement);

        // ----- 3단계. 팀 순위 분배: 등차 가중, 내림, 나머지는 시스템 환수 -----
        Map<String, BigDecimal> memberVolumes = rankingService.computeMemberVolumes(challengeId, activeParts);
        List<TeamAggregate> teamOrder = rankingService.computeTeamOrder(challengeId, activeParts, memberVolumes);

        int p = challenge.getPrizeTeamCount();
        long weightSum = (long) p * (p + 1) / 2;
        long distributedToTeams = 0;

        for (int i = 0; i < p && i < teamOrder.size(); i++) {
            TeamAggregate team = teamOrder.get(i);
            long weight = p - i; // 순위 i+1의 가중치 = P − i (1-기준: P − 순위 + 1)
            long share = Math.floorDiv(prizePool * weight, weightSum);
            distributedToTeams += share;

            String teamPrizeId = idGenerator.teamPrizeId();
            teamPrizeRepository.save(new TeamPrize(teamPrizeId, settlement.getSettlementId(),
                    team.teamId(), PrizeEntryType.TEAM_SHARE, share));

            // ----- 4단계. 팀 내 분배: 팀원 볼륨 비율, 내림, 나머지는 환수 -----
            distributeWithinTeam(teamPrizeId, team, share, activeParts, memberVolumes);
        }

        long systemReclaim = prizePool - distributedToTeams;
        teamPrizeRepository.save(new TeamPrize(idGenerator.teamPrizeId(), settlement.getSettlementId(),
                null, PrizeEntryType.SYSTEM_RECLAIM, systemReclaim));

        // ----- 검산 (8.4.2): 어긋나면 예외로 전체 롤백 -----
        long teamPrizeSum = teamPrizeRepository.findBySettlementId(settlement.getSettlementId()).stream()
                .mapToLong(TeamPrize::getAmount).sum();
        if (teamPrizeSum != prizePool) {
            throw new IllegalStateException("정산 검산 실패: 팀 분배 합(" + teamPrizeSum
                    + ") ≠ 상금풀(" + prizePool + ")");
        }
        if (refundTotal != expectedRefundTotal) {
            throw new IllegalStateException("정산 검산 실패: 환급 합 불일치");
        }

        challenge.setStatus(ChallengeStatus.ENDED);
        challengeRepository.save(challenge);
        logger.info("정산 완료: {} (상금풀 {}, 차감분 {}, 환급 {})",
                challengeId, prizePool, forfeitTotal, refundTotal);
    }

    private void distributeWithinTeam(String teamPrizeId, TeamAggregate team, long teamShare,
                                      List<Participation> activeParts, Map<String, BigDecimal> memberVolumes) {
        List<Participation> members = activeParts.stream()
                .filter(m -> team.teamId().equals(m.getTeamId()))
                .sorted((a, b) -> a.getParticipantId().compareTo(b.getParticipantId()))
                .toList();

        BigDecimal teamVolume = team.volume();
        long distributed = 0;

        for (Participation member : members) {
            long memberShare = 0;
            if (teamVolume.signum() > 0) {
                BigDecimal volume = memberVolumes.getOrDefault(member.getParticipantId(), BigDecimal.ZERO);
                memberShare = BigDecimal.valueOf(teamShare)
                        .multiply(volume)
                        .divide(teamVolume, 0, RoundingMode.FLOOR)
                        .longValueExact();
            }
            distributed += memberShare;
            memberPrizeRepository.save(new MemberPrize(idGenerator.memberPrizeId(), teamPrizeId,
                    member.getParticipantId(), PrizeEntryType.MEMBER_SHARE, memberShare));
        }

        long remainder = teamShare - distributed;
        memberPrizeRepository.save(new MemberPrize(idGenerator.memberPrizeId(), teamPrizeId,
                null, PrizeEntryType.REMAINDER_RECLAIM, remainder));

        // 팀 몫 검산: 팀원 분배 합 + 나머지 환수 = 팀 몫 (7.1.10)
        long memberSum = memberPrizeRepository.findByTeamPrizeId(teamPrizeId).stream()
                .mapToLong(MemberPrize::getAmount).sum();
        if (memberSum != teamShare) {
            throw new IllegalStateException("정산 검산 실패: 팀 " + team.teamId()
                    + " 내 분배 합(" + memberSum + ") ≠ 팀 몫(" + teamShare + ")");
        }
    }
}
