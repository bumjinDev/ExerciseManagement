package com.exercisemanagement.challenge.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import com.exercisemanagement.challenge.service.TeamFormationEngine.Member;

/**
 * seedGreedy 계수 실행기 (JMH 아닌 별도 main). 로직 복제 없이 원본 seedGreedy를 직접 호출한다.
 *
 * 현재 활성: 버전 4(레드블랙 트리 = java.util.TreeMap) 비교 횟수 계수.
 *   엔진 버전 4 비교자에 넣은 임시 카운터(countCompareV4)를 조합별 1회 구동해
 *   TreeMap put이 하강하며 실행한 비교 횟수를 표로 출력한다.
 *   pollFirstEntry는 가장 왼쪽 노드까지 포인터만 따라가므로 비교자를 호출하지 않는다.
 *   따라서 이 값은 put 쪽 비교만 담는다. 초기 구성(팀 K개 put)의 비교도 포함된다 — 버전 2 계수와 같은 기준.
 *
 * 아카이브(주석):
 *   버전 2(우선순위 큐) 비교 횟수 계수 main. 보고서 2편 7.5에서 실행 완료(2026-07-16).
 *   버전 1(선형 스캔) 분기 계수 main. 보고서 1편 7.4에서 실행 완료(2026-07-15).
 *   재실행 시 해당 블록을 살리고 활성 main을 주석 처리한 뒤, 엔진 쪽 해당 카운터도 함께 활성화해야 한다.
 *
 * 실행 모델: 계수는 입력이 같으면 결과가 항상 같으므로(결정적) 조합당 1회면 충분하다.
 *   시간 측정(JMH)과는 반드시 분리 실행한다.
 *
 * 입력 데이터: SeedGreedyBench.setup과 동일. 시드 42 고정 난수, 실력 0.0~99.9,
 *   실력 내림차순·동률 시 ID 오름차순 정렬. 조합마다 new Random(42).
 *
 * 실행 조합: 실험 A 그리드 (N 3,000 / 30,000 / 300,000 × K 3 / 30 / 300, 정원 = N ÷ K).
 *
 * 실행 순서(버전 4 계수):
 *   1) TeamFormationEngine 버전 4 비교자 안 countCompareV4++ 증가 줄이 활성인지 확인.
 *   2) 프로젝트 루트에서:
 *        .\gradlew jmhClasses
 *        java -cp "build\classes\java\jmh;build\classes\java\main" com.exercisemanagement.challenge.service.SeedGreedyBranchCount
 *      (IntelliJ에서는 이 파일의 main을 직접 실행해도 된다)
 *   3) 계수가 끝나면 엔진의 증가 줄을 주석 처리한 뒤에 실험 B JMH 시간 측정을 진행.
 */
public class SeedGreedyBranchCount {

    /**
     * [버전 4 · 활성] TreeMap 비교 횟수 계수.
     * 출력 열:
     *   compareV4       : 비교자 compare 호출 총 횟수 (put 하강 비교. pollFirstEntry는 비교자 호출 없음)
     *   perMember(/N)   : 인원 1명당 비교 횟수 (compareV4 ÷ N)
     *   perMember/log2K : 인원당 비교를 log2(K)로 나눈 값. log K에 비례하면 K가 달라도 대략 일정.
     */
    public static void main(String[] args) {
        int[] memberCounts = {3_000, 30_000, 300_000};
        int[] teamCounts = {3, 30, 300};

        TeamFormationEngine engine = new TeamFormationEngine();
        StringBuilder out = new StringBuilder();

        out.append(String.format("%-8s %-5s %-9s %-15s %-16s %-16s%n",
                "N", "K", "cap", "compareV4", "perMember(/N)", "perMember/log2K"));

        for (int n : memberCounts) {
            for (int k : teamCounts) {
                int capacity = n / k;
                List<Member> sorted = generateSorted(n);

                TeamFormationEngine.resetCompareCountV4();
                engine.seedGreedy(sorted, k, capacity);         // 원본 직접 호출(버전 4), 조합당 1회
                long compare = TeamFormationEngine.countCompareV4;

                double perMember = (double) compare / n;        // 인원 1명당 비교 횟수 평균 (put 하강 비교)

                double log2k = Math.log(k) / Math.log(2);
                double perMemberOverLog = perMember / log2k;

                out.append(String.format("%-8d %-5d %-9d %-15d %-16.3f %-16.3f%n",
                        n, k, capacity, compare, perMember, perMemberOverLog));
            }
        }

        System.out.print(out);   // 전 조합 종료 후 1회 출력
    }

