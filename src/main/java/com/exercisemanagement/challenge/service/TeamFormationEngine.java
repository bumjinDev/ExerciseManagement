package com.exercisemanagement.challenge.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 팀 편성 연산 (명세 8.1).
 *
 * 합 균형은 제약(허용폭 = 전체 팀 합의 평균 × r%), 분포 균형은 그 제약 안에서
 * 최소화하는 목표다. 절차(8.1.4): 준비 → 시작 배정 2개(교대 방향, 내림차순 그리디)
 * → 시작점별 교환 반복(합 필터 + 최선 수락) → 채택.
 *
 * 기준 구현 원칙(8.1.7): 후보 전체를 정의 그대로 평가한다.
 * 병렬 분할·증분 재계산 같은 계산량 절감은 계측 후 결정 사항이라 넣지 않는다.
 */
@Component
public class TeamFormationEngine {

    /** 편성 입력: 참가 식별자와 편성 실력 */
    public record Member(String participationId, BigDecimal skill) { }

    /**
     * @param members       신청자 전체 (인원 = teamCount × teamCapacity 보장됨)
     * @param teamCount     팀 수 (F001 등록값)
     * @param teamCapacity  팀 정원 (F001 등록값)
     * @param sumCapPercent 합 상한 r(%) — 시스템 설정값(잠정)
     * @return 팀 인덱스(0..teamCount-1)별 배정 목록
     */
    public List<List<Member>> form(List<Member> members, int teamCount, int teamCapacity, double sumCapPercent) {

        // 1단계. 준비: 전체 팀 합의 평균과 허용폭을 한 번만 계산해 고정한다
        double totalSkill = members.stream().mapToDouble(m -> m.skill().doubleValue()).sum();
        double avgTeamSum = totalSkill / teamCount;
        double tolerance = avgTeamSum * (sumCapPercent / 100.0);

        // 편성 실력 내림차순, 동률이면 참가 식별자 오름차순 (결정성 보장)
        List<Member> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing((Member m) -> m.skill()).reversed()
                .thenComparing(Member::participationId));

        // 2단계. 시작 배정 두 개
        List<List<Member>> serpentine = seedSerpentine(sorted, teamCount);
        List<List<Member>> greedy = seedGreedy(sorted, teamCount, teamCapacity);

        // 3단계. 교환 반복 (시작 배정마다 독립 실행)
        improve(serpentine, tolerance);
        improve(greedy, tolerance);

