package com.exercisemanagement.challenge.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import com.exercisemanagement.challenge.service.TeamFormationEngine.Member;

/**
 * 교환 반복의 동일성 검증 프로그램이다. 같은 입력으로 구현 넷을 돌려 최종 배정을 대조한다.
 *
 * <p>대조 셋이 각각 다른 질문에 답한다.
 *
 * <ol>
 *   <li>새 순차(improveSequential)와 새 병렬(improveBySplitCount).
 *       나눠 훑어도 혼자 훑은 것과 같은 답이 나오는지 본다. 병렬 세 회차를 각각 본다.</li>
 *   <li>옛 순차(improveSequential_backup)와 새 순차.
 *       증분 재계산이 순차에서 답을 바꿨는지 본다. 다른 것은 두 편차 계산 방법 하나뿐이다.</li>
 *   <li>옛 병렬(improveBySplitCount_backup)과 새 병렬 1회차.
 *       병렬 경로에서도 증분 재계산이 답을 바꿨는지 본다.</li>
 * </ol>
 *
 * <p>옛 순차와 옛 병렬을 서로 맞대지는 않는다. 그 조합은 이번 변경 전에 이미 통과시킨 것이다.
 *
 * <p>판정 기준은 둘이다. 하나는 최종 배정이 팀별로 자리별로 완전히 같은지이고,
 * 다른 하나는 그 배정에서 낸 최종 두 편차가 같은지다.
 * 두 편차는 양쪽 모두 sumDeviation과 distributionDeviation으로 낸다.
 * 두 메서드는 증분 재계산 전환에서 바뀌지 않았으므로 같은 자로 재는 것이 되고, 그래야 배정 차이만 값에 남는다.
 *
 * <p>비트 단위 비교는 쓰지 않는다. 증분 재계산은 분산을 제곱합에서 내어 계산 경로가 달라졌고
 * 마지막 자리가 어긋날 수 있다. 어긋나면 차이와 비중을 찍고 판단은 사람이 한다.
 *
 * <p>케이스는 부하 조합 다섯 가지와 시드 둘과 허용폭 둘과 시작 배정 둘을 모두 조합한 40개다.
 * 케이스마다 병렬판을 여러 번 돌려 스레드 배정이 달라져도 같은 답이 나오는지까지 확인한다.
 *
 * <p>입력 생성은 SeedGreedyBench와 같은 방식이고 정렬은 form()의 1단계와 같다.
 * 실행은 IntelliJ에서 이 클래스의 main을 돌린다.
 *
 * <p>출력에 찍히는 시간과 배율은 간이 측정이다. 워밍업과 fork를 통제하지 않으므로
 * 방향을 잡는 데만 쓰고, 문서에 넣을 값은 JMH로 따로 잰다.
 */
public class ImproveEquivalenceCheck {

    /** 부하 조합이다. 차례로 인원과 팀 수와 정원이다. 전 조합이 인원은 팀 수 곱하기 정원이라는 전제를 만족한다. */
    private static final int[][] COMBOS = {
            { 60,  6, 10},
            {120, 12, 10},
            {200, 20, 10},
            {210,  7, 30},
            {240,  8, 30},
    };

    /** 입력 생성에 쓸 시드다. */
    private static final long[] SEEDS = {42L, 20260722L};

    /** 허용폭 비율(%)이다. form()의 sumCapPercent에 해당한다. 좁은 폭과 넓은 폭 두 경우를 본다. */
    private static final double[] CAP_PERCENTS = {5.0, 20.0};

    /** 케이스마다 병렬판을 몇 번 돌릴지다. 스레드 배정이 달라져도 답이 같은지 보려는 것이다. */
    private static final int PARALLEL_REPEATS = 3;

