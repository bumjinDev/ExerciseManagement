package com.exercisemanagement.challenge.service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

import org.springframework.stereotype.Component;

/**
 * 팀 편성 연산 (명세 8.1).
 *
 * <h2>한 줄 요약</h2>
 * 참가자를 팀에 나눠 담되, (1) 팀별 실력 "합"이 서로 비슷하도록(=제약, 반드시 지킴),
 * 그 제약을 지키는 선에서 (2) 팀별 실력 "분포(분산)"가 고르도록(=목표, 최소화) 배정한다.
 *
 */
@Component
public class TeamFormationEngine {

    /** 편성 입력. 참가 식별자와 편성 실력을 담는다. */
    public record Member(String participationId, BigDecimal skill) { }

    /**
     * 참가자를 팀으로 편성한다.
     *
     * <p>시작 배정 두 개를 각각 개선한 뒤 채택 규칙으로 하나를 고른다.
     *
     * @param members       신청자 전체. 인원은 teamCount × teamCapacity와 같다
     * @param teamCount     팀 수
     * @param teamCapacity  팀 정원
     * @param sumCapPercent 합 상한 비율(%). 허용폭은 팀 합 평균에 이 비율을 곱해 낸다
     * @return 팀 인덱스 0부터 teamCount - 1까지의 배정 목록
     */
    public List<List<Member>> form(List<Member> members, int teamCount, int teamCapacity, double sumCapPercent) {

        // 1단계. 준비.
        // 허용폭은 입력이 정해지면 변하지 않으므로 여기서 한 번만 계산한다.
        // 팀 합 편차가 허용폭 이하이면 제약을 지킨 것으로 본다.
        double totalSkill = members.stream().mapToDouble(m -> m.skill().doubleValue()).sum();   // 참가자 전체의 실력 합
        double avgTeamSum = totalSkill / teamCount;                                             // 팀 하나가 가져야 할 이상적인 합
        double tolerance = avgTeamSum * (sumCapPercent / 100.0);                                // 허용폭. 이후 계산에 쓰는 값이다

        // 정렬 기준은 실력 내림차순이고 동률이면 참가 식별자 오름차순이다.
        // 두 번째 기준이 결정성의 출발점이다. 시작 배정 둘이 이 순서를 그대로 쓴다.
        List<Member> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing((Member m) -> m.skill())
                .reversed()
                .thenComparing(Member::participationId));

        // 2단계. 시작 배정 두 개를 만든다.
        // 교환 반복은 시작점에 따라 다른 국소 최적에 수렴한다.
        // 성질이 다른 두 배정을 각각 개선한 뒤 더 나은 쪽을 고른다.
        List<List<Member>> serpentine = seedSerpentine(sorted, teamCount);          // 교대 방향 배정
        List<List<Member>> greedy = seedGreedy(sorted, teamCount, teamCapacity);    // 내림차순 그리디 배정

        // 3단계. 시작 배정마다 교환 반복을 돌린다. 두 호출은 서로 의존이 없다.
        // 실행 시간의 대부분이 이 단계에서 든다.
        improve(serpentine, tolerance);
        improve(greedy, tolerance);

        // 4단계. 두 결과 중 채택 규칙에 맞는 하나를 반환한다.
        return adopt(serpentine, greedy, tolerance);
    }

    /**
     * 교대 방향으로 배정한다.
     *
     * <p>정렬된 참가자를 팀 수만큼 잘라 한 바퀴로 삼고, 바퀴마다 나눠 주는 방향을 뒤집는다.
     * 방향을 뒤집으면 강한 참가자와 약한 참가자가 팀마다 섞여 초기 합이 균등해진다.
     * 팀이 셋이면 배정 순서가 팀 0, 1, 2 다음에 팀 2, 1, 0이 된다.
     *
     * @param sorted    실력 내림차순으로 정렬된 참가자 목록
     * @param teamCount 팀 수
     * @return 팀 인덱스별 배정 목록
     */
    List<List<Member>> seedSerpentine(List<Member> sorted, int teamCount) {

        // 팀 수만큼 빈 팀 리스트를 만든다.
        List<List<Member>> teams = emptyTeams(teamCount);

        // 정렬된 명단을 teamCount명씩 한 바퀴로 자르고, 홀수 바퀴는 순서를 뒤집은 뒤
        // 팀 0부터 차례로 한 명씩 나눠 준다. 인원이 팀 수 × 정원이라 나머지 없이 나뉜다.
        int cycleCount = sorted.size() / teamCount;   // 바퀴 수. 팀 정원과 같다

        for (int cycle = 0; cycle < cycleCount; cycle++) {

            // 이번 바퀴가 맡을 teamCount명을 잘라 온다. 정렬이 내림차순이라 강한 쪽부터다.
            int start = cycle * teamCount;
            List<Member> group = new ArrayList<>(sorted.subList(start, start + teamCount));

            // 홀수 바퀴는 약한 쪽부터 오도록 뒤집는다. 짝수 바퀴는 그대로 둔다.
            if (cycle % 2 == 1) {
                Collections.reverse(group);
            }

            // 팀마다 한 명씩 나눠 준다. group에 이번 바퀴의 방향이 이미 반영되어 있으므로
            // 같은 인덱스끼리 그대로 넣으면 된다.
            for (int teamIndex = 0; teamIndex < teamCount; teamIndex++) {
                teams.get(teamIndex).add(group.get(teamIndex));
            }
        }

        return teams;

        /* [보존] 순번을 나눗셈으로 펼쳐 좌표를 계산하던 원본 구현이다.
           위 구현과 결과가 같다. 팀 수 2부터 8까지와 정원 1부터 7까지의 전 조합에서 배정 일치를 확인했다.
           리스트 복사 없이 한 번에 훑어 조금 더 효율적이지만 읽기는 덜 직관적이다.

           for (int i = 0; i < sorted.size(); i++) {
               int rowIndex  = i / teamCount;   // 몫    = 몇 번째 바퀴(줄)
               int slotInRow = i % teamCount;   // 나머지 = 그 바퀴의 몇 번째 자리 (0 ~ teamCount-1, 바퀴마다 리셋)
               // 짝수 바퀴는 왼→오 그대로, 홀수 바퀴는 오→왼으로 뒤집기
               int teamIndex = (rowIndex % 2 == 0) ? slotInRow : (teamCount - 1 - slotInRow);
               teams.get(teamIndex).add(sorted.get(i));
           }
           return teams;
        */
    }

    /**
     * 내림차순 그리디로 배정한다.
     *
     * <p>실력이 큰 참가자부터 차례로, 자리가 남았고 현재 합이 가장 작은 팀에 넣는다.
     * 큰 값을 가장 뒤처진 팀에 먼저 주는 방식이라 합 균형에 강하다.
     * 팀별 현재 합은 배열에 들고 다니며 갱신해 매번 다시 더하지 않는다.
     *
     * @param sorted       실력 내림차순으로 정렬된 참가자 목록
     * @param teamCount    팀 수
     * @param teamCapacity 팀 정원
     * @return 팀 인덱스별 배정 목록
     */

    /* [버전 1] 선형 스캔.
       같은 시그니처의 구현이 이 파일에 셋 있다. 버전 1은 선형 스캔, 버전 2는 우선순위 큐,
       버전 4는 java.util.TreeMap을 쓰는 레드블랙 트리다.
       같은 이름의 메서드는 하나만 선언할 수 있으므로 활성 버전 하나만 두고 나머지는 주석으로 보존한다.
       세 버전 모두 자리가 남은 팀 중 합이 최소이고 동점이면 인덱스가 낮은 팀을 고르는 규칙이라 배정 결과가 같다.
       버전 번호 3과 5와 6이 비어 있는 것은 AVL 트리와 정렬된 배열과 정렬된 연결 리스트를
       측정하지 않기로 정하고 코드에서 지웠기 때문이다. 계열 분류와 제외 근거는 docs/병목개선 문서에 있다.
       접근 제어자가 패키지-프라이빗인 것은 같은 패키지의 JMH 벤치마크가 직접 부르기 때문이다. */

    /* [계수 실험 · 비활성] 보고서 1편 7.4절의 분기 계수용이다.
       안쪽 반복문 두 갈래(자리가 찬 팀을 건너뛴 회차와 최소 합 비교까지 간 회차)의 실행 횟수를 센다.
       두 갈래의 합은 인원 수 곱하기 팀 수와 같다.
       계수 실행 진입점은 src/jmh/java의 SeedGreedyBranchCount다. JMH가 아니라 별도 main이다.
       2026-07-15에 아홉 조합의 자기 검산을 통과해 계수를 마치고 비활성으로 두었다. 시간 측정과 계수는 따로 돌린다.
       다시 세려면 아래 코드와 seedGreedy 안의 증가 두 줄과 SeedGreedyBranchCount의 main 본문을 주석 해제한다. */