        // 4단계. 채택
        return adopt(serpentine, greedy, tolerance);
    }

    /** 방식 1. 교대 방향 배정: 라운드마다 배정 방향을 뒤집으며 한 명씩 배정 */
    private List<List<Member>> seedSerpentine(List<Member> sorted, int teamCount) {
        List<List<Member>> teams = emptyTeams(teamCount);
        for (int i = 0; i < sorted.size(); i++) {
            int round = i / teamCount;
            int pos = i % teamCount;
            int teamIndex = (round % 2 == 0) ? pos : (teamCount - 1 - pos);
            teams.get(teamIndex).add(sorted.get(i));
        }
        return teams;
    }

    /** 방식 2. 내림차순 그리디: 큰 값부터, 자리가 남은 팀 중 현재 합이 가장 작은 팀에 배정 */
    private List<List<Member>> seedGreedy(List<Member> sorted, int teamCount, int teamCapacity) {
        List<List<Member>> teams = emptyTeams(teamCount);
        double[] sums = new double[teamCount];
        for (Member m : sorted) {
            int best = -1;
            for (int t = 0; t < teamCount; t++) {
                if (teams.get(t).size() >= teamCapacity) continue;
                if (best == -1 || sums[t] < sums[best]) best = t;
            }
            teams.get(best).add(m);
            sums[best] += m.skill().doubleValue();
        }
        return teams;
    }

    /**
     * 3단계. 교환 반복: 서로 다른 팀의 두 사람 모든 짝을 평가해,
     * 합 필터를 통과하면서 분포 편차를 가장 많이 줄이는 짝 하나를 교환한다.
     * 줄이는 짝이 없으면 종료. 반복 횟수 상한 없음 (8.1.4).
     */
    private void improve(List<List<Member>> teams, double tolerance) {
        while (true) {
            double currentSumDev = sumDeviation(teams);
            double currentDistDev = distributionDeviation(teams);
            boolean withinCap = currentSumDev <= tolerance;

            int bestTeamA = -1, bestIdxA = -1, bestTeamB = -1, bestIdxB = -1;
            double bestNewDistDev = currentDistDev;
            String bestKeyFirst = null, bestKeySecond = null;

            for (int a = 0; a < teams.size(); a++) {
                for (int b = a + 1; b < teams.size(); b++) {
                    for (int i = 0; i < teams.get(a).size(); i++) {
                        for (int j = 0; j < teams.get(b).size(); j++) {
                            swap(teams, a, i, b, j);

                            double newSumDev = sumDeviation(teams);
                            // 합 필터: 허용폭 이내면 넘는 짝을, 허용폭 밖(이상치 풀)이면 더 나빠지는 짝을 버린다
                            boolean pass = withinCap ? newSumDev <= tolerance : newSumDev <= currentSumDev;

                            if (pass) {
                                double newDistDev = distributionDeviation(teams);
                                if (newDistDev < bestNewDistDev - 1e-12) {
                                    bestNewDistDev = newDistDev;
                                    bestTeamA = a; bestIdxA = i; bestTeamB = b; bestIdxB = j;
                                    // 교환 후 상태 기준의 짝 식별자(정렬된 쌍)를 동률 판정용으로 기록
                                    String idA = teams.get(a).get(i).participationId();
                                    String idB = teams.get(b).get(j).participationId();
                                    bestKeyFirst = idA.compareTo(idB) <= 0 ? idA : idB;
                                    bestKeySecond = idA.compareTo(idB) <= 0 ? idB : idA;
                                } else if (Math.abs(newDistDev - bestNewDistDev) <= 1e-12 && bestTeamA != -1) {
                                    // 감소량 동률: 참가자 식별자가 앞선 짝을 잡는다 (8.1.4)
                                    String idA = teams.get(a).get(i).participationId();
                                    String idB = teams.get(b).get(j).participationId();
                                    String first = idA.compareTo(idB) <= 0 ? idA : idB;
                                    String second = idA.compareTo(idB) <= 0 ? idB : idA;
                                    if (first.compareTo(bestKeyFirst) < 0
                                            || (first.equals(bestKeyFirst) && second.compareTo(bestKeySecond) < 0)) {
                                        bestTeamA = a; bestIdxA = i; bestTeamB = b; bestIdxB = j;
                                        bestKeyFirst = first; bestKeySecond = second;
                                    }
                                }
                            }
                            swap(teams, a, i, b, j); // 원상 복구
                        }
                    }
                }
            }

            if (bestTeamA == -1) {
                return; // 분포 편차를 줄이는 짝이 없다 — 종료
            }
            swap(teams, bestTeamA, bestIdxA, bestTeamB, bestIdxB);
        }
    }

    /** 4단계. 채택 규칙 (8.1.4): 제약이 목표보다 앞선다 */
    private List<List<Member>> adopt(List<List<Member>> r1, List<List<Member>> r2, double tolerance) {
        double sum1 = sumDeviation(r1), dist1 = distributionDeviation(r1);
        double sum2 = sumDeviation(r2), dist2 = distributionDeviation(r2);
        boolean ok1 = sum1 <= tolerance, ok2 = sum2 <= tolerance;

        if (ok1 && !ok2) return r1;
        if (ok2 && !ok1) return r2;
        if (ok1) { // 둘 다 지킴: 분포 낮은 쪽, 같으면 합 낮은 쪽
            if (dist1 != dist2) return dist1 < dist2 ? r1 : r2;
            return sum1 <= sum2 ? r1 : r2;
        }
        // 둘 다 어김: 합 낮은 쪽, 같으면 분포 낮은 쪽
        if (sum1 != sum2) return sum1 < sum2 ? r1 : r2;
        return dist1 <= dist2 ? r1 : r2;
    }

    // ---- 판정 값 (8.1.3) ----

    /** 합 편차 = 최대 팀 합 − 최소 팀 합 */
    private double sumDeviation(List<List<Member>> teams) {
        double max = Double.NEGATIVE_INFINITY, min = Double.POSITIVE_INFINITY;
        for (List<Member> team : teams) {
            double sum = team.stream().mapToDouble(m -> m.skill().doubleValue()).sum();
            max = Math.max(max, sum);
            min = Math.min(min, sum);
        }
        return max - min;
    }

    /** 분포 편차 = 최대 팀 분산 − 최소 팀 분산 (분산: 팀 평균에서 벗어난 거리의 제곱 평균) */
    private double distributionDeviation(List<List<Member>> teams) {
        double max = Double.NEGATIVE_INFINITY, min = Double.POSITIVE_INFINITY;
        for (List<Member> team : teams) {
            double mean = team.stream().mapToDouble(m -> m.skill().doubleValue()).average().orElse(0);
            double variance = team.stream()
                    .mapToDouble(m -> {
                        double d = m.skill().doubleValue() - mean;
                        return d * d;
                    }).average().orElse(0);
            max = Math.max(max, variance);
            min = Math.min(min, variance);
        }
        return max - min;
    }

    private void swap(List<List<Member>> teams, int teamA, int idxA, int teamB, int idxB) {
        Member tmp = teams.get(teamA).get(idxA);
        teams.get(teamA).set(idxA, teams.get(teamB).get(idxB));
        teams.get(teamB).set(idxB, tmp);
    }

    private List<List<Member>> emptyTeams(int teamCount) {
        List<List<Member>> teams = new ArrayList<>(teamCount);
        for (int i = 0; i < teamCount; i++) {
            teams.add(new ArrayList<>());
        }
        return teams;
    }
}
