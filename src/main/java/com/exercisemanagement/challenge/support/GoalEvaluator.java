package com.exercisemanagement.challenge.support;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Component;

import com.exercisemanagement.challenge.common.SubmissionStatus;
import com.exercisemanagement.challenge.entity.Challenge;
import com.exercisemanagement.challenge.entity.Participation;
import com.exercisemanagement.challenge.entity.Submission;
import com.exercisemanagement.challenge.repository.SubmissionRepository;

/**
 * 개인 목표 판정 (명세 8.4.1). 순위·현황 조회(6.6 myGoal)와 정산 1단계가
 * 같은 규칙을 쓰도록 이 컴포넌트 하나로 묶는다.
 */
@Component
public class GoalEvaluator {

    private final SubmissionRepository submissionRepository;

    public GoalEvaluator(SubmissionRepository submissionRepository) {
        this.submissionRepository = submissionRepository;
    }

    /**
     * 기간 전체 목표 횟수. 완전 주기만 세는 내림 규칙 (명세 8.4.1).
     * D = 수행 기간이 걸치는 달력 날짜 수. 도출 값이 0이면 1.
     */
    public int targetCount(Challenge challenge, Participation participation) {
        long days = ChronoUnit.DAYS.between(
                challenge.getPerformStart().toLocalDate(),
                challenge.getPerformEnd().toLocalDate()) + 1;

        long target = switch (participation.getGoalCycleMode()) {
            case N_PER_WEEK -> (days / 7) * participation.getGoalCycleInterval();
            case EVERY_N_DAYS -> days / participation.getGoalCycleInterval();
        };
        return (int) Math.max(target, 1);
    }

    /** 강도 하한 이상인 확인 완료 일반 제출의 누적 수. */
    public int achievedCount(Participation participation) {
        BigDecimal floor = participation.intensityFloor();
        List<Submission> confirmed = submissionRepository
                .findByChallengeIdAndParticipantIdAndStatusAndBaselineFalse(
                        participation.getChallengeId(),
                        participation.getParticipantId(),
                        SubmissionStatus.CONFIRMED);
        return (int) confirmed.stream()
                .filter(s -> s.getVolume().compareTo(floor) >= 0)
                .count();
    }

    /** 달성 여부: 누적 수가 기간 전체 목표 횟수 이상 (기간 누적 충족 방식). */
    public boolean isAchieved(Challenge challenge, Participation participation) {
        return achievedCount(participation) >= targetCount(challenge, participation);
    }
}
