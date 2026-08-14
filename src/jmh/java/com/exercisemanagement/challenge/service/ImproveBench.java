package com.exercisemanagement.challenge.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import com.exercisemanagement.challenge.service.TeamFormationEngine.Member;

/**
 * improve() 성능 실측 벤치마크 — 순차(improveSequential) vs fork-join 병렬(improve).
 *
 * 측정 대상 2개 (같은 JMH 실행에서 백투백):
 *   sequential() → improveSequential()  (원본 순차 복원, 배율의 분모)
 *   parallel()   → improve()            (fork-join 병렬, 운영 경로 form()이 호출)
 *
 * 측정 단위 = improve() 호출 1회 = 스캔 반복 while 전체.
 * 두 대상은 넘겨받은 teams를 제자리 수정하므로(호출 한 번에 시작 배정이 최종 결과로 덮인다),
 * 호출마다 같은 시작 배정(greedy)을 새 복사본으로 떠서 넘긴다. 그 복사는 @Setup(Level.Invocation)에서
 * 하므로 측정 시간에 섞이지 않는다.
 *
 * 고정 입력: 정원 10, 시드 42, 허용폭 10%, 시작 배정 greedy. 변하는 축은 부하 N뿐.
 * 측정 환경(배율 해석 기준): i5-11400H, 물리 6 · 논리 12, JDK 17.
 */
@BenchmarkMode(Mode.AverageTime)          // 호출 1회당 평균 시간
@OutputTimeUnit(TimeUnit.MILLISECONDS)    // 보고 단위 ms (주 그리드가 ms~수백 ms 규모)
@State(Scope.Benchmark)                   // 아래 필드 = 벤치 입력 상태 (상수 접기 차단)
@Fork(2)                                  // JVM 2개로 나눠 측정 (실행 간 오염 차단)
@Warmup(iterations = 5, time = 1)         // 버리는 1초 블록 5개 (JIT 안정화 대기) — SeedGreedyBench와 같은 블록 수
@Measurement(iterations = 5, time = 3)    // 재는 3초 블록 5개 — SeedGreedyBench(실험 B)와 같은 블록 수
public class ImproveBench {

    /** 전체 인원 N — 유일한 측정 축. 값마다 전체 측정(fork부터)을 처음부터 다시 돈다.
     *  오름차순으로 둔다 → 값싼 N이 먼저 돌아, 벤치가 제대로 도는지 일찍 확인하고 비싼 N=300은 맨 뒤로. */
//    @Param({"200", "300"})   // [이전 설정 · 보존] Phase 4 gc 진단용 축소 그리드 — 고원 구간(N=200·300)만 재던 임시 설정(2026-07-27 종료)
    @Param({"20", "40", "60", "120", "200", "300"})   // [현재 활성 설정] 전체 그리드(2026-07-27 원복) — 값싼 N부터 오름차순으로 돌아 벤치 정상 동작을 일찍 확인
    int memberCount;

    /** 팀 정원 고정 10 (seedGreedy 측정과 같은 비즈니스 가정). 팀 수 K = N / 10. */
    static final int TEAM_CAPACITY = 10;

    /** 허용폭 비율(%) — form()의 sumCapPercent. 측정 고정값 10%. */
    static final double CAP_PERCENT = 10.0;

    /** 입력 생성 고정 시드 — 같은 시드면 매번 같은 입력. */
    static final long SEED = 42L;

    int teamCount;                     // = memberCount / TEAM_CAPACITY (setup에서 파생)
    double tolerance;                  // 허용폭 절대값 (setup에서 1회 계산)

    TeamFormationEngine engine;
    List<List<Member>> greedySeed;     // 모든 팀원 개발 완료한 배열 - 고정 시작 배정 (greedy). 이 원본은 안 건드리고 호출마다 복사

    /** 호출마다 새로 뜨는 시작 배정 복사본. 측정 대상이 이걸 제자리 수정한다. */
    List<List<Member>> teams;

    @Setup(Level.Trial)                // fork(JVM)당 1회. 측정 시간 밖
    public void setupTrial() {

        teamCount = memberCount / TEAM_CAPACITY;    // 팀당 인원 계산 : N / 10 = K
        engine = new TeamFormationEngine();         // 테스트 java 파일 가져오기

        // SeedGreedyBench / ImproveEquivalenceCheck와 같은 입력 생성 (실력 0.0~99.9, ID "P00000")
        Random rnd = new Random(SEED);

        // 레코드 : public record Member(String participationId, BigDecimal skill) { }
        List<Member> members = new ArrayList<>(memberCount);

        for (int i = 0; i < memberCount; i++) {
            BigDecimal skill = BigDecimal.valueOf(rnd.nextInt(1000)).movePointLeft(1);
            members.add(new Member(String.format("P%05d", i), skill));
        }
        // form() 1단계와 같은 정렬 (실력 내림차순, 동률 시 ID 오름차순) — improve의 실제 입력 조건 재현
        members.sort(Comparator.comparing(Member::skill).reversed()
                .thenComparing(Member::participationId));

        /* 허용폭 절대값 = 팀 평균 합 × (비율/100) — ImproveEquivalenceCheck.tolerance()와 같은 식 */
        // 모든 참가 인원들의 운동 합.
        double totalSkill = members.stream().mapToDouble(m -> m.skill().doubleValue()).sum();
        // 모든 참가 인원들의 운동 합에 대한 평균
        double avgTeamSum = totalSkill / teamCount;
        // 평균에 대한 퍼센티지를 산출 : 이것이 합 편차 차이에 대한 허용 범위.
        tolerance = avgTeamSum * (CAP_PERCENT / 100.0);

        // 고정 시작 배정 (greedy). 순차·병렬 두 벤치가 같은 이 원본을 공유해 공정 비교.
        greedySeed = engine.seedGreedy(members, teamCount, TEAM_CAPACITY);
    }

    @Setup(Level.Invocation)           // 측정 대상 호출 1번마다 1회. 측정 시간 밖
    public void copyStartingAssignment() {
        // A안과 같은 깊은 복사 — 바깥 리스트·팀 리스트만 새로, Member(불변 record)는 공유.
        List<List<Member>> copy = new ArrayList<>(greedySeed.size());
        for (List<Member> team : greedySeed) {
            copy.add(new ArrayList<>(team));
        }
        teams = copy;
    }

    @Benchmark                         // 측정 대상 1 — 순차 (배율의 분모)
    public List<List<Member>> sequential() {
        engine.improveSequential(teams, tolerance);
        return teams;                  // 반환 → Blackhole 소비 (죽은 코드 제거 차단)
    }

    @Benchmark                         // 측정 대상 2 — fork-join 병렬 (배율의 분자)
    public List<List<Member>> parallel() {
        engine.improve(teams, tolerance);
        return teams;
    }
}