    /* ── [버전 2 · 아카이브] 우선순위 큐 비교 횟수 계수 main. 계수 완료(2026-07-16, 보고서 2편 7.5).
          출력 열: compareV2(힙 비교 총 횟수 = offer/poll의 siftUp/siftDown 내부 compare 호출 수),
                   perMember(/N), perMember/log2K. 초기 힙 구성(팀 K개 offer)의 비교도 포함.
          재실행 시 이 블록을 살리고 위 버전 4 main을 주석 처리할 것.
          엔진의 버전 2 comparator 안 countCompareV2++ 증가 줄도 함께 주석 해제해야 한다.

    public static void main(String[] args) {
        int[] memberCounts = {3_000, 30_000, 300_000};
        int[] teamCounts = {3, 30, 300};

        TeamFormationEngine engine = new TeamFormationEngine();
        StringBuilder out = new StringBuilder();

        out.append(String.format("%-8s %-5s %-9s %-15s %-16s %-16s%n",
                "N", "K", "cap", "compareV2", "perMember(/N)", "perMember/log2K"));

        for (int n : memberCounts) {
            for (int k : teamCounts) {
                int capacity = n / k;
                List<Member> sorted = generateSorted(n);

                TeamFormationEngine.resetCompareCountV2();
                engine.seedGreedy(sorted, k, capacity);         // 원본 직접 호출(버전 2), 조합당 1회
                long compare = TeamFormationEngine.countCompareV2;

                double perMember = (double) compare / n;        // 매 회찰 별 한 사람 당 몇번 비교했는지 평균 값_poll 과 offer 총합 값.

                double log2k = Math.log(k) / Math.log(2);
                double perMemberOverLog = perMember / log2k;

                out.append(String.format("%-8d %-5d %-9d %-15d %-16.3f %-16.3f%n",
                        n, k, capacity, compare, perMember, perMemberOverLog));
            }
        }

        System.out.print(out);   // 전 조합 종료 후 1회 출력
    }
    ── */

    /* ── [버전 1 · 아카이브] 선형 스캔 분기 계수 main. 재실행 시 이 블록을 살리고 위 버전 4 main을 주석 처리할 것.
          엔진의 [계수 실험 · 임시 계측] 3곳(countSkip/countCompare)도 함께 주석 해제해야 한다.

    public static void main(String[] args) {
        int[] memberCounts = {3_000, 30_000, 300_000};
        int[] teamCounts = {3, 30, 300};

        TeamFormationEngine engine = new TeamFormationEngine();
        StringBuilder out = new StringBuilder();

        out.append(String.format("%-8s %-5s %-9s %-13s %-13s %-13s %-13s %-9s %-14s%n",
                "N", "K", "cap", "skip", "compare", "skip+comp", "NxK", "selfChk", "skipRatio(%)"));

        for (int n : memberCounts) {
            for (int k : teamCounts) {
                int capacity = n / k;
                List<Member> sorted = generateSorted(n);

                TeamFormationEngine.resetBranchCounters();
                engine.seedGreedy(sorted, k, capacity);              // 원본 직접 호출, 조합당 1회
                long skip = TeamFormationEngine.countSkip;
                long compare = TeamFormationEngine.countCompare;

                // 자기 검산: 두 갈래 실행 횟수의 합은 안쪽 루프 총 회차(N×K)와 정확히 같아야 한다
                long nk = (long) n * k;
                boolean selfOk = skip + compare == nk;
                double skipRatioPct = 100.0 * skip / nk;

                out.append(String.format("%-8d %-5d %-9d %-13d %-13d %-13d %-13d %-9s %-14.6f%n",
                        n, k, capacity, skip, compare, skip + compare, nk,
                        selfOk ? "OK" : "FAIL", skipRatioPct));
            }
        }

        System.out.print(out);   // 전 조합 종료 후 1회 출력
    }
    ── */

    /** SeedGreedyBench.setup과 동일한 입력 생성: 시드 42, 실력 0.0~99.9, 내림차순·ID 오름차순 */
    private static List<Member> generateSorted(int memberCount) {
        Random rnd = new Random(42);
        List<Member> members = new ArrayList<>(memberCount);

        for (int i = 0; i < memberCount; i++) {

            BigDecimal skill = BigDecimal.valueOf(rnd.nextInt(1000)).movePointLeft(1);
            members.add(new Member(String.format("P%05d", i), skill));
        }

        members.sort(Comparator.comparing(Member::skill).reversed()
                .thenComparing(Member::participationId));
        return members;
    }
}
