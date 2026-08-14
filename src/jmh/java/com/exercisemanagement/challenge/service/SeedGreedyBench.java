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
 * seedGreedy(현재 활성 버전 = 버전 2, 우선순위 큐 — 버전 4와의 백투백 재측정) 실측 벤치마크.
 */
@BenchmarkMode(Mode.AverageTime)          // 보고 형식: 호출 1회당 평균 시간
@OutputTimeUnit(TimeUnit.MICROSECONDS)    // 보고 단위: us/op
@State(Scope.Benchmark)                   // 아래 필드들 = 벤치마크 입력 상태 (상수 접기 차단)
@Fork(2)                                  // JVM 프로세스 2개, 차례로 (프로파일 오염 차단)
@Warmup(iterations = 5, time = 1)         // 버리는 1초 블록 5개 (JIT 안정화 대기) — A/B 공통
//@Measurement(iterations = 5, time = 1)  // 실험 A용: 재는 1초 블록 5개 → fork당 표본 5개
@Measurement(iterations = 5, time = 3)    // 실험 B용: 재는 3초 블록 5개 → fork당 표본 5개
public class SeedGreedyBench {

    /** 전체 인원 N — A/B 공통 파라미터. 값마다 전체 측정(fork부터)을 처음부터 다시 돈다. */
    @Param({"3000", "30000", "300000"})
    int memberCount;

    // [전환 2/3] 팀 수 K의 정의: A ↔ B 전환 시 아래 두 블록의 주석을 맞바꿀 것.

    // ---------- 실험 A (비활성): K도 @Param → N과의 데카르트 곱 9조합 ----------
    /** 팀 수 K. memberCount와 조합되어 3×3 그리드가 된다. 정원은 setup에서 N/K로 파생. */
//    @Param({"3", "30", "300"})
//    int teamCount;

    // ---------- 실험 B (활성): 정원 10 고정, K는 setup에서 N/10로 파생 ----------
    /** 팀 정원 고정값. 비즈니스 가정(종목이 정하는 정원, 대표값 10)에 근거한다. */
    static final int TEAM_CAPACITY = 10;

    /** 팀 수 K. @Param이 아니라 setup에서 memberCount / TEAM_CAPACITY 로 파생된다. */
    int teamCount;

    // ----------- 실험 A / B 테스트 별 첫 번째 옵션 선택 끝
    
    /** 팀 정원. 실험에 따라 setup에서 파생된다 (A: N/K, B: 고정 10). */
    int teamCapacity;

    TeamFormationEngine engine;
    List<Member> sorted;                  // 입력 데이터 — @State 필드라서 컴파일러가 상수로 취급 불가

    @Setup(Level.Trial)                   // fork(JVM)당 1회 실행, 측정 시간에 포함 안 됨
    public void setup() {

        // [전환 3/3] 파생 값 계산: A ↔ B 전환 시 아래 두 블록의 주석을 맞바꿀 것.
        //            두 실험 모두 엔진의 전제(인원 = 팀 수 × 정원)를 정확히 만족한다.

        // ---------- 실험 A (비활성): 정원 = N / K (전 조합 정수, 최소 10) ----------
//        teamCapacity = memberCount / teamCount;

        // ---------- 실험 B (활성): 팀 수 = N / 정원(10) → 300, 3,000, 30,000팀 ----------
        teamCount = memberCount / TEAM_CAPACITY;
        teamCapacity = TEAM_CAPACITY;

        // ----------- 실험 A / B 테스트 별 두 번째 옵션 선택 끝

        engine = new TeamFormationEngine();

        // 고정 시드 → 매 실행 같은 입력 (측정 재현성). 실력 0.0 ~ 99.9
        Random rnd = new Random(42);

        /* 멤버들 생성 : 바로 밑의 for 문 돌려서 각 멤버 별로 랜덤 값(실력 볼륨, 무게 x 횟수 값)을 넣어서 구성 후
            바로 다음 과정으로 내림차순 정렬 후 "sorted"에 넣는 용도
         */
        List<Member> members = new ArrayList<>(memberCount);

        for (int i = 0; i < memberCount; i++) {

            BigDecimal skill = BigDecimal.valueOf(rnd.nextInt(1000)).movePointLeft(1);
            members.add(new Member(String.format("P%05d", i), skill));
        }

        // form() 1단계와 동일한 정렬(실력 내림차순, 동률 시 ID 오름차순) — seedGreedy의 실제 입력 조건 재현
        members.sort(Comparator.comparing(Member::skill).reversed()
                .thenComparing(Member::participationId));
        sorted = members;
    }

    @Benchmark                            // 측정 대상. 이 메소드 본문만 시간에 잡힌다
    public List<List<Member>> run() {
        // 반환값을 return → JMH 래퍼가 Blackhole로 소비 (죽은 코드 제거 차단)
        // teamCount, teamCapacity는 setup에서 실험(A/B)에 맞게 파생 완료된 상태
        return engine.seedGreedy(sorted, teamCount, teamCapacity);
    }
}
