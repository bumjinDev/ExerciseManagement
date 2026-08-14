package com.exercisemanagement.challenge.service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.atomic.AtomicLong;

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

    /* [계측 · 활성] improve()의 말단 작업을 재는 정적 카운터 넷이다.
       ImproveTask.compute()의 말단 구간이 지역 변수에 모은 값을 말단이 끝날 때 여기에 더한다.
       카운터 하나에 여러 워커가 동시에 더하므로 AtomicLong을 쓴다.
       같은 패키지의 프로브(ImproveCpuUtilizationProbe)가 improve() 호출 직전에 0으로 되돌리고 직후에 읽는다.
       문서용 시간 확정 측정(JMH) 전에는 이 블록과 compute() 안의 계측 코드를 모두 주석으로 비활성화한다. */
    static final AtomicLong evalCount = new AtomicLong();       // 평가 횟수. 가장 안쪽 반복문 몸통이 실행된 총횟수
    static final AtomicLong passCount = new AtomicLong();       // 통과 횟수. 합 필터를 통과해 분포 편차 계산까지 간 횟수
    static final AtomicLong copyTimeNs = new AtomicLong();      // 복사 구간 시간 합(ns). 말단이 팀 배정을 복사한 시간
    static final AtomicLong evalLoopTimeNs = new AtomicLong();  // 평가 반복문 구간 시간 합(ns). 말단의 4중 루프 전체 시간

    /** [계측] 카운터 넷을 0으로 되돌린다. 프로브가 improve() 호출 직전에 부른다. */
    static void resetImproveInstrumentation() {
        evalCount.set(0);
        passCount.set(0);
        copyTimeNs.set(0);
        evalLoopTimeNs.set(0);
    }

    /* [상세 기록 · 활성] 스캔 한 바퀴와 말단 하나를 각각 한 줄로 남긴다.
       위 카운터 넷과는 별개 체계다. 실행 중에는 메모리 버퍼에만 쌓고,
       출력은 프로브가 improve() 호출이 끝난 뒤 drainDetailLog()로 꺼내서 한다.
       측정 구간 안에 입출력을 넣지 않으려는 것이다.

       레코드는 두 종류이고 쉼표로 구분한 한 줄이다.
         IMPLOG,leaf,스레드,병렬도,분할,회차,스캔,복사ns,4중루프ns,sumDev횟수,sumDevns,distDev횟수,distDevns,시작시각ms
         IMPLOG,scan,main,병렬도,분할,회차,스캔,바퀴ns,시작시각ms

       시작시각ms는 그 구간이 시작된 시각이다(System.currentTimeMillis, epoch ms).
       클럭 CSV의 시각 열과 맞추는 데 쓴다.
       병렬도와 분할과 스캔 번호는 엔진이 설정하고, 회차와 스위치는 프로브가 설정한다.
       스위치가 꺼진 실행(워밍업, 워커 장부 실행, 순차)은 기록하지 않는다.
       시각 읽기 자체는 스위치와 무관하게 늘 수행한다. 안쪽 반복문에 분기를 넣지 않아야
       JIT 컴파일 결과가 실행마다 같기 때문이다.

       주의: 평가 1회마다 시각 읽기가 2회에서 4회 늘어 전체의 1%에서 2%가 더해진다.
       이 기록이 켜진 실행의 절대값은 다른 회차와 직접 맞대지 않는다.
       문서용 시간 확정 측정(JMH) 전에는 전부 비활성화한다. */
    static volatile boolean detailLogEnabled = false;  // 기록 스위치. 프로브가 측정 5회에서만 켠다
    static volatile int detailLogParallelism;          // 풀 병렬도. improve() 몸통이 설정한다
    static volatile int detailLogSplitCount;           // 목표 분할 개수. improve() 몸통이 설정한다
    static volatile int detailLogRunIndex;             // 측정 회차 1부터 5까지. 프로브가 설정한다
    static volatile int detailLogScan;                 // 스캔 번호 1부터. while이 바퀴마다 갱신한다
    static final java.util.concurrent.ConcurrentLinkedQueue<String> detailLog =
            new java.util.concurrent.ConcurrentLinkedQueue<>();   // 레코드 버퍼. 여러 워커가 동시에 넣으므로 동시성 큐를 쓴다

    /**
     * [상세 기록] 쌓인 레코드를 전부 꺼내 반환한다. 프로브가 표를 출력한 뒤 부른다.
     *
     * @return 꺼낸 레코드 목록. 버퍼는 비워진다
     */
    static List<String> drainDetailLog() {
        List<String> out = new ArrayList<>();
        String line;
        while ((line = detailLog.poll()) != null) {
            out.add(line);
        }
        return out;
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

    /* [버전 2 · 활성] 우선순위 큐(이진 힙)로 구현한 판이다. 보고서 2편의 측정 코드와 같다.

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
    List<List<Member>> seedGreedy(List<Member> sorted, int teamCount, int teamCapacity) {

        List<List<Member>> teams = emptyTeams(teamCount);       // 배정 결과. teams.get(t)가 팀 t의 인원 목록이다
        double[] sums = new double[teamCount];                  // 팀별 현재 합. 비교자가 읽는 키의 유일한 원본이다

        // 팀 인덱스 둘을 받아 호출 시점의 sums 값을 조회해 비교한다.
        // 여기서는 규칙을 등록만 하고, 실제 비교는 offer와 poll 안에서 힙이 자리를 잡을 때 실행된다.
        // 우선순위는 합 오름차순이고 동점이면 인덱스 오름차순이다.
        // 이 동점 규칙이 선형 스캔의 "합이 같으면 낮은 인덱스" 규칙과 배정 결과를 맞춘다.
        //
        // 주의: 비교 키가 밖에 있는 가변 배열 sums다. 힙은 큐 안에 있는 원소의 키가 바뀌는 것을 알아채지 못한다.
        // 그래서 꺼낸 뒤에 합을 고치고 다시 넣는 순서를 지켜야 정렬이 유지된다.
        Comparator<Integer> byTeamSumThenIndex = new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
//                countCompareV2++;   // [계수 실험 · 비활성] 다시 셀 때 이 줄만 주석 해제한다
                int bySum = Double.compare(sums[a], sums[b]);
                if (bySum != 0) {
                    return bySum;
                }
                return Integer.compare(a, b);
            }
        };

        // 큐에 담는 것은 팀 인덱스뿐이다. 합과 인원은 담지 않는다.
        // 큐에 있다는 것은 자리가 남은 배정 후보라는 뜻이고, 우선순위는 비교자가 sums를 읽어 정한다.
        PriorityQueue<Integer> pq = new PriorityQueue<>(byTeamSumThenIndex);

        // 전 팀을 후보로 등록한다. 시작은 모두 합이 0으로 동점이라 인덱스 규칙에 따라 팀 0이 최솟값이 된다.
        for (int t = 0; t < teamCount; t++) {
            pq.offer(t);
        }

        for (Member m : sorted) {
            // 합이 가장 작은 팀의 인덱스를 꺼낸다. 동점이면 인덱스가 낮은 쪽이다.
            // 선형 스캔에서 O(K)였던 안쪽 반복문이 이 한 줄 O(log K)로 바뀐 자리다.
            int best = pq.poll();

            teams.get(best).add(m);                     // 배정. best는 teams와 sums가 함께 쓰는 인덱스다
            sums[best] += m.skill().doubleValue();      // 합 갱신. 다음 비교부터 이 값을 읽는다

            // 다시 등록한다. 힙은 큐 안 원소의 키가 바뀐 것을 알아채지 못하므로,
            // 꺼낸 상태에서 합을 고친 뒤 되넣어 새 합 기준으로 재배치시킨다.
            // 자리가 찬 팀은 되넣지 않는다. 선형 스캔에서 꽉 찬 팀을 건너뛰던 것과 같은 효과다.
            if (teams.get(best).size() < teamCapacity) {
                pq.offer(best);
            }
        }
        return teams;
    }

    /* [버전 4 · 비활성] java.util.TreeMap을 그대로 쓰는 레드블랙 트리 판이다.
       실험 A와 B와 계수 측정을 2026-07-21에 마쳤다.

       직접 구현하지 않고 JDK의 레드블랙 트리 구현체인 TreeMap을 쓴다.
       키는 넣는 시점의 합과 팀 인덱스를 담은 TeamKey이고 값은 팀 인덱스다.
       합이 바뀌어도 트리 안의 키는 고치지 않는다. 꺼낸 뒤 새 합으로 키를 새로 만들어 다시 넣는다.
       키가 불변이라 버전 2에서 지켜야 했던 "큐 밖에서만 합을 바꾼다"는 주의가 여기서는 필요 없다.
       우선순위 규칙은 다른 버전과 같아 배정 결과도 같고 결정성도 유지된다.
       pollFirstEntry와 put 모두 O(log K)다. */

    /** [버전 4] TreeMap의 키. 넣는 시점의 팀 합과 팀 인덱스를 담는다. 불변이라 트리 안에서 값이 바뀌지 않는다. */
    private record TeamKey(double sum, int team) { }

//    List<List<Member>> seedGreedy(List<Member> sorted, int teamCount, int teamCapacity) {
//
//        List<List<Member>> teams = emptyTeams(teamCount);
//        double[] sums = new double[teamCount];          // 팀별 현재 합 (증분 유지) — 재삽입 키를 만들 때 읽는다
//
//        // [규칙 정의] 1순위 = 합 오름차순, 2순위(동점) = 인덱스 오름차순. 버전 2 비교자와 같은 규칙.
//        // 비교에 쓰는 값이 키 안에 저장된 값뿐이라(외부 배열 조회 없음) 트리 안 정렬은 항상 유효하다.
//        Comparator<TeamKey> bySumThenIndex = new Comparator<TeamKey>() {
//            @Override
//            public int compare(TeamKey a, TeamKey b) {
////                countCompareV4++;   // [계수 실험 임시 비활성] 재계수 시 이 줄만 주석 해제
//                int bySum = Double.compare(a.sum(), b.sum());
//                if (bySum != 0) {
//                    return bySum;
//                }
//                return Integer.compare(a.team(), b.team());
//            }
//        };
//
//        // "트리 안에 있다" = 정원이 남은 배정 후보. 한 팀은 트리에 최대 한 번만 들어 있으므로
//        // 키가 겹치는 일(compare == 0)은 없다 — put이 기존 항목을 덮어쓰는 경우는 발생하지 않는다.
//        TreeMap<TeamKey, Integer> tree = new TreeMap<>(bySumThenIndex);
//        for (int t = 0; t < teamCount; t++) {
//            tree.put(new TeamKey(0.0, t), t);   // 시작은 전부 합 0(동점) → 2차 규칙에 따라 team0 이 최솟값
//        }
//
//        for (Member m : sorted) {
//            // 최소 키(가장 왼쪽 노드)를 꺼낸다. 선형 스캔의 안쪽 for O(K)가 이 한 줄 O(log K)로 대체된다.
//            int best = tree.pollFirstEntry().getValue();
//
//            teams.get(best).add(m);                     // 팀 배정
//            sums[best] += m.skill().doubleValue();      // 팀 별 합 갱신
//
//            // 갱신된 합으로 키를 새로 만들어 재삽입. 정원이 찬 팀은 되넣지 않는다 (다른 버전과 동일한 규칙).
//            if (teams.get(best).size() < teamCapacity) {
//                tree.put(new TeamKey(sums[best], best), best);
//            }
//        }
//        return teams;
//    }


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

    /* [보존] fork-join 병렬화를 반영하기 전의 순차 improve()다. 아래 전체가 주석 상태다. */
//    private void improve(List<List<Member>> teams, double tolerance) {
//        while (true) {
//            /* ** 이번 스캔의 기준점: 교환 전 현재 상태의 두 편차 */
//            double currentSumDev = sumDeviation(teams);             // 합 편차 계산 : 현재 모든 팀인 "teams" 내 팀원 볼륨 합이 가능 높은 팀과 가장 낮은 팀간 빼서 계산을 한 결과.
//            double currentDistDev = distributionDeviation(teams);   // 분표 편차 계산 : 모든 팀에서 가장 편차가 높은 팀과 낮은 팀을 찾아서 분표 편차 값을 낸다(가장 낮은 팀과 높은 팀 식별 값 등은 별도로 저장하지 않고 결과 값만 계산)
//
//            // 현재 합 편차의 값이 이미 허용폭 안인가? → 후속 과정에서 팀원 간 교환 로직(swap()) 직후 합 필터의 판정 방향이 이 값에 따라 갈린다.
//            // (코드 378 줄 - boolean pass = withinCap ? newSumDev <= tolerance : newSumDev <= currentSumDev;)
//            boolean withinCap = currentSumDev <= tolerance;
//
//            // 이번 스캔의 "최선 교환 후보" 한 건 = 위치 · 값 · 키 3종.
//            // 후보를 훑다가 더 나은 교환이 나오면 아래 (3a)/(3b)에서 이 셋을 함께 갱신하고, 스캔이 끝나면 이 위치로 swap을 한 번 실행해 적용한다.
//
//            // 위치 — 최선 교환이 어느 두 자리였나: (bestTeamA,bestIdxA) ↔ (bestTeamB,bestIdxB).
//            //        -1 = 아직 없음. 스캔이 끝나도 -1이면 개선 교환이 하나도 없다는 뜻 → return.
//            int bestTeamA = -1, bestIdxA = -1, bestTeamB = -1, bestIdxB = -1;
//
//            // 분표 편차 값  — 그 최선 교환의 분포 편차(= 지금까지의 최소값). 초기값이 현재 상태값이라 첫 후보는 현재보다 낮아야 채택된다.
//            double bestNewDistDev = currentDistDev;
//
//            // 키  — 최선 교환에서 맞바꾼 두 참가자 ID를 (작은 값, 큰 값)으로 정렬한 것. 분포 편차가 동률이면 이 키가 사전순으로 앞선 후보를 채택(결정성).
//            String bestKeyFirst = null, bestKeySecond = null;
//
//            /* ** 현재 로직이 모든 팀들 간 모든 팀 인원들을 비교해서 팀들 간 인원 교환 후보군을 추리는 단계. */
//            for (int a = 0; a < teams.size(); a++) {            // 팀 a : 현재 탐색 시작 기준 팀 위치 - 이 팀을 기준으로 다른 모든 팀들을 하나하나 선택해 가면서 모든 인원 조합을 고려
//                for (int b = a + 1; b < teams.size(); b++) {    // 팀 b (a<b → 같은 짝 중복 방지) : 팀 a 위치를 기준점으로 대조 비교할 모든 팀을 하나하나 점진적으로 선택해 나가는 위치.
//                    for (int i = 0; i < teams.get(a).size(); i++) {      // a의 i번째 사람
//                            for (int j = 0; j < teams.get(b).size(); j++) {  // b의 j번째 사람
//
//                            swap(teams, a, i, b, j);            // (1) 후보 교환을 실제로 적용해 본다.
//
//                            double newSumDev = sumDeviation(teams);      // 교환 후 합 편차를 재 계산 한다.(모든 팀을 대상으로 재계산)
//
//                            /* (2) 합 필터 — 제약(합 균형)을 깨는 교환을 걸러낸다. 통과한 후보만 아래 분포 편차 계산으로 넘어간다.
//                                 판정 기준은 교환 전 상태(withinCap)에 따라 두 갈래로 나뉜다:
//                                   1. withinCap == true  (교환 전 이미 허용폭 안): 교환 후에도 허용폭 이내여야 통과. (newSumDev ≤ tolerance)
//                                   2. withinCap == false (교환 전 허용폭 밖, 이상치 구성이라 못 지킴): 교환 후 합 편차가 교환 직전(currentSumDev)보다 나빠지지만 않으면 통과. (newSumDev ≤ currentSumDev)
//
//                                 이 기준의 의미는 교환 전에 합 편차가 상태가 기준 선 이내 였다면 당연히 교환 후 합 편차도 기준 선 이내여야 한다는 기준이며,
//                                 만약 교환 전 합 편차가 기준 선 이내가 아니였다면 교환 후의 합 편차는 교환 전 보다는 개선 된 값이어야 한다는 기준.
//                            */
//                            boolean pass = withinCap ? newSumDev <= tolerance : newSumDev <= currentSumDev;
//
//                            /* 현재 교환 완료 하였고 이것이 분표 편차를 갱신하기 적합하다면 (pass == true) 후속 과정 진행. */
//                            if (pass) {
//
//                                double newDistDev = distributionDeviation(teams);  // 앞서 교환 과정(swap()) 이후 과정으로써 분포 편차를 재 계산 한다. (모든 팀을 대상으로 재계산)
//
//                                /* 교환 과정 이전의 분포 편차(bestNewDistDev) 와 현재 교환 과정(swap()) 으로 변경된 분포 편차(newDistDev)을 비교 한 결과에 따라 분기하여 로직 수행 */
//
//                                // 1. 교환 과정(swap()) 으로 분포 편차가 줄어들었음으로 실제 교환 과정 수행.
//                                if (newDistDev < bestNewDistDev - 1e-12) {
//
//                                    // (3a) 분포 편차를 "엄격히" 더 줄이는 교환 → 새 최선으로 채택
//                                    bestNewDistDev = newDistDev;
//                                    bestTeamA = a; bestIdxA = i; bestTeamB = b; bestIdxB = j;
//
//                                    // 교환된 상태 기준 두 사람의 ID를 (작은 것, 큰 것) 순서로 기록 → 동률 비교 키
//                                    String idA = teams.get(a).get(i).participationId();
//                                    String idB = teams.get(b).get(j).participationId();
//
//                                    bestKeyFirst = idA.compareTo(idB) <= 0 ? idA : idB;
//                                    bestKeySecond = idA.compareTo(idB) <= 0 ? idB : idA;
//
//                                }
//
//                                // 2. 교환 과정(swap()) 으로 분포 편차가 감소량이 사실상 동률 ±1e-12) → 참가자 ID가 사전순으로 앞선 짝을 선택 한다. (이 규칙이 있어야 "어떤 순서로 훑든 같은 교환을 고른다"는 결정성이 성립. (8.1.4))
//                                else if (Math.abs(newDistDev - bestNewDistDev) <= 1e-12 && bestTeamA != -1) {
//
//                                    String idA = teams.get(a).get(i).participationId();
//                                    String idB = teams.get(b).get(j).participationId();
//                                    String first = idA.compareTo(idB) <= 0 ? idA : idB;
//                                    String second = idA.compareTo(idB) <= 0 ? idB : idA;
//
//                                    if (first.compareTo(bestKeyFirst) < 0 || (first.equals(bestKeyFirst) && second.compareTo(bestKeySecond) < 0)) {
//                                        bestTeamA = a; bestIdxA = i; bestTeamB = b; bestIdxB = j;
//                                        bestKeyFirst = first; bestKeySecond = second;
//                                    }
//                                }
//                            }
//
//                            // 3. 분포 편차가 개선되지 않았다면 실제 교환 로직은 수행 되지 않음.
//
//                            // 4.원상 복구 — 후보 평가는 "가정"일 뿐이므로 되돌린다.
//                            swap(teams, a, i, b, j);
//                        }
//                    }
//                }
//            }
//
//            if (bestTeamA == -1) {
//                return;   // 이번 스캔에서 분포 편차를 줄이는 교환이 하나도 없었다 → 수렴, 교환 로직을 완전 종료
//            }
//            swap(teams, bestTeamA, bestIdxA, bestTeamB, bestIdxB);  // 최선 교환 1건만 확정 적용 후 다시 스캔
//        }
//    }

    /** [동일성 검증용 · 임시] 증분 재계산 전환 전의 순차 구현이다.
     *  평가마다 sumDeviation·distributionDeviation으로 팀 전체를 다시 훑는다.
     *  바꾸기 전 구현과 바꾼 뒤 구현을 맞대는 대조에만 쓴다. 검증이 끝나면 제거 후보다. */
    void improveSequential_backup(List<List<Member>> teams, double tolerance) {
        while (true) {
            /* ** 이번 스캔의 기준점: 교환 전 현재 상태의 두 편차 */
            double currentSumDev = sumDeviation(teams);             // 합 편차 계산 : 현재 모든 팀인 "teams" 내 팀원 볼륨 합이 가능 높은 팀과 가장 낮은 팀간 빼서 계산을 한 결과.
            double currentDistDev = distributionDeviation(teams);   // 분표 편차 계산 : 모든 팀에서 가장 편차가 높은 팀과 낮은 팀을 찾아서 분표 편차 값을 낸다(가장 낮은 팀과 높은 팀 식별 값 등은 별도로 저장하지 않고 결과 값만 계산)

            // 현재 합 편차의 값이 이미 허용폭 안인가? → 후속 과정에서 팀원 간 교환 로직(swap()) 직후 합 필터의 판정 방향이 이 값에 따라 갈린다.
            // (코드 378 줄 - boolean pass = withinCap ? newSumDev <= tolerance : newSumDev <= currentSumDev;)
            boolean withinCap = currentSumDev <= tolerance;

            // 이번 스캔의 "최선 교환 후보" 한 건 = 위치 · 값 · 키 3종.
            // 후보를 훑다가 더 나은 교환이 나오면 아래 (3a)/(3b)에서 이 셋을 함께 갱신하고, 스캔이 끝나면 이 위치로 swap을 한 번 실행해 적용한다.

            // 위치 — 최선 교환이 어느 두 자리였나: (bestTeamA,bestIdxA) ↔ (bestTeamB,bestIdxB).
            //        -1 = 아직 없음. 스캔이 끝나도 -1이면 개선 교환이 하나도 없다는 뜻 → return.
            int bestTeamA = -1, bestIdxA = -1, bestTeamB = -1, bestIdxB = -1;

            // 분표 편차 값  — 그 최선 교환의 분포 편차(= 지금까지의 최소값). 초기값이 현재 상태값이라 첫 후보는 현재보다 낮아야 채택된다.
            double bestNewDistDev = currentDistDev;

            // 키  — 최선 교환에서 맞바꾼 두 참가자 ID를 (작은 값, 큰 값)으로 정렬한 것. 분포 편차가 동률이면 이 키가 사전순으로 앞선 후보를 채택(결정성).
            String bestKeyFirst = null, bestKeySecond = null;

            /* ** 현재 로직이 모든 팀들 간 모든 팀 인원들을 비교해서 팀들 간 인원 교환 후보군을 추리는 단계. */
            for (int a = 0; a < teams.size(); a++) {            // 팀 a : 현재 탐색 시작 기준 팀 위치 - 이 팀을 기준으로 다른 모든 팀들을 하나하나 선택해 가면서 모든 인원 조합을 고려
                for (int b = a + 1; b < teams.size(); b++) {    // 팀 b (a<b → 같은 짝 중복 방지) : 팀 a 위치를 기준점으로 대조 비교할 모든 팀을 하나하나 점진적으로 선택해 나가는 위치.
                    for (int i = 0; i < teams.get(a).size(); i++) {      // a의 i번째 사람
                            for (int j = 0; j < teams.get(b).size(); j++) {  // b의 j번째 사람

                            swap(teams, a, i, b, j);            // (1) 후보 교환을 실제로 적용해 본다.

                            double newSumDev = sumDeviation(teams);      // 교환 후 합 편차를 재 계산 한다.(모든 팀을 대상으로 재계산)

                            /* (2) 합 필터 — 제약(합 균형)을 깨는 교환을 걸러낸다. 통과한 후보만 아래 분포 편차 계산으로 넘어간다.
                                 판정 기준은 교환 전 상태(withinCap)에 따라 두 갈래로 나뉜다:
                                   1. withinCap == true  (교환 전 이미 허용폭 안): 교환 후에도 허용폭 이내여야 통과. (newSumDev ≤ tolerance)
                                   2. withinCap == false (교환 전 허용폭 밖, 이상치 구성이라 못 지킴): 교환 후 합 편차가 교환 직전(currentSumDev)보다 나빠지지만 않으면 통과. (newSumDev ≤ currentSumDev)

                                 이 기준의 의미는 교환 전에 합 편차가 상태가 기준 선 이내 였다면 당연히 교환 후 합 편차도 기준 선 이내여야 한다는 기준이며,
                                 만약 교환 전 합 편차가 기준 선 이내가 아니였다면 교환 후의 합 편차는 교환 전 보다는 개선 된 값이어야 한다는 기준.
                            */
                            boolean pass = withinCap ? newSumDev <= tolerance : newSumDev <= currentSumDev;

                            /* 현재 교환 완료 하였고 이것이 분표 편차를 갱신하기 적합하다면 (pass == true) 후속 과정 진행. */
                            if (pass) {

                                double newDistDev = distributionDeviation(teams);  // 앞서 교환 과정(swap()) 이후 과정으로써 분포 편차를 재 계산 한다. (모든 팀을 대상으로 재계산)

                                /* 교환 과정 이전의 분포 편차(bestNewDistDev) 와 현재 교환 과정(swap()) 으로 변경된 분포 편차(newDistDev)을 비교 한 결과에 따라 분기하여 로직 수행 */

                                // 1. 교환 과정(swap()) 으로 분포 편차가 줄어들었음으로 실제 교환 과정 수행.
                                if (newDistDev < bestNewDistDev - 1e-12) {

                                    // (3a) 분포 편차를 "엄격히" 더 줄이는 교환 → 새 최선으로 채택
                                    bestNewDistDev = newDistDev;
                                    bestTeamA = a; bestIdxA = i; bestTeamB = b; bestIdxB = j;

                                    // 교환된 상태 기준 두 사람의 ID를 (작은 것, 큰 것) 순서로 기록 → 동률 비교 키
                                    String idA = teams.get(a).get(i).participationId();
                                    String idB = teams.get(b).get(j).participationId();

                                    bestKeyFirst = idA.compareTo(idB) <= 0 ? idA : idB;
                                    bestKeySecond = idA.compareTo(idB) <= 0 ? idB : idA;

                                }

                                // 2. 교환 과정(swap()) 으로 분포 편차가 감소량이 사실상 동률 ±1e-12) → 참가자 ID가 사전순으로 앞선 짝을 선택 한다. (이 규칙이 있어야 "어떤 순서로 훑든 같은 교환을 고른다"는 결정성이 성립. (8.1.4))
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

                            // 3. 분포 편차가 개선되지 않았다면 실제 교환 로직은 수행 되지 않음.

                            // 4.원상 복구 — 후보 평가는 "가정"일 뿐이므로 되돌린다.
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

            // [보존] 증분 재계산 전환 전의 스캔 기준값 두 줄이다.
            //    되돌릴 때는 위 buildTeamStats 호출과 두 줄을 지우고 아래 두 줄의 앞 // 를 뗀다.
            //    검증 이력: ImproveEquivalenceCheck 40케이스 통과(2026-08-13). 대조 셋 200건 전부
            //               배정 불일치 0건, 최종 두 편차 값 차이 0건.
            //
            //  double currentSumDev = sumDeviation(teams);
            //  double currentDistDev = distributionDeviation(teams);

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

    // [보존] 증분 재계산 전환 전의 순차 평가 몸통이다.
    //    되돌릴 때는 위 4중 루프 안쪽 몸통을 지우고 아래 줄들의 앞 // 를 떼어 넣는다.
    //    함께 되돌릴 것: 배열 셋 생성과 스캔 기준값 자리의 buildTeamStats 호출.
    //    검증 이력: ImproveEquivalenceCheck 40케이스 통과(2026-08-13). 대조 셋 200건 전부
    //               배정 불일치 0건, 최종 두 편차 값 차이 0건.
    //
//                              for (int j = 0; j < teams.get(b).size(); j++) {  // b의 j번째 사람
//
//                              swap(teams, a, i, b, j);            // (1) 후보 교환을 실제로 적용해 본다.
//
//                              double newSumDev = sumDeviation(teams);      // 교환 후 합 편차를 재 계산 한다.(모든 팀을 대상으로 재계산)
//
//                              /* (2) 합 필터 — 제약(합 균형)을 깨는 교환을 걸러낸다. 통과한 후보만 아래 분포 편차 계산으로 넘어간다.
//                                   판정 기준은 교환 전 상태(withinCap)에 따라 두 갈래로 나뉜다:
//                                     1. withinCap == true  (교환 전 이미 허용폭 안): 교환 후에도 허용폭 이내여야 통과. (newSumDev ≤ tolerance)
//                                     2. withinCap == false (교환 전 허용폭 밖, 이상치 구성이라 못 지킴): 교환 후 합 편차가 교환 직전(currentSumDev)보다 나빠지지만 않으면 통과. (newSumDev ≤ currentSumDev)
//
//                                   이 기준의 의미는 교환 전에 합 편차가 상태가 기준 선 이내 였다면 당연히 교환 후 합 편차도 기준 선 이내여야 한다는 기준이며,
//                                   만약 교환 전 합 편차가 기준 선 이내가 아니였다면 교환 후의 합 편차는 교환 전 보다는 개선 된 값이어야 한다는 기준.
//                              */
//                              boolean pass = withinCap ? newSumDev <= tolerance : newSumDev <= currentSumDev;
//
//                              /* 현재 교환 완료 하였고 이것이 분표 편차를 갱신하기 적합하다면 (pass == true) 후속 과정 진행. */
//                              if (pass) {
//
//                                  double newDistDev = distributionDeviation(teams);  // 앞서 교환 과정(swap()) 이후 과정으로써 분포 편차를 재 계산 한다. (모든 팀을 대상으로 재계산)
//
//                                  /* 교환 과정 이전의 분포 편차(bestNewDistDev) 와 현재 교환 과정(swap()) 으로 변경된 분포 편차(newDistDev)을 비교 한 결과에 따라 분기하여 로직 수행 */
//
//                                  // 1. 교환 과정(swap()) 으로 분포 편차가 줄어들었음으로 실제 교환 과정 수행.
//                                  if (newDistDev < bestNewDistDev - 1e-12) {
//
//                                      // (3a) 분포 편차를 "엄격히" 더 줄이는 교환 → 새 최선으로 채택
//                                      bestNewDistDev = newDistDev;
//                                      bestTeamA = a; bestIdxA = i; bestTeamB = b; bestIdxB = j;
//
//                                      // 교환된 상태 기준 두 사람의 ID를 (작은 것, 큰 것) 순서로 기록 → 동률 비교 키
//                                      String idA = teams.get(a).get(i).participationId();
//                                      String idB = teams.get(b).get(j).participationId();
//
//                                      bestKeyFirst = idA.compareTo(idB) <= 0 ? idA : idB;
//                                      bestKeySecond = idA.compareTo(idB) <= 0 ? idB : idA;
//
//                                  }
//
//                                  // 2. 교환 과정(swap()) 으로 분포 편차가 감소량이 사실상 동률 ±1e-12) → 참가자 ID가 사전순으로 앞선 짝을 선택 한다. (이 규칙이 있어야 "어떤 순서로 훑든 같은 교환을 고른다"는 결정성이 성립. (8.1.4))
//                                  else if (Math.abs(newDistDev - bestNewDistDev) <= 1e-12 && bestTeamA != -1) {
//
//                                      String idA = teams.get(a).get(i).participationId();
//                                      String idB = teams.get(b).get(j).participationId();
//                                      String first = idA.compareTo(idB) <= 0 ? idA : idB;
//                                      String second = idA.compareTo(idB) <= 0 ? idB : idA;
//
//                                      if (first.compareTo(bestKeyFirst) < 0 || (first.equals(bestKeyFirst) && second.compareTo(bestKeySecond) < 0)) {
//                                          bestTeamA = a; bestIdxA = i; bestTeamB = b; bestIdxB = j;
//                                          bestKeyFirst = first; bestKeySecond = second;
//                                      }
//                                  }
//                              }
//
//                              // 3. 분포 편차가 개선되지 않았다면 실제 교환 로직은 수행 되지 않음.
//
//                              // 4.원상 복구 — 후보 평가는 "가정"일 뿐이므로 되돌린다.
//                              swap(teams, a, i, b, j);
//                          }

    /* ===== fork-join 병렬 구간 ===== */

    /**
     * 교환 반복을 실행한다. 운영 경로의 진입점이다.
     *
     * <p>풀 병렬도는 논리 프로세서 수로 둔다.
     *
     * @param teams     팀 편성 대상 전체. 이 목록을 제자리에서 고친다
     * @param tolerance 합 편차 허용폭
     */
    void improve(List<List<Member>> teams, double tolerance) {
        improve(teams, tolerance, Runtime.getRuntime().availableProcessors());
    }

    /**
     * 풀 병렬도만 밖에서 받는 오버로드다. 스레드 수 스윕에 쓴다.
     *
     * <p>분할 중단 크기는 병렬도가 아니라 논리 프로세서 수를 기준으로 여기서 계산한다.
     * 운영 경로는 인자 두 개짜리만 부르므로 동작이 달라지지 않는다.
     *
     * @param teams       팀 편성 대상 전체
     * @param tolerance   합 편차 허용폭
     * @param parallelism 풀에 둘 워커 스레드 수
     */
    void improve(List<List<Member>> teams, double tolerance, int parallelism) {

        // 분할 중단 크기의 계산 기준은 논리 프로세서 수로 고정한다. 풀 병렬도와는 무관하다.
        int cpuCount = Runtime.getRuntime().availableProcessors();

        int teamCount = teams.size();                                    // 팀 수
        int pairCount = teamCount * (teamCount - 1) / 2;                 // 팀 짝 수

        // 짝 수를 논리 프로세서 수로 나눈다. max(1, …)는 짝 수가 프로세서 수보다 작을 때 0이 되는 것을 막는다.
        int workSize = Math.max(1, pairCount / cpuCount);

        improve(teams, tolerance, parallelism, workSize);
    }

    /**
     * 풀 병렬도와 분할 중단 크기를 둘 다 밖에서 받는 오버로드다. workSize 스윕에 쓴다.
     *
     * <p>분할 중단 크기는 말단 작업 하나가 맡는 짝 개수다. 담당 구간이 이 값보다 길면 반으로 나눈다.
     * 이 값이 작을수록 말단 작업 수와 팀 배정 복사 횟수가 늘어난다.
     * 운영 경로는 이 오버로드를 부르지 않는다.
     *
     * @param teams       팀 편성 대상 전체
     * @param tolerance   합 편차 허용폭
     * @param parallelism 풀에 둘 워커 스레드 수
     * @param workSize    말단 작업 하나가 맡는 짝 개수
     */
    void improve(List<List<Member>> teams, double tolerance, int parallelism, int workSize) {
        improve(teams, tolerance, parallelism, workSize, 0);   // splitCount가 0이면 workSize 규칙으로 나눈다
    }

    /**
     * 말단 작업을 몇 개로 만들지 직접 지정하는 경로다. 분할 개수 스윕에 쓴다.
     *
     * <p>짝 구간을 목표 개수만큼 나누므로 실제 말단 작업 수가 이 값과 같아진다.
     * 프로브(ImproveCpuUtilizationProbe)의 분할 개수 스윕만 이 경로를 부른다.
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
     * 교환 반복의 몸통이다. 위 오버로드 넷이 모두 이 메서드로 모인다.
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

        /* [상세 기록] 이 호출의 병렬도와 분할을 정적 필드에 실어 두면 말단이 읽어 간다. */
        detailLogParallelism = parallelism;
        detailLogSplitCount = splitCount;
        int detailScanNo = 0;   // [상세 기록] 스캔 번호. while 바퀴마다 1씩 는다

        while (true) {
            long detailScanStartEpochMs = System.currentTimeMillis();   // [상세 기록] 스캔 시작 시각. 클럭 CSV와 맞추는 데 쓴다
            long detailScanStartNs = System.nanoTime();                 // [상세 기록] 스캔 한 바퀴의 시작 시각
            detailScanNo++;                                             // [상세 기록]
            detailLogScan = detailScanNo;                               // [상세 기록] 이번 스캔의 말단 레코드가 읽는 번호

            // 이번 스캔의 기준값이다. 교환 전 상태의 두 편차를 낸다.
            // 앞 바퀴에서 교환 1건이 적용되었으므로 배열을 다시 채우고 거기서 뽑는다.
            buildTeamStats(teams, teamSum, teamSqSum, teamSize);
            double currentSumDev = sumDeviationOf(teamSum);
            double currentDistDev = distributionDeviationOf(teamSum, teamSqSum, teamSize);

            // [보존] 증분 재계산 전환 전의 스캔 기준값 두 줄이다.
            //    되돌릴 때는 위 buildTeamStats 호출과 두 줄을 지우고 아래 두 줄의 앞 // 를 뗀다.
            //    검증 이력: ImproveEquivalenceCheck 40케이스 통과(2026-08-13). 대조 셋 200건 전부
            //               배정 불일치 0건, 최종 두 편차 값 차이 0건.
            //
            //  double currentSumDev = sumDeviation(teams);
            //  double currentDistDev = distributionDeviation(teams);

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
                /* [상세 기록] 개선이 없어 끝나는 마지막 스캔을 기록한다. 바퀴 시작부터 여기까지다. */
                if (detailLogEnabled) {
                    detailLog.add("IMPLOG,scan,main," + parallelism + "," + splitCount + ","
                            + detailLogRunIndex + "," + detailScanNo + "," + (System.nanoTime() - detailScanStartNs)
                            + "," + detailScanStartEpochMs);
                }
                /* [활성] 이 호출에서 워커가 남의 일을 가져간 횟수다. 풀이 세어 둔 값을 읽기만 한다.
                   2026-08-13 병렬도 12 분할 18에서 스캔 225바퀴 중 여덟에서 열한 바퀴가 워커 하나로만 돌았다.
                   일감이 실제로 안 옮겨 간 것인지 가르려고 켠다. 확인이 끝나면 다시 주석 처리한다. */
//                System.out.println("[임시 계측 · work-stealing] getStealCount=" + pool.getStealCount());
                // [계측 · 활성 설정] 아래 pool.shutdown()을 주석 상태로 둔다.
                // 프로브가 호출 뒤에 워커별 CPU를 읽으려면 워커가 살아 있어야 하기 때문이다.
                // 측정이 모두 끝나면 이 줄을 되살린다.
//                pool.shutdown();
                return;   // 개선 교환이 하나도 없었다. 수렴으로 보고 끝낸다
            }
            swap(teams, result.bestTeamA, result.bestIdxA, result.bestTeamB, result.bestIdxB);   // 최선 교환 한 건만 적용하고 다시 스캔한다

            /* [상세 기록] 스캔 한 바퀴를 기록한다. 기준값 계산부터 최선 교환 적용까지다. */
            if (detailLogEnabled) {
                detailLog.add("IMPLOG,scan,main," + parallelism + "," + splitCount + ","
                        + detailLogRunIndex + "," + detailScanNo + "," + (System.nanoTime() - detailScanStartNs)
                        + "," + detailScanStartEpochMs);
            }

//            [보존] 순차 구현의 적용과 종료 판단이다.
//            if (bestTeamA == -1) {
//                return;   // 이번 스캔에서 분포 편차를 줄이는 교환이 하나도 없었다 → 수렴, 교환 로직을 완전 종료
//            }
//            swap(teams, bestTeamA, bestIdxA, bestTeamB, bestIdxB);  // 최선 교환 1건만 확정 적용 후 다시 스캔
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

            long dlogStartEpochMs = System.currentTimeMillis();   // [상세 기록] 말단 시작 시각. 클럭 CSV와 맞추는 데 쓴다
            long dlogCopyStartNs = System.nanoTime();             // [상세 기록] 복사 시작 시각. 아래 계측과 별도로 읽는다

            long copyStartNs = System.nanoTime();   // [계측] 복사 블록 직전 시각

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

            long localCopyNs = System.nanoTime() - copyStartNs;      // [계측] 복사에 걸린 시간(ns)
            long dlogCopyNs = System.nanoTime() - dlogCopyStartNs;   // [상세 기록] 복사 시간. 위 계측의 시각 읽기 두 번을 안쪽에 포함한다

            double bestNewDistDev = currentDistDev;   // 이 말단의 최선 값. 스캔 시작 시점 값에서 출발한다

            ImproveTaskResult improveTaskResult = new ImproveTaskResult();

            /* [상세 기록] 말단 레코드에 쓸 지역 변수다. 안쪽 반복문에서는 지역 변수에만 더하고
               공유 버퍼에는 말단이 끝날 때 한 번만 넣는다. 반복문 안에서 공유 저장소를 건드리면
               워커 사이에 없던 경쟁이 생겨 재려는 현상 자체가 달라지기 때문이다.
               기록 여부와 스캔 번호는 말단이 시작한 시점의 값으로 고정한다. */
            boolean dlogOn = detailLogEnabled;            // 기록 여부
            int dlogScanNo = detailLogScan;               // 이 말단이 속한 스캔 번호
            long dlogSumDevCount = 0, dlogSumDevNs = 0;   // 합 편차 계산의 호출 횟수와 누적 시간(ns)
            long dlogDistDevCount = 0, dlogDistDevNs = 0; // 분포 편차 계산의 호출 횟수와 누적 시간(ns)
            long dlogLoopStartNs = System.nanoTime();     // 4중 루프 시각 — 기존 계측과 별도로 읽는다

            /* [계측] 4중 루프 시간의 시작 시각과, 평가 횟수와 통과 횟수를 세는 지역 변수다.
               가장 안쪽 반복문에서는 지역 변수에만 1씩 더하고, 정적 카운터 합산은 말단이 끝날 때 한 번만 한다. */
            long evalLoopStartNs = System.nanoTime();   // [계측] 4중 루프 직전 시각
            long localEvalCount = 0;                    // [계측] 평가 횟수
            long localPassCount = 0;                    // [계측] 통과 횟수

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

                            long dlogT0 = System.nanoTime();                 // [상세 기록] 합 편차 계산 직전 시각
                            double newSumDev = sumDeviationOf(localSum);      // 교환 후 합 편차. 팀 안의 인원은 훑지 않고 배열만 훑는다
                            dlogSumDevNs += System.nanoTime() - dlogT0;      // [상세 기록] 합 편차 계산 시간 누적
                            dlogSumDevCount++;                               // [상세 기록] 합 편차 계산 호출 횟수

                            localEvalCount++;   // [계측] 평가 1회

                            /* 합 필터. 합 균형이라는 제약을 깨는 교환을 걸러낸다.
                               withinCap이면 교환 후에도 허용폭 안이어야 통과하고,
                               아니면 교환 전보다 나빠지지만 않으면 통과한다. */
                            boolean pass = withinCap ? newSumDev <= tolerance : newSumDev <= currentSumDev;

                            if (pass) {

                                long dlogT1 = System.nanoTime();                       // [상세 기록] 분포 편차 계산 직전 시각
                                double newDistDev = distributionDeviationOf(localSum, localSqSum, localSize);  // 교환 후 분포 편차. 여기서도 배열만 훑는다
                                dlogDistDevNs += System.nanoTime() - dlogT1;           // [상세 기록] 분포 편차 계산 시간 누적
                                dlogDistDevCount++;                                    // [상세 기록] 분포 편차 계산 호출 횟수

                                localPassCount++;   // [계측] 합 필터를 통과한 평가 1회

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

            long localEvalLoopNs = System.nanoTime() - evalLoopStartNs;   // [계측] 4중 루프 전체에 걸린 시간(ns)
            long dlogLoopNs = System.nanoTime() - dlogLoopStartNs;        // [상세 기록] 4중 루프 시간. 위 계측 구간을 안쪽에 포함한다

            /* [계측] 새로 재는 것은 없다. 지역 변수 넷을 정적 카운터 넷에 옮겨 더하기만 한다. */
            evalCount.addAndGet(localEvalCount);
            passCount.addAndGet(localPassCount);
            copyTimeNs.addAndGet(localCopyNs);
            evalLoopTimeNs.addAndGet(localEvalLoopNs);

            /* [상세 기록] 말단 레코드 한 건을 버퍼에 넣는다. 출력은 프로브가 호출이 끝난 뒤에 한다. */
            if (dlogOn) {
                detailLog.add("IMPLOG,leaf," + Thread.currentThread().getName()
                        + "," + detailLogParallelism + "," + detailLogSplitCount
                        + "," + detailLogRunIndex + "," + dlogScanNo
                        + "," + dlogCopyNs + "," + dlogLoopNs
                        + "," + dlogSumDevCount + "," + dlogSumDevNs
                        + "," + dlogDistDevCount + "," + dlogDistDevNs
                        + "," + dlogStartEpochMs);
            }

            /* 순차 구현과 다른 점이다. 순차는 스캔이 끝난 이 지점에서 바로 적용과 종료를 판단했지만,
               이 작업은 부분 결과를 만들어 반환만 한다.
               적용과 종료 판단은 병합을 거친 최종 결과를 받는 최상위 호출부가 한다. */

            improveTaskResult.bestNewDistDev = bestNewDistDev;

            return improveTaskResult;
        } // compute() 끝

        // [보존] 증분 재계산 전환 전의 말단 평가 몸통이다.
        //    되돌릴 때는 위 4중 루프 안쪽 몸통을 지우고 아래 줄들의 앞 // 를 떼어 넣는다.
        //    함께 되돌릴 것: 필드 셋(teamSum·teamSqSum·teamSize), 생성자 매개변수 셋,
        //    하위 작업 둘에 넘기는 인자 셋, 복사 자리의 localSum·localSqSum·localSize.
        //    검증 이력: ImproveEquivalenceCheck 40케이스 통과(2026-08-13). 대조 셋 200건 전부
        //               배정 불일치 0건, 최종 두 편차 값 차이 0건.
        //
//                          // ← 몸통(swap → 합 필터 → (3a)/(3b) → 복구)은 한 글자도 안 바뀜
//
//                              swap(local, a, i, b, j);            // (1) 후보 교환을 적용해 본다
//
//                              long dlogT0 = System.nanoTime();                 // [상세 로그 · 임시] sumDeviation 앞 시각
//                              double newSumDev = sumDeviation(local);      // 교환 후 합 편차 재계산
//                              dlogSumDevNs += System.nanoTime() - dlogT0;      // [상세 로그 · 임시] sumDeviation 시간 누적
//                              dlogSumDevCount++;                               // [상세 로그 · 임시] sumDeviation 횟수
//
//                              localEvalCount++;   // [4.1 계측 3 · 임시] 평가 1회 실행됨
//
//                              /* (2) 합 필터 — 제약(합 균형)을 깨는 교환을 걸러낸다.
//                                 withinCap이면 교환 후에도 허용폭 이내여야 통과,
//                                 아니면 교환 전(currentSumDev)보다 나빠지지 않아야 통과. */
//                              boolean pass = withinCap ? newSumDev <= tolerance : newSumDev <= currentSumDev;
//
//                              if (pass) {
//
//                                  long dlogT1 = System.nanoTime();                       // [상세 로그 · 임시] distributionDeviation 앞 시각
//                                  double newDistDev = distributionDeviation(local);  // 교환 후 분포 편차 재계산
//                                  dlogDistDevNs += System.nanoTime() - dlogT1;           // [상세 로그 · 임시] distributionDeviation 시간 누적
//                                  dlogDistDevCount++;                                    // [상세 로그 · 임시] distributionDeviation 횟수
//
//                                  localPassCount++;   // [4.1 계측 4 · 임시] 합 필터를 통과한 평가 1회
//
//                                  // (3a) 엄격한 개선(1e-12 초과) — best의 값·위치·키를 모두 갱신
//                                  if (newDistDev < bestNewDistDev - 1e-12) {
//
//                                      bestNewDistDev = newDistDev;
//
//                                      improveTaskResult.bestTeamA = a; improveTaskResult.bestIdxA = i;
//                                      improveTaskResult.bestTeamB = b; improveTaskResult.bestIdxB = j;
//
//                                      // 동률 비교용 키 — 맞바꾼 두 참가자 ID를 (작은 것, 큰 것) 순서로 기록
//                                      String idA = local.get(a).get(i).participationId();
//                                      String idB = local.get(b).get(j).participationId();
//
//                                      improveTaskResult.bestKeyFirst = idA.compareTo(idB) <= 0 ? idA : idB;
//                                      improveTaskResult.bestKeySecond = idA.compareTo(idB) <= 0 ? idB : idA;
//
//                                  }
//
//                                  // (3b) 동률(±1e-12 이내) — ID 키가 사전순으로 앞설 때만 위치·키를 갱신 (결정성 규칙)
//                                  else if (Math.abs(newDistDev - bestNewDistDev) <= 1e-12 && improveTaskResult.bestTeamA != -1) {
//
//                                      String idA = local.get(a).get(i).participationId();
//                                      String idB = local.get(b).get(j).participationId();
//                                      String first = idA.compareTo(idB) <= 0 ? idA : idB;
//                                      String second = idA.compareTo(idB) <= 0 ? idB : idA;
//
//                                      if (first.compareTo(improveTaskResult.bestKeyFirst) < 0 || (first.equals(improveTaskResult.bestKeyFirst) && second.compareTo(improveTaskResult.bestKeySecond) < 0)) {
//                                          improveTaskResult.bestTeamA = a; improveTaskResult.bestIdxA = i; improveTaskResult.bestTeamB = b; improveTaskResult.bestIdxB = j;
//                                          improveTaskResult.bestKeyFirst = first; improveTaskResult.bestKeySecond = second;
//                                      }
//                                  }
//                              }
//
//                              // (4) 원상 복구 — 후보 평가는 가정일 뿐이므로 되돌린다
//                              swap(local, a, i, b, j);
//                          }
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

    /* [보존] for문 판 sumDeviation이다. 2026-08-04 할당 제거 대조에 썼던 명령형 for문 판. 원인 추적을 잇기 위해 2026-08-05 스트림판으로 복귀했다.
       다시 for판으로 바꿀 때는 위 for (List<Member> team : teams) 몸통 세 줄을 지우고 아래 블록을 넣는다.
       주의: DoubleStream.sum()은 단순 누적이 아니라 보정 항을 함께 굴리는 합이다(Kahan 방식).
       아래 블록은 그 보정 계산까지 그대로 옮긴 판이고, 마지막 단계는 sum + comp가 아니라 sum - comp다(부호까지 실측으로 확인).
       비트 동일성 검증 완료: 배정 상태 80,000가지 불일치 0건, ImproveEquivalenceCheck 40케이스 통과(2026-08-04).

            double sum = 0.0;       // 보정을 반영한 누적 합
            double comp = 0.0;      // 직전 덧셈에서 어긋난 몫. 다음 값을 더하기 전에 빼 주고 마지막에도 빼 준다
            double simple = 0.0;    // 보정 없는 단순 합. 아래 무한대 판정에만 쓴다

            for (int i = 0; i < team.size(); i++) {
                double v = team.get(i).skill().doubleValue();   // 스트림의 mapToDouble과 같은 변환
                double adjusted = v - comp;                     // 직전 덧셈에서 어긋난 몫을 단다
                double next = sum + adjusted;
                comp = (next - sum) - adjusted;                 // 이번 덧셈에서 반올림으로 어긋난 몫
                sum = next;
                simple += v;
            }

            double total = sum - comp;                          // 스트림의 마무리 계산과 같다. 부호까지 맞춰야 비트가 같다(실측으로 확인)
            if (Double.isNaN(total) && Double.isInfinite(simple)) {
                total = simple;                                 // 보정 합이 NaN이고 단순 합이 무한대면 단순 합을 쓴다
            }

            max = Math.max(max, total);
            min = Math.min(min, total);
    */

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

    /* [보존] for문 판 distributionDeviation이다. 2026-08-04 할당 제거 대조에 썼던 명령형 for문 판. 원인 추적을 잇기 위해 2026-08-05 스트림판으로 복귀했다.
       다시 for판으로 바꿀 때는 위 본문의 스트림 두 줄(mean과 variance)을 지우고 아래 블록을 넣는다.
       주의: 스트림 average()도 sum()과 같은 보정 합을 굴리고 마지막에 인원 수로 나눈다(Kahan 방식).
       아래 블록은 그 보정 계산까지 그대로 옮긴 판이다. 각 합의 마지막 단계는 뺄셈(sum - comp, vsum - vcomp)이어야 비트가 같다(실측으로 확인).
       비트 동일성 검증 완료: 배정 상태 80,000가지 불일치 0건, ImproveEquivalenceCheck 40케이스 통과(2026-08-04).

            int size = team.size();

            // 1) 팀 평균 — 보정 합을 만들고 인원 수로 나눈다
            double sum = 0.0, comp = 0.0, simple = 0.0;
            for (int i = 0; i < size; i++) {
                double v = team.get(i).skill().doubleValue();
                double adjusted = v - comp;
                double next = sum + adjusted;
                comp = (next - sum) - adjusted;
                sum = next;
                simple += v;
            }
            double meanTotal = sum - comp;
            if (Double.isNaN(meanTotal) && Double.isInfinite(simple)) {
                meanTotal = simple;
            }
            double mean = (size > 0) ? meanTotal / size : 0;   // 인원이 없으면 0. 스트림의 orElse(0)과 같다

            // 2) 평균에서 벗어난 거리의 제곱을 다시 평균낸다. 보정 방식은 위와 같고 더하는 값만 다르다
            double vsum = 0.0, vcomp = 0.0, vsimple = 0.0;
            for (int i = 0; i < size; i++) {
                double d = team.get(i).skill().doubleValue() - mean;   // 이 멤버가 평균에서 얼마나 떨어져 있나
                double v = d * d;                                      // 그 거리를 제곱해 분포 값으로 쓴다
                double adjusted = v - vcomp;
                double next = vsum + adjusted;
                vcomp = (next - vsum) - adjusted;
                vsum = next;
                vsimple += v;
            }
            double varTotal = vsum - vcomp;
            if (Double.isNaN(varTotal) && Double.isInfinite(vsimple)) {
                varTotal = vsimple;
            }
            double variance = (size > 0) ? varTotal / size : 0;
    */

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

    /* [검증용 · 임시] 증분 재계산 전환 전의 병렬 경로다.
       위 improve()·ImproveTask를 증분 재계산으로 바꾸기 전 모습 그대로 복사한 것이다.
       평가마다 sumDeviation·distributionDeviation으로 팀 전체를 다시 훑는다.
       바꾸기 전 구현과 바꾼 뒤 구현을 맞대는 대조에만 쓴다(ImproveEquivalenceCheck).
       분할 규칙과 병합 규칙과 계측은 지금 코드와 같으므로 그대로 옮겼고,
       걷어낸 것은 배열 셋(teamSum·teamSqSum·teamSize)과 그것을 쓰는 자리뿐이다.
       검증이 끝나면 improveSequential_backup과 함께 제거 후보다. */

    /** [동일성 검증용 · 임시] 바꾸기 전 병렬 진입점. 목표 분할 개수 경로만 쓴다. */
    void improveBySplitCount_backup(List<List<Member>> teams, double tolerance, int parallelism, int splitCount) {
        improve_backup(teams, tolerance, parallelism, 0, splitCount);
    }

    private void improve_backup(List<List<Member>> teams, double tolerance, int parallelism, int workSize, int splitCount) {

        // 이 호출에서 쓸 fork-join 풀. 워커 스레드 수 = parallelism(운영 경로에서는 논리 프로세서 수와 같은 값). 풀의 생성 위치·재사용 방식은 추후 확정 사항.
        ForkJoinPool pool = new ForkJoinPool(parallelism);


        int teamCount = teams.size();   // 팀 설정이 4개면 4
        int pairCount = teamCount * (teamCount - 1) / 2;    // 팀 개수 4개 - 4 * 12 / 2 = 12 반복.

        int [] pairA = new int[pairCount];
        int [] pairB = new int[pairCount];

        int p = 0;

        /* 팀 짝 K(K-1)/2개를 번호표(pairA·pairB)로 편다. p번 칸 = 팀 (pairA[p], pairB[p]).
           b=a+1 → a<b인 짝만 세어 자기짝·중복 없음. 정원 고정이라 짝당 일량(정원²)이 균일해,
           한 줄로 펴 두면 ImproveTask가 번호 구간을 잘라도 일량이 고르게 나뉜다
           (팀 리스트를 반분하면 두 반을 가로지르는 짝이 소실되므로 이렇게 편다).

           예) K=4 (팀 0~3), pairCount=6. a·b는 팀 번호(0~3), p는 칸 번호(0~5):
             p : 0  1  2  3  4  5
             a : 0  0  0  1  1  2
             b : 1  2  3  2  3  3    (b는 a+1..K-1만 돌고, a가 오르면 a+1로 리셋)
             → pairA={0,0,0,1,1,2}, pairB={1,2,3,2,3,3} */
        for (int a = 0; a < teamCount; a++) {
            for (int b = a + 1; b < teamCount; b++) {
                pairA[p] = a; pairB[p] = b;
                p++;
            }
        }

        // workSize는 이제 호출부가 넘긴다. 계산 식은 위 3인자 오버로드에 있다(짝 수 ÷ 논리 프로세서 수).

        /* [상세 로그 · 임시] 식별자 컨텍스트 — 이 호출의 병렬도·분할을 정적 필드에 실어 두면 말단이 읽어 간다 */
        detailLogParallelism = parallelism;
        detailLogSplitCount = splitCount;
        int detailScanNo = 0;   // [상세 로그 · 임시] 스캔 번호(1부터). while 바퀴마다 1씩 는다

        while (true) {
            long detailScanStartEpochMs = System.currentTimeMillis();   // [상세 로그 · 임시] 스캔 시작 시각(epoch ms) — typeperf CSV 시각과 매핑용
            long detailScanStartNs = System.nanoTime();   // [상세 로그 · 임시] 스캔 1바퀴 시작 시각
            detailScanNo++;                               // [상세 로그 · 임시]
            detailLogScan = detailScanNo;                 // [상세 로그 · 임시] 이번 스캔의 말단 레코드가 읽는 번호

            // 스캔 기준값 — 교환 전 상태의 합 편차와 분포 편차
            double currentSumDev = sumDeviation(teams);
            double currentDistDev = distributionDeviation(teams);

            // 합 필터의 판정 방향을 가르는 값 — 교환 전에 이미 허용폭 안인가.
            boolean withinCap = currentSumDev <= tolerance;

            /* 후보 전수 조사(원본의 4중 루프)를 fork-join 풀에 위임한다.
               루트 작업 하나를 제출하고, 분할·병합을 거친 최종 결과 1건(ImproveTaskResult)을 돌려받는다. */
            ImproveTaskResult result = pool.invoke(
                    new ImproveTask_backup(teams, pairA, pairB, 0, pairCount, workSize, splitCount,
                            tolerance, withinCap, currentSumDev, currentDistDev));

            /* 이후는 원본과 같은 적용/종료 판단. 지역변수 best 대신 반환 객체(result)의 값을 읽는 점만 다르다. */

            if (result.bestTeamA == -1) {
                /* [상세 로그 · 임시] 마지막 스캔(개선 없음) 기록 — 바퀴 시작부터 여기까지 */
                if (detailLogEnabled) {
                    detailLog.add("IMPLOG,scan,main," + parallelism + "," + splitCount + ","
                            + detailLogRunIndex + "," + detailScanNo + "," + (System.nanoTime() - detailScanStartNs)
                            + "," + detailScanStartEpochMs);
                }
//                System.out.println("[임시 계측 · Phase 0 · work-stealing] getStealCount=" + pool.getStealCount());  // 확인 후 주석 처리
                // [현재 활성 설정] pool.shutdown() 주석 상태 — 프로브(ImproveCpuUtilizationProbe)가 호출 뒤
                // 워커별 CPU 장부를 읽으려면 워커가 살아 있어야 하기 때문. 측정이 모두 끝나면 이 줄을 복원한다.
//                pool.shutdown();
                return;   // 이번 스캔에서 개선 교환이 하나도 없었다 → 수렴, 종료
            }
            swap(teams, result.bestTeamA, result.bestIdxA, result.bestTeamB, result.bestIdxB);   // 최선 교환 1건만 확정 적용 후 다시 스캔

            /* [상세 로그 · 임시] 스캔 1바퀴 기록 — 기준값 계산부터 최선 교환 적용까지 */
            if (detailLogEnabled) {
                detailLog.add("IMPLOG,scan,main," + parallelism + "," + splitCount + ","
                        + detailLogRunIndex + "," + detailScanNo + "," + (System.nanoTime() - detailScanStartNs)
                        + "," + detailScanStartEpochMs);
            }

//            [원본 보존 — 순차 코드의 적용/종료 판단]
//            if (bestTeamA == -1) {
//                return;   // 이번 스캔에서 분포 편차를 줄이는 교환이 하나도 없었다 → 수렴, 교환 로직을 완전 종료
//            }
//            swap(teams, bestTeamA, bestIdxA, bestTeamB, bestIdxB);  // 최선 교환 1건만 확정 적용 후 다시 스캔
        }
    }
    public class ImproveTask_backup extends RecursiveTask<ImproveTaskResult> {

        /* 모든 작업이 공유하는 것 (읽기 전용) */

        List<List<Member>> teams;   // 전체 teams — 쪼개지 않은 원본. 말단 작업이 복사본을 만들 때만 사용
        int[] pairA;                // 짝 번호표 — p번 짝의 팀 a
        int[] pairB;                // 짝 번호표 — p번 짝의 팀 b

        int workSize;               // 분할 중단 크기 — improve()에서 한 번 계산한 고정값 (splitCount가 0일 때만 쓴다)
        int splitCount;             // 이 작업이 만들어야 할 말단 작업 수. 0이면 workSize 규칙을 쓴다

        double tolerance;           // 합 필터 기준 (withinCap일 때)
        boolean withinCap;          // 합 필터의 판정 방향 — 스캔 시작 시점 스냅샷
        double currentSumDev;       // 합 필터 기준 (withinCap이 아닐 때)
        double currentDistDev;      // 분포 편차 스냅샷 — 말단 작업의 bestNewDistDev 출발값

        /* 작업마다 다른 것 — 이 작업의 몫 */

        int lo;                     // 담당 짝 번호 구간 시작 (포함)
        int hi;                     // 담당 짝 번호 구간 끝 (미포함)

        public ImproveTask_backup(
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
                double currentDistDev)
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
        }
        @Override
        protected ImproveTaskResult compute() {

            /* 분할한 쪽이 병합도 맡는다 — fork만 하고 join을 하지 않으면 하위 결과를 회수할 곳이 없으므로,
               분할을 수행한 이 compute()가 join으로 두 결과를 받아 병합해 위로 반환 한다.
               (fork와 join 사이의 호출 스택 세부 동작은 추후 학습 항목으로 남겨 두고, 지금은 이 구조 이해로 진행한다.) */

            /* 1. 분할 — 규칙 두 가지 중 하나로 자른다.
                 splitCount > 0 : 목표 분할 개수 규칙. 목표를 반으로 나누고 구간도 그 비율로 자른다.
                                  예) 짝 190개에 목표 6 → 왼쪽 3 · 오른쪽 3, 자르는 위치는 0 + 190×3÷6 = 95.
                 splitCount = 0 : 기존 규칙. 구간이 workSize보다 길면 반으로 자른다. */
            int mid, leftSplit, rightSplit;
            boolean doSplit;

            if (splitCount > 0) {
                leftSplit  = splitCount / 2;                                   // 왼쪽이 맡을 말단 작업 수
                rightSplit = splitCount - leftSplit;                           // 오른쪽이 맡을 말단 작업 수
                mid = lo + (int) ((long) (hi - lo) * leftSplit / splitCount);  // 목표 비율대로 자르는 위치
                doSplit = splitCount > 1 && mid > lo && mid < hi;              // 목표가 1이거나 더 못 자를 면 말단으로
            } else {
                leftSplit  = 0;
                rightSplit = 0;
                mid = (lo + hi) / 2;
                doSplit = hi - lo > workSize;
            }

            if (doSplit) {

                ImproveTask_backup leftTask  = new ImproveTask_backup(teams, pairA, pairB, lo,  mid, workSize, leftSplit,
                        tolerance, withinCap, currentSumDev, currentDistDev);
                ImproveTask_backup rightTask = new ImproveTask_backup(teams, pairA, pairB, mid, hi,  workSize, rightSplit,
                        tolerance, withinCap, currentSumDev, currentDistDev);

                leftTask.fork();                                      // 왼쪽만 큐에 — 다른 스레드가 가져간다
                ImproveTaskResult rightResult = rightTask.compute();  // 오른쪽은 지금 스레드가 직접
                ImproveTaskResult leftResult  = leftTask.join();      // 왼쪽 결과 회수

                /* 병합 — 하위 두 결과 중 bestNewDistDev 값이 더 큰 쪽(왼쪽이 크면 왼쪽, 그 외 오른쪽)을 반환한다.
                   단계마다 1건으로 줄여 올라가므로 최상위 호출부(improve의 while)는 최종 1건만 받고,
                   원본과 같은 기준(bestTeamA == -1 여부)으로 적용/종료를 판단한다. */
                if (leftResult.bestTeamA == -1)  return rightResult;   // 왼쪽이 후보 없음 → 오른쪽이 뭐든 그쪽
                if (rightResult.bestTeamA == -1) return leftResult;    // 오른쪽이 후보 없음 → 왼쪽

                if (rightResult.bestNewDistDev < leftResult.bestNewDistDev - 1e-12) {
                    return rightResult;                   // 오른쪽이 1e-12를 넘게 작을 때만 오른쪽 승
                }
                if (Math.abs(rightResult.bestNewDistDev - leftResult.bestNewDistDev) <= 1e-12) {
                    // 동률 — ID 키 사전순 (순차 코드의 동률 규칙과 같은 비교)
                    if (rightResult.bestKeyFirst.compareTo(leftResult.bestKeyFirst) < 0
                            || (rightResult.bestKeyFirst.equals(leftResult.bestKeyFirst)
                            && rightResult.bestKeySecond.compareTo(leftResult.bestKeySecond) < 0)) {
                        return rightResult;
                    }
                    return leftResult;                    // 키까지 같거나 왼쪽이 앞서면 왼쪽
                }
                return leftResult;                        // 왼쪽이 1e-12를 넘게 작음 → 왼쪽 승
            }

            long dlogStartEpochMs = System.currentTimeMillis();   // [상세 로그 · 임시] 말단 시작 시각(epoch ms) — typeperf CSV 시각과 매핑용
            long dlogCopyStartNs = System.nanoTime();   // [상세 로그 · 임시] 복사 시각 — 기존 계측과 별도로 읽는다

            long copyStartNs = System.nanoTime();   // [4.1 계측 1 시작 · 임시] 복사 블록 직전 시각

            /* fork 한 여러 스레드들이 동일한 teams 메모리 지점을 바라보면 안되기 때문에 현재 스레드마다 깊은 복사하기 */
            List<List<Member>> local = new ArrayList<>(teams.size());
            for (List<Member> team : teams) {
                local.add(new ArrayList<>(team));
            }

            long localCopyNs = System.nanoTime() - copyStartNs;   // [4.1 계측 1 끝 · 임시] 복사에 걸린 시간(ns)
            long dlogCopyNs = System.nanoTime() - dlogCopyStartNs;   // [상세 로그 · 임시] 복사 시간(별도. 기존 계측의 nanoTime 2회를 안쪽에 포함)

            double bestNewDistDev = currentDistDev;   // 여기도 teams 와 같은 맥락

            ImproveTaskResult improveTaskResult = new ImproveTaskResult();

            /* [상세 로그 · 임시] 말단 레코드용 지역 변수 — 안쪽 루프에서는 지역 변수에만 더하고,
               공유 버퍼 저장은 말단이 끝날 때 1회만 한다(루프 안에서 공유 저장소를 치면 워커 간 경쟁이 새로 생겨
               측정 대상 현상을 바꾸기 때문). 켜짐 여부와 스캔 번호는 말단 시작 시점 값으로 고정한다. */
            boolean dlogOn = detailLogEnabled;            // 기록 여부 스냅샷
            int dlogScanNo = detailLogScan;               // 이 말단이 속한 스캔 번호 스냅샷
            long dlogSumDevCount = 0, dlogSumDevNs = 0;   // sumDeviation 횟수·누적 시간(ns)
            long dlogDistDevCount = 0, dlogDistDevNs = 0; // distributionDeviation 횟수·누적 시간(ns)
            long dlogLoopStartNs = System.nanoTime();     // 4중 루프 시각 — 기존 계측과 별도로 읽는다

            /* [4.1 계측 · 임시] 계측 2의 시작 시각과, 계측 3·4가 세는 지역 변수 2개.
               가장 안쪽 반복문에서는 지역 변수에 1씩만 더하고, 정적 카운터 합산은 말단이 끝날 때 한 번만 한다. */
            long evalLoopStartNs = System.nanoTime();   // [4.1 계측 2 시작 · 임시] 4중 루프 직전 시각
            long localEvalCount = 0;                    // [4.1 계측 3 · 임시] 평가 횟수 지역 변수
            long localPassCount = 0;                    // [4.1 계측 4 · 임시] 통과 횟수 지역 변수

            // 2. 말단 작업 — 담당 구간 [lo, hi)의 짝만, 번호표에서 찾아 평가한다.
            for (int pIdx = lo; pIdx < hi; pIdx++) {
                int a = pairA[pIdx];                  // p번 짝의 팀 a
                int b = pairB[pIdx];                  // p번 짝의 팀 b

                for (int i = 0; i < local.get(a).size(); i++) {      // a의 i번째 사람
                    for (int j = 0; j < local.get(b).size(); j++) {  // b의 j번째 사람

                        // ← 몸통(swap → 합 필터 → (3a)/(3b) → 복구)은 한 글자도 안 바뀜

                            swap(local, a, i, b, j);            // (1) 후보 교환을 적용해 본다

                            long dlogT0 = System.nanoTime();                 // [상세 로그 · 임시] sumDeviation 앞 시각
                            double newSumDev = sumDeviation(local);      // 교환 후 합 편차 재계산
                            dlogSumDevNs += System.nanoTime() - dlogT0;      // [상세 로그 · 임시] sumDeviation 시간 누적
                            dlogSumDevCount++;                               // [상세 로그 · 임시] sumDeviation 횟수

                            localEvalCount++;   // [4.1 계측 3 · 임시] 평가 1회 실행됨

                            /* (2) 합 필터 — 제약(합 균형)을 깨는 교환을 걸러낸다.
                               withinCap이면 교환 후에도 허용폭 이내여야 통과,
                               아니면 교환 전(currentSumDev)보다 나빠지지 않아야 통과. */
                            boolean pass = withinCap ? newSumDev <= tolerance : newSumDev <= currentSumDev;

                            if (pass) {

                                long dlogT1 = System.nanoTime();                       // [상세 로그 · 임시] distributionDeviation 앞 시각
                                double newDistDev = distributionDeviation(local);  // 교환 후 분포 편차 재계산
                                dlogDistDevNs += System.nanoTime() - dlogT1;           // [상세 로그 · 임시] distributionDeviation 시간 누적
                                dlogDistDevCount++;                                    // [상세 로그 · 임시] distributionDeviation 횟수

                                localPassCount++;   // [4.1 계측 4 · 임시] 합 필터를 통과한 평가 1회

                                // (3a) 엄격한 개선(1e-12 초과) — best의 값·위치·키를 모두 갱신
                                if (newDistDev < bestNewDistDev - 1e-12) {

                                    bestNewDistDev = newDistDev;

                                    improveTaskResult.bestTeamA = a; improveTaskResult.bestIdxA = i;
                                    improveTaskResult.bestTeamB = b; improveTaskResult.bestIdxB = j;

                                    // 동률 비교용 키 — 맞바꾼 두 참가자 ID를 (작은 것, 큰 것) 순서로 기록
                                    String idA = local.get(a).get(i).participationId();
                                    String idB = local.get(b).get(j).participationId();

                                    improveTaskResult.bestKeyFirst = idA.compareTo(idB) <= 0 ? idA : idB;
                                    improveTaskResult.bestKeySecond = idA.compareTo(idB) <= 0 ? idB : idA;

                                }

                                // (3b) 동률(±1e-12 이내) — ID 키가 사전순으로 앞설 때만 위치·키를 갱신 (결정성 규칙)
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

                            // (4) 원상 복구 — 후보 평가는 가정일 뿐이므로 되돌린다
                            swap(local, a, i, b, j);
                        }
                    }

            }   // 4중 루프 끝

            long localEvalLoopNs = System.nanoTime() - evalLoopStartNs;   // [4.1 계측 2 끝 · 임시] 4중 루프 전체에 걸린 시간(ns)
            long dlogLoopNs = System.nanoTime() - dlogLoopStartNs;        // [상세 로그 · 임시] 4중 루프 시간(별도. 기존 계측 구간을 안쪽에 포함)

            /* [4.1 합산 · 임시] 새로 재는 것은 없다. 지역 변수 4개를 정적 카운터 4개에 각각 옮겨 더한다. */
            evalCount.addAndGet(localEvalCount);
            passCount.addAndGet(localPassCount);
            copyTimeNs.addAndGet(localCopyNs);
            evalLoopTimeNs.addAndGet(localEvalLoopNs);

            /* [상세 로그 · 임시] 말단 레코드 1건 저장 — 실제 출력은 프로브가 호출이 끝난 뒤 한다 */
            if (dlogOn) {
                detailLog.add("IMPLOG,leaf," + Thread.currentThread().getName()
                        + "," + detailLogParallelism + "," + detailLogSplitCount
                        + "," + detailLogRunIndex + "," + dlogScanNo
                        + "," + dlogCopyNs + "," + dlogLoopNs
                        + "," + dlogSumDevCount + "," + dlogSumDevNs
                        + "," + dlogDistDevCount + "," + dlogDistDevNs
                        + "," + dlogStartEpochMs);
            }

            /* 원본과 다른 점 — 원본은 스캔이 끝난 이 지점에서 바로 적용/종료를 판단했지만,
               이 작업은 부분 결과를 ImproveTaskResult로 만들어 반환만 한다.
               적용/종료 판단은 병합을 거친 최종 결과를 받는 최상위 호출부(improve의 while)가 수행한다. */

            improveTaskResult.bestNewDistDev = bestNewDistDev;

            return improveTaskResult;
        } // compute() 끝
    }

}

