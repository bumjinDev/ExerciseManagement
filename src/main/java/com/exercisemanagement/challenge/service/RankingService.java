package com.exercisemanagement.challenge.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exercisemanagement.challenge.common.ChallengeStatus;
import com.exercisemanagement.challenge.common.ConfirmationDecision;
import com.exercisemanagement.challenge.common.ParticipationStatus;
import com.exercisemanagement.challenge.common.SubmissionStatus;
import com.exercisemanagement.challenge.dto.response.RankingStatusResponse;
import com.exercisemanagement.challenge.dto.response.RankingStatusResponse.MyGoalStatus;
import com.exercisemanagement.challenge.dto.response.RankingStatusResponse.MyTeamStatus;
import com.exercisemanagement.challenge.dto.response.RankingStatusResponse.TeamContributionEntry;
import com.exercisemanagement.challenge.dto.response.RankingStatusResponse.TeamRankingEntry;
import com.exercisemanagement.challenge.entity.Challenge;
import com.exercisemanagement.challenge.entity.Confirmation;
import com.exercisemanagement.challenge.entity.Participation;
import com.exercisemanagement.challenge.entity.Submission;
import com.exercisemanagement.challenge.entity.Team;
import com.exercisemanagement.challenge.exception.ChallengeApiException;
import com.exercisemanagement.challenge.exception.ErrorCode;
import com.exercisemanagement.challenge.repository.ChallengeRepository;
import com.exercisemanagement.challenge.repository.ConfirmationRepository;
import com.exercisemanagement.challenge.repository.ParticipationRepository;
import com.exercisemanagement.challenge.repository.SubmissionRepository;
import com.exercisemanagement.challenge.repository.TeamRepository;
import com.exercisemanagement.challenge.support.GoalEvaluator;

/**
 * 순위와 개인 목표 현황 산출 (F007, 명세 8.3) + 조회 응답 구성 (6.6).
 *
 * 기준 구현은 확인 완료 제출을 원본으로 DB에서 집계해 계산한다.
 * 자료구조·캐시 선택은 미확정 항목으로 계측 후 재결정한다(명세 8.3.3·10.3).
 * 이탈(WITHDRAWN) 참가자의 볼륨은 집계에서 제외한다(8.3.2 이탈 반영).
 */
@Service
public class RankingService {

    private final ChallengeRepository challengeRepository;
    private final ParticipationRepository participationRepository;
    private final SubmissionRepository submissionRepository;
    private final ConfirmationRepository confirmationRepository;
    private final TeamRepository teamRepository;
    private final GoalEvaluator goalEvaluator;

    public RankingService(ChallengeRepository challengeRepository,
                          ParticipationRepository participationRepository,
                          SubmissionRepository submissionRepository,
                          ConfirmationRepository confirmationRepository,
                          TeamRepository teamRepository,
                          GoalEvaluator goalEvaluator) {
        this.challengeRepository = challengeRepository;
        this.participationRepository = participationRepository;
        this.submissionRepository = submissionRepository;
        this.confirmationRepository = confirmationRepository;
        this.teamRepository = teamRepository;
        this.goalEvaluator = goalEvaluator;
    }

    /** 팀 집계 결과. 순위 조회와 정산 3단계가 같은 순서를 쓴다. */
    public record TeamAggregate(String teamId, BigDecimal volume, LocalDateTime lastConfirmedAt) { }

