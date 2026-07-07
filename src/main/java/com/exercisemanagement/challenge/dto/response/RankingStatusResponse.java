package com.exercisemanagement.challenge.dto.response;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 순위·현황 조회 응답 (명세서 7.2.3). 화면 한 번의 갱신에 필요한 네 블록을 한 응답에 담는다.
 * 명세의 네 원소 타입(TeamRankingEntry 등)은 중첩 클래스로 정의한다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RankingStatusResponse {

    private String challengeId;
    private int prizeTeamCount;                            // 상금 경계 표시 기준
    private List<TeamRankingEntry> teamRankings;           // 전체 팀 순위 목록
    private MyTeamStatus myTeam;                           // 요청자 팀의 순위와 경계 판정
    private List<TeamContributionEntry> teamContributions; // 요청자 팀의 기여도 순위
    private MyGoalStatus myGoal;                           // 요청자의 개인 목표 현황

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TeamRankingEntry {
        private int rank;                     // 동점이면 먼저 도달한 팀이 위 (8.3.2)
        private String teamId;
        private BigDecimal teamVolume;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MyTeamStatus {
        private String teamId;
        private int rank;
        private boolean inPrizeBoundary;      // 순위가 상금 받을 팀 수 이내면 true
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TeamContributionEntry {
        private int rank;                     // 비율이 같으면 공동 순위
        private String participantId;
        private BigDecimal memberVolume;
        private BigDecimal contributionRatio; // 0과 1 사이
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MyGoalStatus {
        private int targetCount;              // 기간 전체 목표 횟수 (8.4.1 도출 규칙)
        private int achievedCount;            // 강도 하한 이상 확인 완료 제출의 누적 수
        private boolean achieved;
    }
}