    /**
     * 검증에 쓸 풀 병렬도와 목표 분할 개수다. 측정과 같은 분할 경로를 태우려고 둔 값이다.
     *
     * <p>짝 수가 SPLIT_COUNT보다 적은 조합에서는 더 나눌 수 없는 지점에서 분할이 멈춰
     * 말단 작업 수가 짝 수만큼만 나온다. 60명 6팀은 15개, 210명 7팀은 21개, 240명 8팀은 28개다.
     */
    private static final int PARALLELISM = 12;
    private static final int SPLIT_COUNT = 36;

    public static void main(String[] args) {
        TeamFormationEngine engine = new TeamFormationEngine();

        int totalCases = 0;
        int passCases = 0;         // 배정도 값도 같다
        int valueDiffCases = 0;    // 배정은 같고 값의 마지막 자리만 다르다
        int assignDiffCases = 0;   // 배정이 다르다 — 통과로 처리하지 않는다
        long startMs = System.currentTimeMillis();

        for (int[] combo : COMBOS) {
            int memberCount = combo[0];
            int teamCount = combo[1];
            int teamCapacity = combo[2];

            for (long seed : SEEDS) {
                List<Member> sorted = buildSortedMembers(memberCount, seed);

                for (double capPercent : CAP_PERCENTS) {
                    double tolerance = tolerance(sorted, teamCount, capPercent);

                    // 시작 배정 두 가지다. 운영 경로가 둘 다 교환 반복에 태우므로 각각 검증한다.
                    List<List<Member>> serpentineSeed = engine.seedSerpentine(sorted, teamCount);
                    List<List<Member>> greedySeed = engine.seedGreedy(sorted, teamCount, teamCapacity);

                    String comboLabel = String.format("N=%d K=%d cap=%d seed=%d cap%%=%.0f",
                            memberCount, teamCount, teamCapacity, seed, capPercent);

                    totalCases++;
                    Verdict v1 = runCase(engine, comboLabel + " start=serpentine", serpentineSeed, tolerance);
                    if (v1 == Verdict.PASS) passCases++;
                    else if (v1 == Verdict.VALUE_DIFF) valueDiffCases++;
                    else assignDiffCases++;

                    totalCases++;
                    Verdict v2 = runCase(engine, comboLabel + " start=greedy", greedySeed, tolerance);
                    if (v2 == Verdict.PASS) passCases++;
                    else if (v2 == Verdict.VALUE_DIFF) valueDiffCases++;
                    else assignDiffCases++;
                }
            }
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        System.out.println();
        System.out.printf("전체 %d케이스 — 통과 %d건, 배정 같고 값만 다름 %d건, 배정 다름 %d건 (소요 %.1f초)%n",
                totalCases, passCases, valueDiffCases, assignDiffCases, elapsedMs / 1000.0);

        if (valueDiffCases == 0 && assignDiffCases == 0) {
            System.out.println("결론: 모든 케이스에서 배정과 최종 두 편차 값이 순차와 병렬에서 같다.");
        } else {
            System.out.println("결론: 위 상세를 보고 통과 여부를 사람이 정한다. 임의로 통과로 처리하지 않는다.");
        }

        /* [보존] 비트 비교 시절의 마감 출력이다. 판정이 통과와 실패 두 갈래였다.
              되돌릴 때는 위 블록을 지우고 아래 줄들의 // 를 뗀다. failedCases 변수도 되살린다.

           // System.out.printf("전체 %d케이스 중 실패 %d건 (소요 %.1f초)%n",
           //         totalCases, failedCases, elapsedMs / 1000.0);
           // System.out.println(failedCases == 0
           //         ? "결론: 병렬판은 모든 케이스에서 순차 구현과 배정·부동소수점 값이 완전히 일치한다."
           //         : "결론: 불일치 케이스가 있다. 위 FAIL 상세를 확인할 것.");
        */
    }

    /** 케이스 하나의 판정 결과다. 통과와 값만 다름과 배정 다름 세 갈래이고, 적은 순서가 곧 나쁜 정도다. */
    private enum Verdict { PASS, VALUE_DIFF, ASSIGN_DIFF }

    /**
     * 케이스 한 건을 실행한다.
     *
     * <p>같은 시작 배정에서 옛 구현 둘과 새 순차 하나와 새 병렬 세 회차를 돌리고 대조 셋을 본다.
     * 시작 배정은 실행마다 깊은 복사로 새로 떠서 넘긴다. 교환 반복이 넘겨받은 목록을 제자리에서 고치기 때문이다.
     *
     * @param engine    엔진
     * @param label     출력에 붙일 케이스 이름
     * @param seedTeams 이 케이스의 시작 배정. 이 목록 자체는 고치지 않는다
     * @param tolerance 합 편차 허용폭
     * @return 이 케이스의 판정. 여러 대조 중 가장 나쁜 것을 돌려준다
     */
    private static Verdict runCase(TeamFormationEngine engine, String label,
                                   List<List<Member>> seedTeams, double tolerance) {

        /* 바꾸기 전 구현 둘을 먼저 돌린다. 대조 2와 대조 3의 상대다.
              엔진의 _backup 넷은 증분 재계산으로 바꾸기 전 코드 그대로다.
              평가마다 팀 전체를 다시 훑어 두 편차를 낸다. 여기도 반환값 없이 제자리 수정이라,
              호출이 끝나면 넘긴 배정 자체가 그 구현의 최종 배정이다. */
        List<List<Member>> oldSeqTeams = deepCopy(seedTeams);
        engine.improveSequential_backup(oldSeqTeams, tolerance);                                   // 옛 순차의 최종 배정

        List<List<Member>> oldParTeams = deepCopy(seedTeams);
        engine.improveBySplitCount_backup(oldParTeams, tolerance, PARALLELISM, SPLIT_COUNT);       // 옛 병렬의 최종 배정

        /* 기준이 될 순차 결과를 만든다.
              improveSequential은 반환값이 없다(void). 넘긴 배정을 제자리에서 고치는 방식이라,
              호출이 끝나면 인자로 넘긴 seqTeams 자체가 이 케이스의 최종 배정이다.
              (엔진이 스캔 한 바퀴마다 swap(teams, ...)으로 그 리스트를 직접 바꾼다)

              그래서 시작 배정 seedTeams를 그대로 넘기지 않고 깊은 복사를 떠서 넘긴다.
              seedTeams는 아래 병렬 세 회차도 같은 출발점으로 써야 하므로 끝까지 건드리지 않는다. */
        List<List<Member>> seqTeams = deepCopy(seedTeams);

        long seqStart = System.nanoTime();
        engine.improveSequential(seqTeams, tolerance);   // 끝나면 seqTeams가 순차의 최종 배정
        double seqSec = (System.nanoTime() - seqStart) / 1e9;

        /* [보존] 비트 비교 시절의 기준값 두 줄이다. 지금은 compare()가 값을 직접 낸다.
              되돌리는 법: 아래 두 줄의 // 를 떼고, compare() 호출 자리를 옛 비트 비교로 되돌린다.

           // long seqSumBits = Double.doubleToRawLongBits(engine.sumDeviation(seqTeams));
           // long seqDistBits = Double.doubleToRawLongBits(engine.distributionDeviation(seqTeams));
        */

        double[] parSec = new double[PARALLEL_REPEATS];
        Verdict worst = Verdict.PASS;   // 이 케이스의 모든 대조 중 가장 나쁜 판정을 결과로 삼는다

        /* 대조 2 — 옛 순차와 새 순차. 둘 다 단일 스레드라 다른 것은 두 편차 계산 방법 하나뿐이다.
           여기서 어긋나면 원인이 증분 재계산 하나로 좁혀진다. */
        worst = worse(worst, compare(engine, label + " 대조2 옛 순차 대 새 순차", oldSeqTeams, seqTeams));

        /* 병렬 결과를 PARALLEL_REPEATS회 만들어 기준과 맞댄다.
              병렬 호출도 반환값이 없다. 순차와 같은 방식으로 넘긴 배정을 제자리에서 고친다.
              회차마다 seedTeams에서 새로 복사를 뜨는 이유는, 앞 회차가 자기 복사본을 이미
              최종 배정까지 고쳐 놓아서 그것을 다시 넘기면 같은 출발점이 되지 않기 때문이다. */
        for (int rep = 1; rep <= PARALLEL_REPEATS; rep++) {
            List<List<Member>> parTeams = deepCopy(seedTeams);

            long parStart = System.nanoTime();
            engine.improveBySplitCount(parTeams, tolerance, PARALLELISM, SPLIT_COUNT);   // 끝나면 parTeams가 이 회차의 최종 배정
            parSec[rep - 1] = (System.nanoTime() - parStart) / 1e9;

            /* [보존] 이 자리의 원래 코드는 아래 한 줄이었다.
                  무엇을 바꿨나: 인자 두 개짜리 improve()를 improveBySplitCount()로 바꿨다.
                  왜 바꿨나: 인자 두 개짜리는 workSize 규칙으로 짝 구간을 나누는데,
                             측정(ImproveCpuUtilizationProbe)은 improveBySplitCount 경로로 나눈다.
                             그대로 두면 검증한 분할 경로와 측정할 분할 경로가 서로 달라져,
                             검증이 통과해도 측정에서 도는 분할 경로는 검증되지 않은 것이 된다.
                  되돌리는 법: 위 improveBySplitCount 호출 한 줄을 지우고 아래 줄의 // 를 뗀다.
                               PARALLELISM과 SPLIT_COUNT 상수도 함께 지운다.

               // engine.improve(parTeams, tolerance);
            */

            /* 넘기는 seqTeams와 parTeams는 시작 배정이 아니라 위에서 각각 돌리고 난 뒤의 최종 배정이다.
               회차마다 판정한다. 어긋나도 남은 회차를 건너뛰지 않는다 — 세 회차가 다 같은지도 봐야 한다. */
            worst = worse(worst, compare(engine, label + " 대조1 순차 대 병렬 " + rep + "회차", seqTeams, parTeams));

            /* 대조 3 — 옛 병렬과 새 병렬. 1회차에서만 본다.
               옛 병렬은 이번 변경 전에 이미 통과시킨 코드라 결정성을 다시 볼 이유가 없다. */
            if (rep == 1) {
                worst = worse(worst, compare(engine, label + " 대조3 옛 병렬 대 새 병렬", oldParTeams, parTeams));
            }

            /* [보존] 비트 비교 시절의 회차 판정 몸통이다. 첫 불일치에서 바로 빠져나갔다.
                  왜 바꿨나: 증분 재계산은 분산의 계산 경로가 달라져 마지막 자리가 어긋날 수 있다.
                             비트 비교로 두면 정상인 경우도 전부 실패로 찍힌다.
                  되돌리는 법: 위 compare() 호출 네 줄을 지우고 아래 줄들의 // 를 뗀다.
                               runCase의 반환형을 boolean으로, 옛 기준값 두 줄도 함께 되돌린다.

               // String mismatch = firstAssignmentMismatch(seqTeams, parTeams);
               // if (mismatch != null) {
               //     System.out.printf("[%s] FAIL (병렬 %d회차) 배정 불일치: %s%n", label, rep, mismatch);
               //     return false;
               // }
               //
               // long parSumBits = Double.doubleToRawLongBits(engine.sumDeviation(parTeams));
               // long parDistBits = Double.doubleToRawLongBits(engine.distributionDeviation(parTeams));
               //
               // if (parSumBits != seqSumBits || parDistBits != seqDistBits) {
               //     System.out.printf("[%s] FAIL (병렬 %d회차) 편차 값 비트 불일치: "
               //                     + "sumDev 순차=%s 병렬=%s / distDev 순차=%s 병렬=%s%n",
               //             label, rep,
               //             Double.longBitsToDouble(seqSumBits), Double.longBitsToDouble(parSumBits),
               //             Double.longBitsToDouble(seqDistBits), Double.longBitsToDouble(parDistBits));
               //     return false;
               // }
            */
        }

        double parAvg = 0.0;
        StringBuilder reps = new StringBuilder();
        for (int r = 0; r < PARALLEL_REPEATS; r++) {
            parAvg += parSec[r];
            if (r > 0) reps.append('/');
            reps.append(String.format("%.2f", parSec[r]));
        }
        parAvg /= PARALLEL_REPEATS;

        String verdictText = worst == Verdict.PASS ? "PASS"
                : worst == Verdict.VALUE_DIFF ? "값 차이" : "배정 다름";

        System.out.printf("[%s] %s  순차 %.2f초, 병렬 %s초 (평균 %.2f초) → 배율 %.2f배%n",
                label, verdictText, seqSec, reps, parAvg, seqSec / parAvg);
        return worst;
    }

    /**
     * 두 결과를 대조해 판정한다.
     *
     * <p>최종 배정이 같은지와 그 배정에서 낸 최종 두 편차가 같은지를 본다.
     * 두 편차는 양쪽 모두 sumDeviation과 distributionDeviation으로 낸다.
     * 두 메서드는 증분 재계산 전환에서 바뀌지 않았으므로, 같은 자로 재면 배정 차이만 값에 남는다.
     *
     * @param engine 엔진
     * @param label  출력에 붙일 대조 이름
     * @param base   기준이 되는 결과
     * @param other  맞대어 볼 결과
     * @return 판정 결과
     */
    private static Verdict compare(TeamFormationEngine engine, String label,
                                   List<List<Member>> base, List<List<Member>> other) {

        /* base와 other는 각각 돌리고 난 뒤의 최종 배정이다. 거기서 두 편차를 낸다.
           이 값은 통과 여부를 가리는 잣대가 아니라, 배정이 어긋났을 때 얼마나 다른지를
           숫자로 찍기 위한 것이다(7.2절). 두 메서드는 증분 재계산 전환에서 바뀌지 않았으므로
           양쪽을 같은 자로 재는 것이 되고, 그래야 배정 차이만 값에 남는다. */
        double baseSum = engine.sumDeviation(base);
        double baseDist = engine.distributionDeviation(base);
        double otherSum = engine.sumDeviation(other);
        double otherDist = engine.distributionDeviation(other);

        double sumDiff = otherSum - baseSum;
        double distDiff = otherDist - baseDist;

        String mismatch = firstAssignmentMismatch(base, other);

        if (mismatch != null) {
            // 배정이 다르다 — 통과로 처리하지 않는다. 두 편차가 얼마나 다른지 재서 보고한다
            System.out.printf("[%s] 배정 다름: %s%n", label, mismatch);
            System.out.printf("        합 편차   기준 %s / 대상 %s / 차 %.6g / 비중 %.6g%%%n",
                    baseSum, otherSum, sumDiff, ratio(sumDiff, baseSum));
            System.out.printf("        분포 편차 기준 %s / 대상 %s / 차 %.6g / 비중 %.6g%%%n",
                    baseDist, otherDist, distDiff, ratio(distDiff, baseDist));
            return Verdict.ASSIGN_DIFF;
        }

        if (sumDiff != 0.0 || distDiff != 0.0) {
            // 배정은 같고 값의 마지막 자리만 다르다 — 차이 크기를 적고 통과 여부는 사람이 정한다
            System.out.printf("[%s] 배정 같음, 값 차이: 합 편차 차 %.6g (비중 %.6g%%) / 분포 편차 차 %.6g (비중 %.6g%%)%n",
                    label, sumDiff, ratio(sumDiff, baseSum), distDiff, ratio(distDiff, baseDist));
            return Verdict.VALUE_DIFF;
        }

        return Verdict.PASS;
    }

    /**
     * 두 판정 중 나쁜 쪽을 고른다. 열거형에 적은 순서가 곧 나쁜 정도라 ordinal로 견준다.
     *
     * @param a 지금까지의 판정
     * @param b 새로 나온 판정
     * @return 둘 중 나쁜 쪽
     */
    private static Verdict worse(Verdict a, Verdict b) {
        return b.ordinal() > a.ordinal() ? b : a;
    }

    /**
     * 차이를 기준값으로 나눈 비중(%)을 낸다.
     *
     * @param diff 차이
     * @param base 기준값
     * @return 비중(%). 기준값이 0이면 나눌 수 없어 NaN을 돌려준다
     */
    private static double ratio(double diff, double base) {
        return base == 0.0 ? Double.NaN : diff / base * 100.0;
    }

    /**
     * 참가자 목록을 만들고 정렬한다. 생성은 SeedGreedyBench와 같은 방식이고 정렬은 form()의 1단계와 같다.
     *
     * @param memberCount 인원 수
     * @param seed        난수 시드
     * @return 실력 내림차순으로 정렬된 참가자 목록. 동률이면 참가 식별자 오름차순이다
     */
    private static List<Member> buildSortedMembers(int memberCount, long seed) {
        Random rnd = new Random(seed);
        List<Member> members = new ArrayList<>(memberCount);
        for (int i = 0; i < memberCount; i++) {
            BigDecimal skill = BigDecimal.valueOf(rnd.nextInt(1000)).movePointLeft(1);
            members.add(new Member(String.format("P%05d", i), skill));
        }
        members.sort(Comparator.comparing(Member::skill).reversed()
                .thenComparing(Member::participationId));
        return members;
    }

    /**
     * 허용폭을 낸다. 계산은 form()의 1단계와 같다.
     *
     * @param members    참가자 전체
     * @param teamCount  팀 수
     * @param capPercent 허용폭 비율(%)
     * @return 허용폭
     */
    private static double tolerance(List<Member> members, int teamCount, double capPercent) {
        double totalSkill = members.stream().mapToDouble(m -> m.skill().doubleValue()).sum();
        double avgTeamSum = totalSkill / teamCount;
        return avgTeamSum * (capPercent / 100.0);
    }

    /**
     * 배정을 깊은 복사한다. 바깥 목록과 팀 목록만 새로 만들고 참가자는 불변이라 함께 쓴다.
     *
     * @param teams 복사할 배정
     * @return 복사본
     */
    private static List<List<Member>> deepCopy(List<List<Member>> teams) {
        List<List<Member>> copy = new ArrayList<>(teams.size());
        for (List<Member> team : teams) {
            copy.add(new ArrayList<>(team));
        }
        return copy;
    }

    /**
     * 두 배정을 팀별로 자리별로 대조한다.
     *
     * @param seq 기준 배정
     * @param par 맞대어 볼 배정
     * @return 다 같으면 null, 다르면 첫 어긋난 지점을 설명하는 문장
     */
    private static String firstAssignmentMismatch(List<List<Member>> seq, List<List<Member>> par) {
        if (seq.size() != par.size()) {
            return "팀 수가 다름: 순차 " + seq.size() + " vs 병렬 " + par.size();
        }
        for (int t = 0; t < seq.size(); t++) {
            if (seq.get(t).size() != par.get(t).size()) {
                return "팀 " + t + " 인원이 다름: 순차 " + seq.get(t).size() + " vs 병렬 " + par.get(t).size();
            }
            for (int i = 0; i < seq.get(t).size(); i++) {
                String idSeq = seq.get(t).get(i).participationId();
                String idPar = par.get(t).get(i).participationId();
                if (!idSeq.equals(idPar)) {
                    return "팀 " + t + "의 " + i + "번 자리: 순차 " + idSeq + " vs 병렬 " + idPar;
                }
            }
        }
        return null;
    }
}