    /** 순위·현황 조회 (6.6). */
    @Transactional(readOnly = true)
    public RankingStatusResponse getRankings(String challengeId, String requesterId) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new ChallengeApiException(ErrorCode.E_CHL_NOT_FOUND));

        // 시작·종료 상태에서만 조회 허용 (모집·무산은 반환할 순위가 없다)
        if (challenge.getStatus() != ChallengeStatus.STARTED && challenge.getStatus() != ChallengeStatus.ENDED) {
            throw new ChallengeApiException(ErrorCode.E_RNK_NOT_STARTED);
        }

        Participation requester = participationRepository
                .findByChallengeIdAndParticipantId(challengeId, requesterId)
                .filter(p -> p.getStatus() == ParticipationStatus.ACTIVE)
                .orElseThrow(() -> new ChallengeApiException(ErrorCode.E_RNK_NOT_PARTICIPANT));

        List<Participation> activeParts = participationRepository
                .findByChallengeIdAndStatus(challengeId, ParticipationStatus.ACTIVE);
        Map<String, BigDecimal> memberVolumes = computeMemberVolumes(challengeId, activeParts);
        List<TeamAggregate> teamOrder = computeTeamOrder(challengeId, activeParts, memberVolumes);

        // 팀 순위 목록 + 요청자 팀의 순위·경계 판정
        List<TeamRankingEntry> rankings = new ArrayList<>();
        MyTeamStatus myTeam = null;
        for (int i = 0; i < teamOrder.size(); i++) {
            TeamAggregate agg = teamOrder.get(i);
            int rank = i + 1;
            rankings.add(new TeamRankingEntry(rank, agg.teamId(), agg.volume()));
            if (agg.teamId().equals(requester.getTeamId())) {
                myTeam = new MyTeamStatus(agg.teamId(), rank, rank <= challenge.getPrizeTeamCount());
            }
        }

        // 요청자 팀의 팀 내 기여도 순위 (비율 동률은 공동 순위)
        List<TeamContributionEntry> contributions = buildContributions(
                requester.getTeamId(), activeParts, memberVolumes);

        // 개인 목표 달성 현황 — 정산 1단계와 같은 규칙 (GoalEvaluator 공용)
        int targetCount = goalEvaluator.targetCount(challenge, requester);
        int achievedCount = goalEvaluator.achievedCount(requester);
        MyGoalStatus myGoal = new MyGoalStatus(targetCount, achievedCount, achievedCount >= targetCount);

        return RankingStatusResponse.builder()
                .challengeId(challengeId)
                .prizeTeamCount(challenge.getPrizeTeamCount())
                .teamRankings(rankings)
                .myTeam(myTeam)
                .teamContributions(contributions)
                .myGoal(myGoal)
                .build();
    }

    /** 참가자별 확인 완료 볼륨 누적 (이탈자 제외). */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> computeMemberVolumes(String challengeId, List<Participation> activeParts) {
        Map<String, BigDecimal> volumes = new HashMap<>();
        for (Participation p : activeParts) {
            volumes.put(p.getParticipantId(), BigDecimal.ZERO);
        }
        if (activeParts.isEmpty()) {
            return volumes;
        }
        List<Submission> confirmed = submissionRepository
                .findByChallengeIdAndParticipantIdInAndStatusAndBaselineFalse(
                        challengeId, volumes.keySet(), SubmissionStatus.CONFIRMED);
        for (Submission s : confirmed) {
            volumes.merge(s.getParticipantId(), s.getVolume(), BigDecimal::add);
        }
        return volumes;
    }

    /**
     * 팀 순위 정렬 (8.3.2): 팀 볼륨 누적 합 내림차순.
     * 동점이면 그 점수에 먼저 도달한 팀(마지막 반영 확인 시각이 빠른 팀)이 위 — 변경사항 문서 4-7.
     */
    @Transactional(readOnly = true)
    public List<TeamAggregate> computeTeamOrder(String challengeId, List<Participation> activeParts,
                                                Map<String, BigDecimal> memberVolumes) {
        Map<String, String> participantToTeam = new HashMap<>();
        for (Participation p : activeParts) {
            participantToTeam.put(p.getParticipantId(), p.getTeamId());
        }

        Map<String, BigDecimal> teamVolumes = new HashMap<>();
        for (Team team : teamRepository.findByChallengeId(challengeId)) {
            teamVolumes.put(team.getTeamId(), BigDecimal.ZERO);
        }
        memberVolumes.forEach((participantId, volume) -> {
            String teamId = participantToTeam.get(participantId);
            if (teamId != null) {
                teamVolumes.merge(teamId, volume, BigDecimal::add);
            }
        });

        // 팀별 마지막 반영 확인 시각: 확인(CONFIRM) 기록과 확인 완료 제출을 잇는다
        Map<String, LocalDateTime> lastConfirmedAt = new HashMap<>();
        Map<String, String> submissionToTeam = new HashMap<>();
        if (!activeParts.isEmpty()) {
            for (Submission s : submissionRepository.findByChallengeIdAndParticipantIdInAndStatusAndBaselineFalse(
                    challengeId, memberVolumes.keySet(), SubmissionStatus.CONFIRMED)) {
                String teamId = participantToTeam.get(s.getParticipantId());
                if (teamId != null) {
                    submissionToTeam.put(s.getSubmissionId(), teamId);
                }
            }
            for (Confirmation c : confirmationRepository.findByChallengeAndDecision(
                    challengeId, ConfirmationDecision.CONFIRM)) {
                String teamId = submissionToTeam.get(c.getSubmissionId());
                if (teamId != null) {
                    lastConfirmedAt.merge(teamId, c.getConfirmedAt(),
                            (a, b) -> a.isAfter(b) ? a : b);
                }
            }
        }

        return teamVolumes.entrySet().stream()
                .map(e -> new TeamAggregate(e.getKey(), e.getValue(),
                        lastConfirmedAt.get(e.getKey())))
                .sorted(Comparator
                        .comparing(TeamAggregate::volume, Comparator.reverseOrder())
                        .thenComparing(a -> a.lastConfirmedAt() == null ? LocalDateTime.MAX : a.lastConfirmedAt())
                        .thenComparing(TeamAggregate::teamId))
                .toList();
    }

    /** 팀 내 기여도 순위: 개인 볼륨 내림차순, 비율(=볼륨) 동률은 공동 순위. */
    private List<TeamContributionEntry> buildContributions(String teamId, List<Participation> activeParts,
                                                           Map<String, BigDecimal> memberVolumes) {
        List<Participation> teamMembers = activeParts.stream()
                .filter(p -> teamId != null && teamId.equals(p.getTeamId()))
                .sorted(Comparator.comparing((Participation p) ->
                        memberVolumes.getOrDefault(p.getParticipantId(), BigDecimal.ZERO),
                        Comparator.reverseOrder())
                        .thenComparing(Participation::getParticipantId))
                .toList();

        BigDecimal teamVolume = teamMembers.stream()
                .map(p -> memberVolumes.getOrDefault(p.getParticipantId(), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<TeamContributionEntry> entries = new ArrayList<>();
        int rank = 0;
        BigDecimal prevVolume = null;
        for (int i = 0; i < teamMembers.size(); i++) {
            Participation p = teamMembers.get(i);
            BigDecimal volume = memberVolumes.getOrDefault(p.getParticipantId(), BigDecimal.ZERO);
            if (prevVolume == null || volume.compareTo(prevVolume) != 0) {
                rank = i + 1; // 공동 순위: 다음 순위는 인원수만큼 건너뛴다
            }
            prevVolume = volume;
            BigDecimal ratio = teamVolume.signum() == 0
                    ? BigDecimal.ZERO
                    : volume.divide(teamVolume, 4, RoundingMode.HALF_UP);
            entries.add(new TeamContributionEntry(rank, p.getParticipantId(), volume, ratio));
        }
        return entries;
    }
}