//    static long countSkip;      // 꽉 찬 팀이라 continue로 건너뛴 회차 수
//    static long countCompare;   // 최소 합 비교까지 실행된 회차 수
//
//    /** [계수 실험 · 임시] 카운터 초기화 — 계수 실행이 조합마다 시작 전에 호출한다. */
//    static void resetBranchCounters() {
//        countSkip = 0;
//        countCompare = 0;
//    }

    /* [계수 실험 · 버전 2 · 증가 비활성] 우선순위 큐의 비교 횟수를 센다.
       비교자의 compare 호출 횟수이고, 이것이 곧 힙이 offer와 poll에서 수행한 비교 횟수다.
       계수 실행 진입점은 src/jmh/java의 SeedGreedyBranchCount다.
       시간 측정이 흔들리지 않도록 비교자 안의 증가 한 줄만 주석 처리했다.
       아래 필드와 초기화 메서드는 SeedGreedyBranchCount가 참조해 컴파일에 필요하므로 남겨 둔다.
       핫 패스가 아니라 시간에는 영향이 없다.
       다시 세려면 비교자 안의 countCompareV2++ 한 줄만 주석 해제한다. */
    static long countCompareV2;   // 비교자 compare 호출 횟수. 힙 비교 횟수와 같다

    /** [계수 실험] 카운터를 0으로 되돌린다. 계수 실행이 조합마다 시작 전에 부른다. */
    static void resetCompareCountV2() {
        countCompareV2 = 0;
    }

    /* [계수 실험 · 버전 4 · 증가 비활성] TreeMap의 비교 횟수를 센다.
       비교자 bySumThenIndex의 compare 호출 횟수이고, 이것이 곧 put이 트리를 내려가며 수행한 비교 횟수다.
       pollFirstEntry는 가장 왼쪽 노드까지 포인터만 따라가므로 비교자를 부르지 않는다.
       팀 K개를 처음 넣을 때의 비교도 포함한다. 버전 2 계수와 같은 기준이다.
       계수 실행 진입점은 src/jmh/java의 SeedGreedyBranchCount다.
       2026-07-21에 계수를 마쳤고, 시간 측정이 흔들리지 않도록 비교자 안의 증가 한 줄만 주석 처리했다.
       아래 필드와 초기화 메서드는 SeedGreedyBranchCount가 참조해 컴파일에 필요하므로 남겨 둔다.
       다시 세려면 비교자 안의 countCompareV4++ 한 줄만 주석 해제한다. */
    static long countCompareV4;   // 비교자 compare 호출 횟수. put이 내려가며 수행한 비교 횟수와 같다

    /** [계수 실험] 카운터를 0으로 되돌린다. 계수 실행이 조합마다 시작 전에 부른다. */
    static void resetCompareCountV4() {
        countCompareV4 = 0;
    }

//    List<List<Member>> seedGreedy(List<Member> sorted, int teamCount, int teamCapacity) {
//        /* teams : 팀을 순차적으로 빈 데이터들만 가진 연결리스트로 현
//           sums : teams 내 각 합들을 별도로 저장해서 합을 가지고 teams 를 선택하기 위한 배열이며, 별도의 인덱스 표 등을 구성하지는 않지만 teams 와 sums 를 동일한 인덱스로 참조하는 형식으로 진행. */
//        List<List<Member>> teams = emptyTeams(teamCount);   // 방식 1과 동일한 로직
//        double[] sums = new double[teamCount];         // 팀별(emptyTeams) 현재 합 (증분 유지)
//
//        for (Member m : sorted) {
//            // 이번 사람을 넣을 팀 고르기: 정원이 남은 팀들 중 "현재 합(sums)이 가장 작은" 팀을 찾는다.
//            int best = -1;   // 아직 후보 없음 (-1 = 미정)
//
//            /* Member m 이 모든 팀 값들(sums)를 순회하면서 현재 m 이 어디에 넣어질지 결정한다. 모든 m은 최악의 경우 이 모든 팀들 개수 만큼 순회할 수 있다. */
//            for (int t = 0; t < teamCount; t++) {
//
//                if (teams.get(t).size() >= teamCapacity) continue;   // 이미 꽉 찬 팀은 후보에서 제외
//                // [계수 실험 · 임시 · 비활성] 재계수 시 위 한 줄을 주석 처리하고 아래 블록을 해제:
//                // if (teams.get(t).size() >= teamCapacity) {
//                //     countSkip++;                                  // skip 갈래 계수
//                //     continue;
//                // }
//
//                // 첫 후보(best == -1)는 무조건 채택하고, 그 뒤로는 이 팀의 현재 합 sums[t]가
//                // 지금까지의 최소 sums[best]보다 "더 작을 때만" best를 교체한다.
//                // 비교가 strict(<)라 합이 같으면 교체하지 않으므로, 동점이면 인덱스가 낮은(먼저 만난) 팀이 유지된다.
//                // → 첫 사람은 모든 sums가 0으로 동점이라 team0(인덱스 0)이 선택된다. 마지막 팀이 아니며, 이 규칙이 결정성도 보장한다.
//                // countCompare++;   // [계수 실험 · 임시 · 비활성] 비교 갈래 계수 — 재계수 시 해제
//                if (best == -1 || sums[t] < sums[best])
//                    best = t;
//            }
//            teams.get(best).add(m);                  // 합이 가장 작은 팀에 이번 사람을 배정
//            sums[best] += m.skill().doubleValue();   // 그 팀의 합을 방금 넣은 만큼 즉시 갱신 → 다음 사람 배정의 기준이 됨
//        }
//        return teams;
//    }

    /* [버전 2 · 비활성] 우선순위 큐(이진 힙)로 구현한 판이다. 보고서 2편의 측정 코드와 같다.

       구성 요소가 넷이다.
         teams  : 배정 결과. teams.get(t)가 팀 t의 인원 목록이다.
         sums   : 팀별 현재 합의 유일한 원본. 비교자가 비교할 때마다 읽는다.
         비교자 : 팀 인덱스 둘을 받아 그 시점의 sums 값으로 우선순위를 정하는 규칙이다.
         pq     : 자리가 남은 팀 인덱스의 대기열이다. 합과 인원은 담지 않는다.

       한 사람을 배정하는 흐름은 이렇다.
       pq.poll()로 합이 가장 작은 팀을 꺼내고, 그 팀에 사람을 넣고, sums를 갱신한다.
       자리가 남았으면 pq.offer()로 다시 넣어 갱신된 합 기준으로 재배치하고, 찼으면 넣지 않는다.

       poll과 offer가 O(log K)다. 힙은 최솟값 하나만 보장하는 부분 정렬이고,
       그 점이 전체 정렬 순서를 늘 유지하는 아래 버전 4의 레드블랙 트리와 다르다. */
//    List<List<Member>> seedGreedy(List<Member> sorted, int teamCount, int teamCapacity) {
//
//        List<List<Member>> teams = emptyTeams(teamCount);       // 배정 결과. teams.get(t)가 팀 t의 인원 목록이다
//        double[] sums = new double[teamCount];                  // 팀별 현재 합. 비교자가 읽는 키의 유일한 원본이다
//
//        // 팀 인덱스 둘을 받아 호출 시점의 sums 값을 조회해 비교한다.
//        // 여기서는 규칙을 등록만 하고, 실제 비교는 offer와 poll 안에서 힙이 자리를 잡을 때 실행된다.
//        // 우선순위는 합 오름차순이고 동점이면 인덱스 오름차순이다.
//        // 이 동점 규칙이 선형 스캔의 "합이 같으면 낮은 인덱스" 규칙과 배정 결과를 맞춘다.
//        //
//        // 주의: 비교 키가 밖에 있는 가변 배열 sums다. 힙은 큐 안에 있는 원소의 키가 바뀌는 것을 알아채지 못한다.
//        // 그래서 꺼낸 뒤에 합을 고치고 다시 넣는 순서를 지켜야 정렬이 유지된다.
//        Comparator<Integer> byTeamSumThenIndex = new Comparator<Integer>() {
//            @Override
//            public int compare(Integer a, Integer b) {
////                countCompareV2++;   // [계수 실험 · 비활성] 다시 셀 때 이 줄만 주석 해제한다
//                int bySum = Double.compare(sums[a], sums[b]);
//                if (bySum != 0) {
//                    return bySum;
//                }
//                return Integer.compare(a, b);
//            }
//        };
//
//        // 큐에 담는 것은 팀 인덱스뿐이다. 합과 인원은 담지 않는다.
//        // 큐에 있다는 것은 자리가 남은 배정 후보라는 뜻이고, 우선순위는 비교자가 sums를 읽어 정한다.
//        PriorityQueue<Integer> pq = new PriorityQueue<>(byTeamSumThenIndex);
//
//        // 전 팀을 후보로 등록한다. 시작은 모두 합이 0으로 동점이라 인덱스 규칙에 따라 팀 0이 최솟값이 된다.
//        for (int t = 0; t < teamCount; t++) {
//            pq.offer(t);
//        }
//
//        for (Member m : sorted) {
//            // 합이 가장 작은 팀의 인덱스를 꺼낸다. 동점이면 인덱스가 낮은 쪽이다.
//            // 선형 스캔에서 O(K)였던 안쪽 반복문이 이 한 줄 O(log K)로 바뀐 자리다.
//            int best = pq.poll();
//
//            teams.get(best).add(m);                     // 배정. best는 teams와 sums가 함께 쓰는 인덱스다
//            sums[best] += m.skill().doubleValue();      // 합 갱신. 다음 비교부터 이 값을 읽는다
//
//            // 다시 등록한다. 힙은 큐 안 원소의 키가 바뀐 것을 알아채지 못하므로,
//            // 꺼낸 상태에서 합을 고친 뒤 되넣어 새 합 기준으로 재배치시킨다.
//            // 자리가 찬 팀은 되넣지 않는다. 선형 스캔에서 꽉 찬 팀을 건너뛰던 것과 같은 효과다.
//            if (teams.get(best).size() < teamCapacity) {
//                pq.offer(best);
//            }
//        }
//        return teams;
//    }

    /* [버전 4 · 활성] java.util.TreeMap을 그대로 쓰는 레드블랙 트리 판이다.
       실험 A와 B와 계수 측정을 2026-07-21에 마쳤다.

       직접 구현하지 않고 JDK의 레드블랙 트리 구현체인 TreeMap을 쓴다.
       키는 넣는 시점의 합과 팀 인덱스를 담은 TeamKey이고 값은 팀 인덱스다.
       합이 바뀌어도 트리 안의 키는 고치지 않는다. 꺼낸 뒤 새 합으로 키를 새로 만들어 다시 넣는다.
       키가 불변이라 버전 2에서 지켜야 했던 "큐 밖에서만 합을 바꾼다"는 주의가 여기서는 필요 없다.
       우선순위 규칙은 다른 버전과 같아 배정 결과도 같고 결정성도 유지된다.
       pollFirstEntry와 put 모두 O(log K)다. */

    /** [버전 4] TreeMap의 키. 넣는 시점의 팀 합과 팀 인덱스를 담는다. 불변이라 트리 안에서 값이 바뀌지 않는다. */
    private record TeamKey(double sum, int team) { }

    List<List<Member>> seedGreedy(List<Member> sorted, int teamCount, int teamCapacity) {

        List<List<Member>> teams = emptyTeams(teamCount);
        double[] sums = new double[teamCount];          // 팀별 현재 합 (증분 유지) — 재삽입 키를 만들 때 읽는다

        // [규칙 정의] 1순위 = 합 오름차순, 2순위(동점) = 인덱스 오름차순. 버전 2 비교자와 같은 규칙.
        // 비교에 쓰는 값이 키 안에 저장된 값뿐이라(외부 배열 조회 없음) 트리 안 정렬은 항상 유효하다.
        Comparator<TeamKey> bySumThenIndex = new Comparator<TeamKey>() {
            @Override
            public int compare(TeamKey a, TeamKey b) {
//                countCompareV4++;   // [계수 실험 임시 비활성] 재계수 시 이 줄만 주석 해제
                int bySum = Double.compare(a.sum(), b.sum());
                if (bySum != 0) {
                    return bySum;
                }
                return Integer.compare(a.team(), b.team());
            }
        };

        // "트리 안에 있다" = 정원이 남은 배정 후보. 한 팀은 트리에 최대 한 번만 들어 있으므로
        // 키가 겹치는 일(compare == 0)은 없다 — put이 기존 항목을 덮어쓰는 경우는 발생하지 않는다.
        TreeMap<TeamKey, Integer> tree = new TreeMap<>(bySumThenIndex);
        for (int t = 0; t < teamCount; t++) {
            tree.put(new TeamKey(0.0, t), t);   // 시작은 전부 합 0(동점) → 2차 규칙에 따라 team0 이 최솟값
        }

        for (Member m : sorted) {
            // 최소 키(가장 왼쪽 노드)를 꺼낸다. 선형 스캔의 안쪽 for O(K)가 이 한 줄 O(log K)로 대체된다.
            int best = tree.pollFirstEntry().getValue();

            teams.get(best).add(m);                     // 팀 배정
            sums[best] += m.skill().doubleValue();      // 팀 별 합 갱신

            // 갱신된 합으로 키를 새로 만들어 재삽입. 정원이 찬 팀은 되넣지 않는다 (다른 버전과 동일한 규칙).
            if (teams.get(best).size() < teamCapacity) {
                tree.put(new TeamKey(sums[best], best), best);
            }
        }
        return teams;
    }


    /*
     * 3단계. 교환 반복. 지역 탐색이고 CPU를 가장 많이 쓰는 구간이다.
     *
     * 한 명씩 맞바꿔 분포 편차를 더 줄일 수 있는 동안 계속 개선한다.
     * 팀 사이의 합 편차는 허용폭 안에서 유지하고, 이 제약이 목표보다 늘 앞선다.
     *
     * 바깥 while 한 바퀴를 스캔이라고 부른다. 스캔 한 번의 흐름은 셋이다.
     *   1. 기준값. 교환 전 상태의 합 편차와 분포 편차, 그리고 이미 허용폭 안인지를 구한다.
     *   2. 후보 전수 조사. 서로 다른 두 팀에서 한 명씩 맞바꾸는 모든 후보를 4중 루프로 훑는다.
     *      후보마다 적용하고, 합 필터로 거르고, 통과하면 분포 편차로 최선을 갱신하고, 되돌린다.
     *   3. 적용과 종료. 최선 교환이 있으면 그 한 건만 실제로 적용하고 다시 스캔한다. 없으면 수렴으로 보고 끝낸다.
     *
     * 후보 평가는 공유 배열 teams를 바꿔 보고 되돌리는 방식이라, 스캔이 끝날 때까지 teams는 기준 상태 그대로다.
     * 실제 이동은 스캔마다 최선 한 건에서만 일어난다.
     */

    /**
     * 교환 반복의 순차 참조 구현이다.
     *
     * <p>병렬판(improve)과 결과를 맞대는 검증에만 쓴다. 운영 경로는 이 메서드를 부르지 않는다.
     * 두 편차는 팀별 합과 제곱합 배열에서 낸다.
     *
     * @param teams     팀 편성 대상 전체. 이 목록을 제자리에서 고친다
     * @param tolerance 합 편차 허용폭
     */
    void improveSequential(List<List<Member>> teams, double tolerance) {

        /* 팀 개수가 변하지 않으므로 배열은 여기서 한 번만 만들고 스캔 바퀴마다 다시 채운다. */
        double[] teamSum = new double[teams.size()];      // 팀별 실력 합
        double[] teamSqSum = new double[teams.size()];    // 팀별 제곱합
        int[] teamSize = new int[teams.size()];           // 팀별 인원

        while (true) {
            /* 이번 스캔의 기준값이다. 교환 전 상태의 두 편차를 낸다.
               앞 바퀴에서 교환 1건이 적용되었으므로 배열을 다시 채우고 거기서 뽑는다. */
            buildTeamStats(teams, teamSum, teamSqSum, teamSize);
            double currentSumDev = sumDeviationOf(teamSum);
            double currentDistDev = distributionDeviationOf(teamSum, teamSqSum, teamSize);

            // 교환 전에 이미 허용폭 안인지를 담는다. 아래 합 필터의 판정 방향이 이 값에 따라 갈린다.
            boolean withinCap = currentSumDev <= tolerance;

            // 이번 스캔의 최선 교환 후보. 위치와 분포 편차 값과 동률 비교 키로 이루어진다.
            // 더 나은 교환을 만나면 셋을 함께 갱신하고, 스캔이 끝나면 이 위치로 교환을 한 번 적용한다.

            // 최선 교환의 두 자리. -1은 아직 후보가 없다는 뜻이다.
            // 스캔이 끝나도 -1이면 개선 교환이 하나도 없었다는 뜻이라 종료한다.
            int bestTeamA = -1, bestIdxA = -1, bestTeamB = -1, bestIdxB = -1;

            // 최선 교환의 분포 편차. 초기값이 현재 상태의 값이라 첫 후보도 현재보다 낮아야 채택된다.
            double bestNewDistDev = currentDistDev;

            // 동률 비교 키. 맞바꾼 두 참가 식별자를 작은 것과 큰 것 순서로 담는다.
            // 분포 편차가 동률이면 이 키가 사전순으로 앞선 후보를 채택한다.
            String bestKeyFirst = null, bestKeySecond = null;

            /* 서로 다른 두 팀에서 한 명씩 맞바꾸는 모든 후보를 훑는다. */
            for (int a = 0; a < teams.size(); a++) {            // 기준이 되는 팀
                for (int b = a + 1; b < teams.size(); b++) {    // 맞대어 볼 팀. a보다 뒤만 보아 같은 짝을 두 번 세지 않는다
                    for (int i = 0; i < teams.get(a).size(); i++) {      // 팀 a의 i번 자리
                            for (int j = 0; j < teams.get(b).size(); j++) {  // 팀 b의 j번 자리

                            /* 이번 후보 교환으로 자리를 옮기는 두 사람의 실력을 미리 변수에 담아 둔다. */
                            double swapTargetSkillA = teams.get(a).get(i).skill().doubleValue();   // 팀 a의 교환 대상 실력
                            double swapTargetSkillB = teams.get(b).get(j).skill().doubleValue();   // 팀 b의 교환 대상 실력

                            /* 교환 로직(swap() 후에 실제 검사 로직을 수행 끝나면 다시 원복 해야 된다) 전에 값을 보존 */
                            double oldSumA = teamSum[a], oldSumB = teamSum[b];
                            double oldSqA = teamSqSum[a], oldSqB = teamSqSum[b];

                            swap(teams, a, i, b, j);            // 후보 교환을 실제로 적용한다

                            /* "swap() 수행 후 teamSum 배열과 teamSqSum 배열 값을 참조 해서 평가하기 때문에 그에 맞게 수정 한다.*/
                            teamSum[a]   = oldSumA - swapTargetSkillA + swapTargetSkillB;   // 팀 a의 합
                            teamSum[b]   = oldSumB - swapTargetSkillB + swapTargetSkillA;   // 팀 b의 합

                            teamSqSum[a] = oldSqA  - swapTargetSkillA * swapTargetSkillA + swapTargetSkillB * swapTargetSkillB;   // 팀 a의 제곱합
                            teamSqSum[b] = oldSqB  - swapTargetSkillB * swapTargetSkillB + swapTargetSkillA * swapTargetSkillA;   // 팀 b의 제곱합

                            double newSumDev = sumDeviationOf(teamSum);      // 교환 후 합 편차. 팀 안의 인원은 훑지 않고 배열만 훑는다

                            /* 합 필터. 합 균형이라는 제약을 깨는 교환을 걸러낸다. 통과한 후보만 분포 편차 계산으로 넘어간다.
                               판정 기준은 교환 전 상태에 따라 두 갈래다.
                                 withinCap이 true이면 교환 전에 이미 허용폭 안이므로 교환 후에도 허용폭 안이어야 통과한다.
                                 withinCap이 false이면 이상치 구성이라 허용폭을 못 지키는 상태이므로,
                                 교환 후 합 편차가 교환 직전보다 나빠지지만 않으면 통과한다. */
                            boolean pass = withinCap ? newSumDev <= tolerance : newSumDev <= currentSumDev;

                            if (pass) {

                                double newDistDev = distributionDeviationOf(teamSum, teamSqSum, teamSize);  // 교환 후 분포 편차. 여기서도 배열만 훑는다

                                /* 교환 전의 최선 값과 이번 교환의 값을 견주어 갈래를 나눈다. */

                                // 분포 편차를 1e-12를 넘게 줄인 교환이다. 새 최선으로 채택하고 값과 위치와 키를 모두 갱신한다.
                                if (newDistDev < bestNewDistDev - 1e-12) {

                                    bestNewDistDev = newDistDev;
                                    bestTeamA = a; bestIdxA = i; bestTeamB = b; bestIdxB = j;

                                    // 교환된 상태에서 두 사람의 참가 식별자를 작은 것과 큰 것 순서로 기록한다.
                                    String idA = teams.get(a).get(i).participationId();
                                    String idB = teams.get(b).get(j).participationId();

                                    bestKeyFirst = idA.compareTo(idB) <= 0 ? idA : idB;
                                    bestKeySecond = idA.compareTo(idB) <= 0 ? idB : idA;

                                }

                                // 줄어든 정도가 1e-12 안이라 사실상 동률인 교환이다.
                                // 참가 식별자가 사전순으로 앞선 짝을 고른다. 이 규칙이 있어야 어떤 순서로 훑든 같은 교환을 고른다.
                                else if (Math.abs(newDistDev - bestNewDistDev) <= 1e-12 && bestTeamA != -1) {

                                    String idA = teams.get(a).get(i).participationId();
                                    String idB = teams.get(b).get(j).participationId();
                                    String first = idA.compareTo(idB) <= 0 ? idA : idB;
                                    String second = idA.compareTo(idB) <= 0 ? idB : idA;

                                    if (first.compareTo(bestKeyFirst) < 0 || (first.equals(bestKeyFirst) && second.compareTo(bestKeySecond) < 0)) {
                                        bestTeamA = a; bestIdxA = i; bestTeamB = b; bestIdxB = j;
                                        bestKeyFirst = first; bestKeySecond = second;
                                    }
                                }
                            }

                            // 분포 편차가 개선되지 않았으면 최선 후보를 갱신하지 않는다.

                            teamSum[a] = oldSumA;   teamSum[b] = oldSumB;                // 담아 두었던 옛 값을 그대로 다시 넣는다
                            teamSqSum[a] = oldSqA;  teamSqSum[b] = oldSqB;               // 뺄셈으로 되돌리지 않는다. double은 더한 값을 다시 빼도 원래 값이 된다는 보장이 없다

                            // 원상 복구. 후보 평가는 가정일 뿐이므로 되돌린다.
                            swap(teams, a, i, b, j);
                        }
                    }
                }
            }

            if (bestTeamA == -1) {
                return;   // 이번 스캔에서 분포 편차를 줄이는 교환이 하나도 없었다 → 수렴, 교환 로직을 완전 종료
            }
            swap(teams, bestTeamA, bestIdxA, bestTeamB, bestIdxB);  // 최선 교환 1건만 확정 적용 후 다시 스캔
        }
    }

    /* ===== fork-join 병렬 구간 ===== */

    /** 운영 분할 개수. 증분 재계산 측정(인원 200, 정원 10, 논리 프로세서 12)에서
     *  워커 스레드 12개에 분할 9가 여섯 실행 중 다섯에서 가장 빨랐다.
     *  다른 입력 크기와 기계에서는 재측정 전까지 근거가 없다. */
    private static final int OPERATIONAL_SPLIT_COUNT = 9;

    /**
     * 교환 반복을 실행한다. 운영 경로의 진입점이다.
     *
     * <p>풀 병렬도는 논리 프로세서 수로 두고, 말단 작업은 측정으로 고른 분할 개수로 나눈다.
     * 짝 수가 분할 개수보다 적으면 본체의 분할 규칙이 짝 수에서 멈춘다.
     *
     * @param teams     팀 편성 대상 전체. 이 목록을 제자리에서 고친다
     * @param tolerance 합 편차 허용폭
     */
    void improve(List<List<Member>> teams, double tolerance) {

        int cpuCount = Runtime.getRuntime().availableProcessors();   // 풀 병렬도

        improve(teams, tolerance, cpuCount, 0, OPERATIONAL_SPLIT_COUNT);   // 분할 개수 규칙으로 나눈다
    }

    /**
     * 말단 작업을 몇 개로 만들지 직접 지정하는 경로다. 분할 개수 스윕에 쓴다.
     *
     * <p>짝 구간을 목표 개수만큼 나누므로 실제 말단 작업 수가 이 값과 같아진다.
     * 측정 프로브와 검증 프로그램(ImproveEquivalenceCheck)이 이 경로를 부른다.
     *
     * @param teams       팀 편성 대상 전체
     * @param tolerance   합 편차 허용폭
     * @param parallelism 풀에 둘 워커 스레드 수
     * @param splitCount  만들 말단 작업 수
     */
    void improveBySplitCount(List<List<Member>> teams, double tolerance, int parallelism, int splitCount) {
        improve(teams, tolerance, parallelism, 0, splitCount);
    }

    /**
     * 교환 반복의 몸통이다. 위 진입점 둘(운영 경로와 분할 개수 지정 경로)이 이 메서드로 모인다.
     *
     * <p>splitCount가 0보다 크면 목표 분할 개수 규칙으로 나누고, 0이면 workSize 규칙으로 나눈다.
     * 두 규칙의 차이는 자르는 방식 하나뿐이고 말단 작업의 평가와 병합 규칙은 같다.
     *
     * @param teams       팀 편성 대상 전체
     * @param tolerance   합 편차 허용폭
     * @param parallelism 풀에 둘 워커 스레드 수
     * @param workSize    말단 작업 하나가 맡는 짝 개수. splitCount가 0일 때만 쓴다
     * @param splitCount  만들 말단 작업 수. 0이면 workSize 규칙을 쓴다
     */
    private void improve(List<List<Member>> teams, double tolerance, int parallelism, int workSize, int splitCount) {

        // 이 호출에서 쓸 fork-join 풀이다. 워커 수는 parallelism이고, 운영 경로에서는 논리 프로세서 수와 같다.
        // 풀을 어디서 만들고 재사용할지는 아직 정하지 않았다.
        ForkJoinPool pool = new ForkJoinPool(parallelism);


        int teamCount = teams.size();
        int pairCount = teamCount * (teamCount - 1) / 2;    // 팀이 넷이면 짝은 여섯이다

        int [] pairA = new int[pairCount];
        int [] pairB = new int[pairCount];

        int p = 0;

        /* 팀 짝을 번호표 둘로 편다. p번 칸이 팀 pairA[p]와 팀 pairB[p]의 짝이다.
           b를 a+1부터 돌려 a보다 뒤만 보므로 자기 짝도 없고 같은 짝을 두 번 세지도 않는다.
           정원이 같아 짝 하나의 일량이 정원의 제곱으로 균일하다.
           한 줄로 펴 두면 번호 구간을 어디서 잘라도 일량이 고르게 나뉜다.
           팀 리스트를 반으로 나누면 두 반을 가로지르는 짝이 사라지므로 이렇게 편다.

           팀이 넷이고 짝이 여섯일 때의 예다.
             p : 0  1  2  3  4  5
             a : 0  0  0  1  1  2
             b : 1  2  3  2  3  3
           b는 a+1부터 K-1까지 돌고 a가 오르면 a+1로 되돌아간다. */
        for (int a = 0; a < teamCount; a++) {
            for (int b = a + 1; b < teamCount; b++) {
                pairA[p] = a; pairB[p] = b;
                p++;
            }
        }

        /* 팀 개수가 변하지 않으므로 배열은 여기서 한 번만 만들고 스캔 바퀴마다 다시 채운다.
           말단이 각자 만들지 않는다. 분할이 36이면 스캔 한 바퀴에 팀 전체 훑기가 36번 일어나기 때문이다. */
        double[] teamSum = new double[teamCount];      // 팀별 실력 합
        double[] teamSqSum = new double[teamCount];    // 팀별 제곱합
        int[] teamSize = new int[teamCount];           // 팀별 인원

        while (true) {
            // 이번 스캔의 기준값이다. 교환 전 상태의 두 편차를 낸다.
            // 앞 바퀴에서 교환 1건이 적용되었으므로 배열을 다시 채우고 거기서 뽑는다.
            buildTeamStats(teams, teamSum, teamSqSum, teamSize);
            double currentSumDev = sumDeviationOf(teamSum);
            double currentDistDev = distributionDeviationOf(teamSum, teamSqSum, teamSize);

            // 교환 전에 이미 허용폭 안인지를 담는다. 말단의 합 필터가 이 값으로 판정 방향을 가른다.
            boolean withinCap = currentSumDev <= tolerance;

            /* 후보 전수 조사를 fork-join 풀에 맡긴다.
               루트 작업 하나를 제출하고, 분할과 병합을 거친 최종 결과 한 건을 돌려받는다. */
            ImproveTaskResult result = pool.invoke(
                    new ImproveTask(teams, pairA, pairB, 0, pairCount, workSize, splitCount,
                            tolerance, withinCap, currentSumDev, currentDistDev,
                            teamSum, teamSqSum, teamSize));

            /* 이후는 순차 구현과 같은 적용과 종료 판단이다. 지역 변수 대신 반환 객체의 값을 읽는 점만 다르다. */

            if (result.bestTeamA == -1) {
                pool.shutdown();   // 이 호출에서 쓴 풀을 닫는다
                return;   // 개선 교환이 하나도 없었다. 수렴으로 보고 끝낸다
            }
            swap(teams, result.bestTeamA, result.bestIdxA, result.bestTeamB, result.bestIdxB);   // 최선 교환 한 건만 적용하고 다시 스캔한다
        }
    }

    /**
     * fork-join 작업 단위다.
     *
     * <p>담당 짝 구간이 분할 기준보다 크면 반으로 나눠 하위 작업 둘에 맡기고,
     * 그 이하면 받은 범위를 직접 평가해 부분 결과를 반환한다. 후자를 말단 작업이라고 부른다.
     * 구간 표기 [lo, hi)는 lo를 포함하고 hi는 제외한다는 뜻이다.
     */
    public class ImproveTask extends RecursiveTask<ImproveTaskResult> {

        /* 모든 작업이 함께 쓰는 값이다. 읽기만 한다. */

        List<List<Member>> teams;   // 쪼개지 않은 원본 배정. 말단 작업이 복사본을 만들 때만 읽는다
        int[] pairA;                // 짝 번호표. p번 짝의 팀 a
        int[] pairB;                // 짝 번호표. p번 짝의 팀 b

        double[] teamSum;           // 팀별 실력 합. improve()가 이번 스캔에서 채운 원본이고 말단이 자기 것으로 복사한다
        double[] teamSqSum;         // 팀별 제곱합. 같은 원본이고 말단이 자기 것으로 복사한다
        int[] teamSize;             // 팀별 인원. 평가 중 변하지 않아 복사하지 않고 함께 읽는다

        int workSize;               // 분할 중단 크기. improve()가 한 번 계산한 고정값이고 splitCount가 0일 때만 쓴다
        int splitCount;             // 이 작업이 만들어야 할 말단 작업 수. 0이면 workSize 규칙을 쓴다

        double tolerance;           // 합 필터 기준. withinCap이 true일 때 쓴다
        boolean withinCap;          // 합 필터의 판정 방향. 스캔 시작 시점의 값이다
        double currentSumDev;       // 합 필터 기준. withinCap이 false일 때 쓴다
        double currentDistDev;      // 스캔 시작 시점의 분포 편차. 말단 작업의 최선 값 출발점이다

        /* 작업마다 다른 값이다. 이 작업이 맡은 몫이다. */

        int lo;                     // 담당 짝 번호 구간의 시작. 포함한다
        int hi;                     // 담당 짝 번호 구간의 끝. 포함하지 않는다

        /**
         * @param teams          쪼개지 않은 원본 배정
         * @param pairA          짝 번호표의 팀 a
         * @param pairB          짝 번호표의 팀 b
         * @param lo             담당 구간 시작. 포함한다
         * @param hi             담당 구간 끝. 포함하지 않는다
         * @param workSize       분할 중단 크기
         * @param splitCount     만들어야 할 말단 작업 수. 0이면 workSize 규칙을 쓴다
         * @param tolerance      합 편차 허용폭
         * @param withinCap      스캔 시작 시점에 이미 허용폭 안이었는지
         * @param currentSumDev  스캔 시작 시점의 합 편차
         * @param currentDistDev 스캔 시작 시점의 분포 편차
         * @param teamSum        팀별 실력 합
         * @param teamSqSum      팀별 제곱합
         * @param teamSize       팀별 인원
         */
        public ImproveTask(
                List<List<Member>> teams,
                int[] pairA,
                int[] pairB,

                int lo,
                int hi,
                int workSize,
                int splitCount,

                double tolerance,
                boolean withinCap,
                double currentSumDev,
                double currentDistDev,

                double[] teamSum,
                double[] teamSqSum,
                int[] teamSize)
        {
            this.teams = teams;
            this.pairA = pairA;
            this.pairB = pairB;

            this.lo = lo;
            this.hi = hi;
            this.workSize = workSize;
            this.splitCount = splitCount;

            this.tolerance = tolerance;
            this.withinCap = withinCap;
            this.currentSumDev = currentSumDev;
            this.currentDistDev = currentDistDev;

            this.teamSum = teamSum;
            this.teamSqSum = teamSqSum;
            this.teamSize = teamSize;
        }

        @Override
        protected ImproveTaskResult compute() {

            /* 나눈 쪽이 병합도 맡는다. fork만 하고 join을 하지 않으면 하위 결과를 회수할 곳이 없으므로,
               나눈 이 compute()가 join으로 두 결과를 받아 병합해 위로 반환한다. */

            /* 1. 분할. 규칙 둘 중 하나로 자른다.
                 splitCount가 0보다 크면 목표 분할 개수 규칙이다. 목표를 반으로 나누고 구간도 그 비율로 자른다.
                 짝 190개에 목표가 6이면 왼쪽 3과 오른쪽 3으로 나누고 자르는 위치는 95다.
                 splitCount가 0이면 workSize 규칙이다. 구간이 workSize보다 길면 반으로 자른다. */
            int mid, leftSplit, rightSplit;
            boolean doSplit;

            if (splitCount > 0) {
                leftSplit  = splitCount / 2;                                   // 왼쪽이 맡을 말단 작업 수
                rightSplit = splitCount - leftSplit;                           // 오른쪽이 맡을 말단 작업 수
                mid = lo + (int) ((long) (hi - lo) * leftSplit / splitCount);  // 목표 비율대로 자르는 위치
                doSplit = splitCount > 1 && mid > lo && mid < hi;              // 목표가 1이거나 더 자를 수 없으면 말단으로 간다
            } else {
                leftSplit  = 0;
                rightSplit = 0;
                mid = (lo + hi) / 2;
                doSplit = hi - lo > workSize;
            }

            if (doSplit) {

                ImproveTask leftTask  = new ImproveTask(teams, pairA, pairB, lo,  mid, workSize, leftSplit,
                        tolerance, withinCap, currentSumDev, currentDistDev,
                        teamSum, teamSqSum, teamSize);
                ImproveTask rightTask = new ImproveTask(teams, pairA, pairB, mid, hi,  workSize, rightSplit,
                        tolerance, withinCap, currentSumDev, currentDistDev,
                        teamSum, teamSqSum, teamSize);

                leftTask.fork();                                      // 왼쪽만 큐에 넣는다. 다른 스레드가 가져간다
                ImproveTaskResult rightResult = rightTask.compute();  // 오른쪽은 지금 스레드가 직접 한다
                ImproveTaskResult leftResult  = leftTask.join();      // 왼쪽 결과를 회수한다

                /* 병합. 하위 두 결과 중 하나를 골라 위로 올린다. 규칙은 순차 구현의 최선 후보 규칙과 같다.
                   단계마다 한 건으로 줄여 올라가므로 최상위 호출부는 최종 한 건만 받는다. */
                if (leftResult.bestTeamA == -1)  return rightResult;   // 왼쪽에 후보가 없으면 오른쪽을 올린다
                if (rightResult.bestTeamA == -1) return leftResult;    // 오른쪽에 후보가 없으면 왼쪽을 올린다

                if (rightResult.bestNewDistDev < leftResult.bestNewDistDev - 1e-12) {
                    return rightResult;                   // 오른쪽이 1e-12를 넘게 작을 때만 오른쪽이 이긴다
                }
                if (Math.abs(rightResult.bestNewDistDev - leftResult.bestNewDistDev) <= 1e-12) {
                    // 동률이면 참가 식별자 키를 사전순으로 견준다. 순차 구현의 동률 규칙과 같은 비교다.
                    if (rightResult.bestKeyFirst.compareTo(leftResult.bestKeyFirst) < 0
                            || (rightResult.bestKeyFirst.equals(leftResult.bestKeyFirst)
                            && rightResult.bestKeySecond.compareTo(leftResult.bestKeySecond) < 0)) {
                        return rightResult;
                    }
                    return leftResult;                    // 키까지 같거나 왼쪽이 앞서면 왼쪽이 이긴다
                }
                return leftResult;                        // 왼쪽이 1e-12를 넘게 작으면 왼쪽이 이긴다
            }

            /* 여러 워커가 같은 배정을 동시에 고치면 안 되므로 말단마다 깊은 복사를 뜬다. */
            List<List<Member>> local = new ArrayList<>(teams.size());
            for (List<Member> team : teams) {
                local.add(new ArrayList<>(team));
            }

            /* 합과 제곱합은 평가 중에 고쳤다 되돌리는 값이라 말단마다 자기 것을 가진다.
               인원은 값이 변하지 않아 복사하지 않고 원본을 함께 읽는다. */
            double[] localSum = teamSum.clone();
            double[] localSqSum = teamSqSum.clone();
            int[] localSize = teamSize;

            double bestNewDistDev = currentDistDev;   // 이 말단의 최선 값. 스캔 시작 시점 값에서 출발한다

            ImproveTaskResult improveTaskResult = new ImproveTaskResult();

            // 2. 말단 작업. 담당 구간 [lo, hi)의 짝만 번호표에서 찾아 평가한다.
            for (int pIdx = lo; pIdx < hi; pIdx++) {
                int a = pairA[pIdx];                  // 이 짝의 팀 a
                int b = pairB[pIdx];                  // 이 짝의 팀 b

                for (int i = 0; i < local.get(a).size(); i++) {      // 팀 a의 i번 자리
                    for (int j = 0; j < local.get(b).size(); j++) {  // 팀 b의 j번 자리
                            // 평가 한 번이다. 실력을 읽고, 맞바꾸고, 두 팀을 고치고, 두 편차를 내고, 되돌린다.

                            /* 이번 후보 교환으로 자리를 옮기는 두 사람의 실력을 미리 변수에 담아 둔다. */
                            double swapTargetSkillA = local.get(a).get(i).skill().doubleValue();   // 팀 a의 교환 대상 실력
                            double swapTargetSkillB = local.get(b).get(j).skill().doubleValue();   // 팀 b의 교환 대상 실력

                            /* 교환 로직(swap() 후에 실제 검사 로직을 수행 끝나면 다시 원복 해야 된다) 전에 값을 보존 */
                            double oldSumA = localSum[a], oldSumB = localSum[b];
                            double oldSqA = localSqSum[a], oldSqB = localSqSum[b];

                            swap(local, a, i, b, j);            // 후보 교환을 실제로 적용한다

                            /* "swap() 수행 후 localSum 배열과 localSqSum 배열 값을 참조 해서 평가하기 때문에 그에 맞게 수정 한다.*/
                            localSum[a]   = oldSumA - swapTargetSkillA + swapTargetSkillB;   // 팀 a의 합
                            localSum[b]   = oldSumB - swapTargetSkillB + swapTargetSkillA;   // 팀 b의 합

                            localSqSum[a] = oldSqA  - swapTargetSkillA * swapTargetSkillA + swapTargetSkillB * swapTargetSkillB;   // 팀 a의 제곱합
                            localSqSum[b] = oldSqB  - swapTargetSkillB * swapTargetSkillB + swapTargetSkillA * swapTargetSkillA;   // 팀 b의 제곱합

                            double newSumDev = sumDeviationOf(localSum);      // 교환 후 합 편차. 팀 안의 인원은 훑지 않고 배열만 훑는다

                            /* 합 필터. 합 균형이라는 제약을 깨는 교환을 걸러낸다.
                               withinCap이면 교환 후에도 허용폭 안이어야 통과하고,
                               아니면 교환 전보다 나빠지지만 않으면 통과한다. */
                            boolean pass = withinCap ? newSumDev <= tolerance : newSumDev <= currentSumDev;

                            if (pass) {

                                double newDistDev = distributionDeviationOf(localSum, localSqSum, localSize);  // 교환 후 분포 편차. 여기서도 배열만 훑는다

                                // 분포 편차를 1e-12를 넘게 줄인 교환이다. 값과 위치와 키를 모두 갱신한다.
                                if (newDistDev < bestNewDistDev - 1e-12) {

                                    bestNewDistDev = newDistDev;

                                    improveTaskResult.bestTeamA = a; improveTaskResult.bestIdxA = i;
                                    improveTaskResult.bestTeamB = b; improveTaskResult.bestIdxB = j;

                                    // 동률 비교 키다. 맞바꾼 두 참가 식별자를 작은 것과 큰 것 순서로 기록한다.
                                    String idA = local.get(a).get(i).participationId();
                                    String idB = local.get(b).get(j).participationId();

                                    improveTaskResult.bestKeyFirst = idA.compareTo(idB) <= 0 ? idA : idB;
                                    improveTaskResult.bestKeySecond = idA.compareTo(idB) <= 0 ? idB : idA;

                                }

                                // 줄어든 정도가 1e-12 안이라 사실상 동률이다. 키가 사전순으로 앞설 때만 위치와 키를 갱신한다.
                                else if (Math.abs(newDistDev - bestNewDistDev) <= 1e-12 && improveTaskResult.bestTeamA != -1) {

                                    String idA = local.get(a).get(i).participationId();
                                    String idB = local.get(b).get(j).participationId();
                                    String first = idA.compareTo(idB) <= 0 ? idA : idB;
                                    String second = idA.compareTo(idB) <= 0 ? idB : idA;

                                    if (first.compareTo(improveTaskResult.bestKeyFirst) < 0 || (first.equals(improveTaskResult.bestKeyFirst) && second.compareTo(improveTaskResult.bestKeySecond) < 0)) {
                                        improveTaskResult.bestTeamA = a; improveTaskResult.bestIdxA = i; improveTaskResult.bestTeamB = b; improveTaskResult.bestIdxB = j;
                                        improveTaskResult.bestKeyFirst = first; improveTaskResult.bestKeySecond = second;
                                    }
                                }
                            }

                            localSum[a] = oldSumA;   localSum[b] = oldSumB;              // 담아 두었던 옛 값을 그대로 다시 넣는다
                            localSqSum[a] = oldSqA;  localSqSum[b] = oldSqB;             // 뺄셈으로 되돌리지 않는다. double은 더한 값을 다시 빼도 원래 값이 된다는 보장이 없다

                            // 원상 복구. 후보 평가는 가정일 뿐이므로 되돌린다.
                            swap(local, a, i, b, j);
                        }
                    }

            }   // 4중 루프 끝

            /* 순차 구현과 다른 점이다. 순차는 스캔이 끝난 이 지점에서 바로 적용과 종료를 판단했지만,
               이 작업은 부분 결과를 만들어 반환만 한다.
               적용과 종료 판단은 병합을 거친 최종 결과를 받는 최상위 호출부가 한다. */

            improveTaskResult.bestNewDistDev = bestNewDistDev;

            return improveTaskResult;
        } // compute() 끝

    }

    /**
     * fork-join 부분 결과다.
     *
     * <p>최선 교환의 값과 위치와 동률 비교 키를 담아 상위 작업과 호출부로 전달한다.
     */
    private class ImproveTaskResult {

        // 이 부분 결과의 최선 분포 편차다. 개선 후보가 없었으면 스캔 시작 시점 값 그대로이므로,
        // 후보가 있었는지는 이 값이 아니라 bestTeamA가 -1인지로 판별해야 한다.
        double bestNewDistDev;

        int bestTeamA, bestIdxA, bestTeamB, bestIdxB;

        String bestKeyFirst, bestKeySecond;

        public ImproveTaskResult() {

            this.bestTeamA = -1;
            this.bestIdxA = -1;
            this.bestTeamB = -1;
            this.bestIdxB = -1;
        }

        public ImproveTaskResult(

                double bestNewDistDev,

                int bestTeamA,
                int bestIdxA,
                int bestTeamB,
                int bestIdxB,

                String bestKeyFirst,
                String bestKeySecond
        ){
            this.bestNewDistDev = bestNewDistDev;

            this.bestTeamA = bestTeamA;
            this.bestIdxA = bestIdxA;
            this.bestTeamB = bestTeamB;
            this.bestIdxB = bestIdxB;

            this.bestKeyFirst = bestKeyFirst;
            this.bestKeySecond = bestKeySecond;
        }
    }

    /**
     * 4단계. 개선 결과 둘 중 하나를 채택한다.
     *
     * <p>제약인 합이 목표인 분포보다 늘 앞선다. 우선순위는 셋이다.
     * 먼저 허용폭을 지킨 쪽이 이긴다. 한쪽만 지켰으면 그쪽을 고른다.
     * 둘 다 지켰으면 분포 편차가 낮은 쪽을 고르고, 그마저 같으면 합 편차가 낮은 쪽을 고른다.
     * 둘 다 어겼으면 합 편차가 낮은 쪽을 고르고, 같으면 분포 편차가 낮은 쪽을 고른다.
     * 마지막까지 동률이면 r1을 고른다. 이것도 결정성의 일부다.
     *
     * @param r1        교대 방향 배정을 개선한 결과
     * @param r2        그리디 배정을 개선한 결과
     * @param tolerance 합 편차 허용폭
     * @return 채택한 배정
     */
    private List<List<Member>> adopt(List<List<Member>> r1, List<List<Member>> r2, double tolerance) {
        double sum1 = sumDeviation(r1), dist1 = distributionDeviation(r1);
        double sum2 = sumDeviation(r2), dist2 = distributionDeviation(r2);
        boolean ok1 = sum1 <= tolerance, ok2 = sum2 <= tolerance;   // 각자 제약 충족 여부

        if (ok1 && !ok2) return r1;    // r1만 제약 충족
        if (ok2 && !ok1) return r2;    // r2만 제약 충족
        if (ok1) { // 둘 다 지킴: 목표(분포) 낮은 쪽, 같으면 합 낮은 쪽
            if (dist1 != dist2) return dist1 < dist2 ? r1 : r2;
            return sum1 <= sum2 ? r1 : r2;
        }
        // 둘 다 어김: 제약을 조금이라도 덜 어긴(합 낮은) 쪽, 같으면 분포 낮은 쪽
        if (sum1 != sum2) return sum1 < sum2 ? r1 : r2;
        return dist1 <= dist2 ? r1 : r2;
    }

    /* 판정 값을 내는 메서드 둘이다. */
    // * 두 메서드 모두 매 호출마다 전체 팀을 처음부터 순회한다(각 O(K·N)).
    //   [P0-1 ②] 평가 경로는 배열판(buildTeamStats·sumDeviationOf·distributionDeviationOf)으로 옮겼다.
    //   이 두 메서드는 adopt()와 검증 프로그램이 최종 상태를 잴 때만 쓴다.

    /**
     * 합 편차를 낸다. 팀별 실력 합 중 최댓값에서 최솟값을 뺀 값이다.
     *
     * <p>값이 작을수록 팀 사이의 실력 합이 균등하다.
     *
     * @param teams 팀별 인원 목록
     * @return 합 편차
     */
    double sumDeviation(List<List<Member>> teams) {
        double max = Double.NEGATIVE_INFINITY, min = Double.POSITIVE_INFINITY;

        for (List<Member> team : teams) {
            double sum = team.stream().mapToDouble(m -> m.skill().doubleValue()).sum();
            max = Math.max(max, sum);
            min = Math.min(min, sum);
        }
        return max - min;
    }


    /**
     * 분포 편차를 낸다. 팀별 분산 중 최댓값에서 최솟값을 뺀 값이다.
     *
     * <p>값이 작을수록 팀들의 내부 산포가 서로 비슷하다.
     * 분산은 팀 평균에서 각 참가자가 벗어난 거리를 제곱해 다시 평균낸 값이고 인원 수로 나눈다.
     * 팀마다 평균을 한 번 내고 벗어난 거리의 제곱 평균을 한 번 더 내므로 팀당 두 번 훑는다.
     *
     * <p>평가 경로는 배열에서 값을 내는 distributionDeviationOf를 쓴다.
     * 이 메서드는 adopt()와 검증 프로그램이 최종 상태를 잴 때만 쓴다.
     *
     * @param teams 팀별 인원 목록
     * @return 분포 편차
     */
    double distributionDeviation(List<List<Member>> teams) {
        double max = Double.NEGATIVE_INFINITY, min = Double.POSITIVE_INFINITY;

        for (List<Member> team : teams) {   // 모든 팀을 순차적으로 순회 하면서 조회
            /* 현재 팀 내 모든 인원의 점수 총합에 대한 평균을 계산한다. */
            double mean = team.stream().mapToDouble(m -> m.skill().doubleValue()).average().orElse(0);
            /* 햔제 팀 내 각 개별 인원의 점수가 평균으로부터 얼마만큼 분포되어 있는지를 계산 및 누적 후 이를 제곱 후 합산한 것에 대한 평균 */
            double variance = team.stream()
                    .mapToDouble(m -> {
                        double d = m.skill().doubleValue() - mean;
                        return d * d;
                    }).average().orElse(0);

            /* 앞서 계산한 각 팀 별로 분포 편차가 계산될 때 마다 기존의 max 와 min 값을 갱신, 이 값은 "max - min" 인 "분포 편차" 로써 계산 되어 반환.*/
            max = Math.max(max, variance);
            min = Math.min(min, variance);
        }
        /* 현재 분포 정도가 가장 높은 팀과 분포 정도가 가장 낮은 팀 간 분포 차이가 얼마인지 계산하여 반환. */
        return max - min;
    }


    /* 배열에서 두 편차를 내는 메서드 셋이다.
       평가 경로인 improveSequential과 ImproveTask만 이 셋을 쓴다.
       adopt()와 검증 프로그램은 위의 sumDeviation과 distributionDeviation을 그대로 쓴다. */

    /** 팀별 합과 제곱합과 인원을 배열 셋에 채운다. 스캔을 시작할 때 한 번 부른다. */
    void buildTeamStats(List<List<Member>> teams, double[] outSum, double[] outSqSum, int[] outSize) {

        for (int t = 0; t < teams.size(); t++) {

            List<Member> team = teams.get(t);
            double sum = 0.0;
            double sqSum = 0.0;

            for (int m = 0; m < team.size(); m++) {
                double v = team.get(m).skill().doubleValue();
                sum += v;
                sqSum += v * v;
            }

            outSum[t] = sum;
            outSqSum[t] = sqSum;
            outSize[t] = team.size();
        }
    }

    /** 합 편차 = 팀별 합 중 (최댓값 − 최솟값). 팀 안의 인원을 훑지 않고 배열만 훑는다. */
    double sumDeviationOf(double[] teamSum) {

        double max = Double.NEGATIVE_INFINITY, min = Double.POSITIVE_INFINITY;

        for (int t = 0; t < teamSum.length; t++) {
            max = Math.max(max, teamSum[t]);
            min = Math.min(min, teamSum[t]);
        }
        return max - min;
    }

    /** 분포 편차 = 팀별 분산 중 (최댓값 − 최솟값).
     *  분산은 제곱합 ÷ 인원 − (합 ÷ 인원)²으로 낸다. 인원이 0이면 0으로 두는데,
     *  스트림 구현의 average().orElse(0)이 두 자리 모두 0을 내는 것과 같은 자리다. */
    double distributionDeviationOf(double[] teamSum, double[] teamSqSum, int[] teamSize) {

        double max = Double.NEGATIVE_INFINITY, min = Double.POSITIVE_INFINITY;

        for (int t = 0; t < teamSum.length; t++) {

            int size = teamSize[t];
            double variance = 0.0;

            if (size > 0) {
                double mean = teamSum[t] / size;
                variance = teamSqSum[t] / size - mean * mean;
            }

            max = Math.max(max, variance);
            min = Math.min(min, variance);
        }
        return max - min;
    }


    /**
     * 두 팀의 특정 자리에 있는 사람을 맞바꾼다. 넘겨받은 목록을 제자리에서 고친다.
     *
     * <p>교환 반복은 이 메서드로 교환해 보고 평가한 뒤 되돌리기를 되풀이한다.
     * 넘겨받은 목록을 직접 고치므로, 병렬 평가에서는 말단마다 복사본을 떠서 그 복사본을 넘긴다.
     *
     * @param teams 고칠 팀별 인원 목록
     * @param teamA 첫 번째 팀 인덱스
     * @param idxA  첫 번째 팀에서 맞바꿀 자리
     * @param teamB 두 번째 팀 인덱스
     * @param idxB  두 번째 팀에서 맞바꿀 자리
     */
    private void swap(List<List<Member>> teams, int teamA, int idxA, int teamB, int idxB) {

        // swap 대상 중 첫번째 대상인 팀 A의 인원 idxA 를 선택, 이 위치를 teamB 의 idxB 와 스왑 한다.
        Member tmp = teams.get(teamA).get(idxA);

        /* 맞교환 로직. */
        teams.get(teamA).set(idxA, teams.get(teamB).get(idxB));
        teams.get(teamB).set(idxB, tmp);
    }

    /**
     * 빈 팀 목록을 만든다. 팀마다 빈 ArrayList를 둔다.
     *
     * @param teamCount 팀 수
     * @return 빈 팀 목록
     */
    private List<List<Member>> emptyTeams(int teamCount) {

        List<List<Member>> teams = new ArrayList<>(teamCount);

        for (int i = 0; i < teamCount; i++) {
            teams.add(new ArrayList<>());
        }
        return teams;
    }
}
