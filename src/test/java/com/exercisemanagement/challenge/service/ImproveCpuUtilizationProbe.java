package com.exercisemanagement.challenge.service;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.exercisemanagement.challenge.service.TeamFormationEngine.Member;

/**
 * 교환 반복의 CPU 점유를 재는 진단용 프로그램이다. JMH 벤치마크가 아니다.
 *
 * <p>물리 코어가 여섯이고 논리 프로세서가 열둘인데 배율이 2.4에서 멈추는 이유를,
 * 코어가 노는 것인지 바쁜데 낭비하는 것인지로 가르려고 만들었다.
 *
 * <p>재는 것은 셋이다.
 *
 * <ol>
 *   <li>평균 바쁜 코어 수. 프로세스 CPU 시간을 호출 한 번에 걸린 시간으로 나눈 값이다.
 *       배율과 비슷하면 코어가 노는 것이고, 배율보다 훨씬 크면 코어는 바쁜데 낭비하는 것이다.</li>
 *   <li>워커별 CPU. fork-join 워커 각각이 쓴 CPU 시간이다.
 *       최댓값과 최솟값이 크게 벌어지면 일이 한쪽으로 쏠린 것이다.
 *       이 값을 보려면 엔진의 pool.shutdown()이 주석 상태여야 한다.</li>
 *   <li>프로세스 CPU 시간을 쓴 주체별로 나눈 값. main과 워커와 기타 자바 스레드와 자바 아닌 스레드,
 *       그리고 GC 시간이다. 워커 밖에서 늘어난 CPU 시간이 GC로 설명되는지를 가른다.</li>
 * </ol>
 *
 * <p>기타 자바 스레드는 main도 워커도 아닌 자바 스레드다. ThreadMXBean으로 하나씩 잰다.
 * 자바 아닌 스레드는 프로세스 CPU 시간에서 자바 스레드 CPU 합을 뺀 나머지다.
 * ThreadMXBean이 식별자를 주지 않아 스레드별로 잴 수 없는 몫이고,
 * GC 작업 스레드와 JIT 컴파일러 스레드와 VM Thread 등이 여기 들어간다.
 * JIT 컴파일 시간은 2026-07-28 결정으로 측정 계획에서 뺐고, 관련 코드는 지우지 않고 주석으로 보존한다.
 *
 * <p>평균 바쁜 코어 수를 읽을 때 주의할 것이 있다. CPU 시간은 코어가 실제로 실행 중일 때만 쌓이고
 * 노는 코어는 전혀 기여하지 않는다. 그래서 이 값은 코어가 내내 꽉 찼다는 뜻이 아니라
 * 노는 시간까지 포함한 평균이다. 코어가 여섯일 때 앞 3ms는 여섯 개가 돌고 뒤 7ms는 하나만 돌았다면
 * 값은 2.5가 된다. 상한은 논리 프로세서 수인 12다. 물리 코어는 여섯이므로 값이 8이라고 해서
 * 물리 코어 여덟 개가 돈 것은 아니다. 또 이 값은 평균이라 언제 몇 개가 돌았는지는 담지 않는다.
 * 그 분포는 워커별 CPU로 본다.
 *
 * <p>설계에서 중요한 것이 셋이다.
 * 프로세스 CPU 시간은 종료된 스레드 몫까지 누적되므로 호출이 풀을 닫아도 첫째 값은 정확하다.
 * 워커별 CPU는 살아 있는 스레드만 읽히므로 엔진의 pool.shutdown()이 주석 상태여야 한다.
 * 워커는 데몬 스레드라 프로그램은 정상 종료하고, 측정이 끝나면 그 줄을 되살린다.
 * 이번 호출이 만든 워커만 고르려고 호출 직전과 직후의 워커 식별자 집합을 견준다.
 *
 * <p>대조군으로 순차 구현도 함께 잰다. 순차는 스레드가 하나라 평균 바쁜 코어 수가 1.0 근처여야 정상이고,
 * 이것이 이 프로그램 자체의 검산이 된다.
 *
 * <p>쓰는 계측 API는 다섯이다.
 * OperatingSystemMXBean.getProcessCpuTime()은 프로세스의 누적 CPU 시간을 준다. 미지원이면 -1이다.
 * ThreadMXBean.getCurrentThreadCpuTime()은 main 스레드의 CPU 시간을 준다.
 * ThreadMXBean.getAllThreadIds()와 getThreadInfo()는 워커 스레드를 찾는 데 쓴다.
 * ThreadMXBean.getThreadCpuTime(id)는 스레드 하나의 CPU 시간을 준다. 죽었으면 -1이다.
 * System.nanoTime()은 호출 한 번에 걸린 시간을 재는 데 쓴다.
 *
 * <p>실행은 src/test 소스 세트에서 이 클래스의 main을 돌린다. 인자로 인원 수를 지정할 수 있고 기본은 200이다.
 * 인원이 60 이하이면 실행 시간이 짧아 프로세스 CPU 타이머 해상도에 묻히므로 큰 인원에서만 뜻이 있다.
 *
 * <p>주의할 것이 셋이다. 이 값들은 진단용이고 문서에 넣을 배율은 JMH로 따로 잰다.
 * 측정이 모두 끝나면 엔진의 pool.shutdown()을 되살린다.
 * Windows의 스레드 CPU 해상도가 15.625ms라 작은 값은 0으로 잡히거나 계단처럼 나오고,
 * GC 시간은 CPU 시간이 아니라 실제 흐른 시간 기준의 근사값이다.
 */
public class ImproveCpuUtilizationProbe {

    private static final int TEAM_CAPACITY = 10;      // 정원 고정 (Phase3와 같은 비즈니스 가정)
    private static final long SEED = 42L;             // 고정 시드 → 매번 같은 입력
    private static final double CAP_PERCENT = 10.0;   // 허용폭 10%
//    private static final int[] DEFAULT_LOADS = {120, 200};   // [이전 설정 · 보존] run1~4용 — N 두 점, 병렬도는 엔진 기본값(2026-07-27 종료)
    private static final int[] DEFAULT_LOADS = {200};          // [현재 활성 설정] workSize 스윕 — N=200 한 점(N=120은 실행 시간이 짧아 값이 흔들려 제외)

    /* [보존] 스레드 수 스윕에 쓰던 풀 병렬도 목록이다. 2026-07-28에 실행을 마쳤다.
       1은 분할과 복사와 병합을 그대로 하면서 실행만 한 스레드로 하는 순차 대조용이다. */
//    private static final int[] PARALLELISMS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    /* [보존] 풀 병렬도 12 한 점이다. 2026-08-13 분할 13~23 스윕에 썼다.
       말단 수가 워커 수의 정수배가 아닌 것이 느려짐의 원인인지 보려고 병렬도를 12로 고정했다.
       열한 점이 20.8에서 26.0ms로 모두 정상이었고 분할 18도 24.0ms였다.
       정수배가 아니라서 느려진다는 것은 이 실행으로 아니라고 나왔다. */
//    private static final int[] PARALLELISMS = {12};

    /* [보존] 풀 병렬도 두 점이다. 2026-08-13과 08-14 증분 재계산 15점 스윕 여섯 번에 썼다.
       6은 물리 코어 수이고 12는 논리 프로세서 수다. */
//    private static final int[] PARALLELISMS = {6, 12};

    /**
     * [활성] 풀 병렬도 6 한 점이다. JFR 할당 기록 회차에서만 쓴다.
     *
     * <p>2026-08-05 JFR 회차가 병렬도 6 한 점이었다. 그 회차는 스트림 구현을 대상으로
     * 호출 1회 9,522.1MB가 어느 줄에서 나오는지 확정한 것이고, 이번 회차는 같은 자리의
     * 증분 재계산 값이다. 클래스별 할당 표를 그 회차와 한 줄씩 맞대려고 같은 점으로 맞춘다.
     *
     * <p>스트림 구현에서는 총 할당량이 병렬도와 무관했다.
     * 순차 9,517.9MB, 병렬도 6이 9,522.1MB, 병렬도 12가 9,521.4MB였다.
     * 증분 재계산에서는 무관하지 않다. 분할 36에서 병렬도 6이 4.44MB, 병렬도 12가 5.24MB다.
     * 차이는 main 몫에서 나온다(0.45MB 대 1.31MB). 그 출처를 이번 기록으로 확인한다.
     */
    private static final int[] PARALLELISMS = {6};

    /* [보존] 분할 중단 크기 스윕에 쓰던 목록이다. 2026-07-28에 마쳤다.
       말단 작업 하나가 맡는 짝 개수를 1부터 12까지 바꿔 가며 쟀다.
       반씩 자르는 규칙이라 실제 분할 개수가 190, 126, 64, 64, 62, 32처럼 뛰어
       분할 6이나 12 같은 지점을 볼 수 없었다. */
//    private static final int[] WORKSIZES = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    /* [보존] 분할 개수 1부터 36까지 전 구간을 재던 목록이다. 2026-07-28에 마쳤다. */
//    private static final int[] SPLIT_COUNTS = {
//            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
//            13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
//            25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36};

    /* [보존] 목표 분할 개수 13부터 23까지 열한 점이다. 2026-08-13 정수배 확인에 썼다.
       열다섯 점 목록에서 비어 있던 구간이고, 병렬도 12에서 말단이 워커보다 많으면서 정수배가 아닌 구간이다.
       열한 점이 20.8에서 26.0ms로 모두 정상이었고 분할 18도 24.0ms였다. */
//    private static final int[] SPLIT_COUNTS = {13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23};

    /* [보존] 목표 분할 개수 열다섯 점이다. 2026-08-13과 08-14 증분 재계산 측정 여섯 번에 썼다.
       나누는 대상은 팀 짝이고 인원이 200명이면 190개다.
       1부터 12까지는 동시에 도는 말단 개수가 변하는 구간이라 전부 넣었고,
       13 이상은 값이 유지되는지 확인하려고 18과 24와 36 세 점만 두었다. */
//    private static final int[] SPLIT_COUNTS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 18, 24, 36};

    /**
     * [활성] 목표 분할 개수 36 한 점이다. JFR 할당 기록 회차에서만 쓴다.
     *
     * <p>분할 36은 2026-08-04 클럭 관찰과 for문 전환 앞뒤의 할당 실측,
     * 2026-08-05 JFR 회차가 모두 쓴 점이다. 세 구현의 할당을 같은 자리에서 맞대려고 36으로 맞춘다.
     *
     * <p>스트림 구현에서는 총 할당량이 분할 개수와 무관해 어느 점이어도 됐다.
     * 증분 재계산에서는 무관하지 않다. 분할 1개당 할당이 123에서 140KB로 거의 일정해
     * 총량이 분할 개수에 비례한다. 말단마다 붙는 몫이 있다는 뜻이고, 그 정체를 이번 기록으로 확인한다.
     */
    private static final int[] SPLIT_COUNTS = {36};

    /* [보존] 워밍업 2회다. 2026-08-13 첫 실행에 썼다.
       증분 재계산으로 호출 하나가 57ms가 되면서 워밍업 2회가 0.1초 남짓이 되었고,
       병렬 경로의 첫 두 설정인 병렬도 6의 분할 1과 2에서 측정 5회 안에 값이 계속 내려갔다. */
//    private static final int WARMUP = 2;

    /* [보존] 워밍업 5회다. 2026-08-13 두 번째 실행에 썼다.
       병렬도 6 분할 2는 자리를 잡았고, 병렬 경로가 처음 도는 병렬도 6 분할 1만 남았다.
       그 설정의 측정 5회가 155.2에서 103.6ms로 계속 내려갔다. */
//    private static final int WARMUP = 5;

    /* [보존] 워밍업 5회다. 2026-08-13과 08-14 증분 재계산 측정 여섯 번에 썼다.
       위 보존 항목과 값은 같고 쓰인 회차가 다르다. */
//    private static final int WARMUP = 5;             // JIT 컴파일 안정화용, 버리는 실행

    /* [활성] 워밍업 100회다. JFR 할당 기록 회차에서만 쓴다.
       JIT 안정화가 아니라 JFR 표본을 모으려고 늘린 값이다.
       표본은 스레드가 TLAB을 새로 받는 순간에 뽑히므로 총 할당량이 적으면 표본도 적게 나온다.
       2026-08-05 JFR 회차는 기록 40초에 표본 11,568개였고 그때 호출 1회 할당이 9,522.1MB였다.
       증분 재계산은 호출 1회가 4.44MB라, 워밍업 5회로 두면 그 설정 총 할당이 49MB에 그쳐
       표본이 백 개 안팎이 된다. 100회로 두면 호출 106번에 471MB가 되어 표본이 천 개를 넘는다.
       워밍업 호출은 값을 버리므로 측정 결과와 클래스별 할당 구성은 달라지지 않는다.
       JFR 기록이 끝나면 5로 되돌린다. */
    private static final int WARMUP = 100;
    private static final int MEASURE = 5;             // 평균낼 측정 실행 수

    /** 죽은 코드 제거를 막으려고 두는 소비용 필드다. 교환 반복의 결과를 반드시 읽게 한다. */
    private static volatile double sink;

    /* 기타 자바 스레드 목록을 담아 두는 곳이다.
       measure()가 주체를 나눌 때 기타로 분류한 스레드를 식별자별로 여기에 모은다.
       otherJavaCpu는 식별자마다 누적 CPU 증가분과 그 스레드가 잡힌 측정 횟수를 담고,
       otherJavaName은 스레드 이름을 담는다. 이름은 처음 만났을 때 한 번만 읽는다.
       표의 기타 자바 스레드 열은 합계만 보여 주므로, 그 안에 어떤 스레드가 있었는지는
       모든 표가 끝난 뒤 printOtherJavaThreads()가 따로 출력한다. */
    private static final Map<Long, long[]> otherJavaCpu = new HashMap<>();
    private static final Map<Long, String> otherJavaName = new HashMap<>();

    public static void main(String[] args) {
        int[] loads = parseLoads(args);

        // 측정 계측기 3종 준비
        //   procs   : 논리 코어 수 → 평균 바쁜 코어의 상한(결과가 이 값을 넘을 수 없음)
        //   threads : 스레드별 CPU 계측 창구 → main몫·워커별 CPU를 읽는 데 씀
        //   os      : 프로세스 전체 CPU 계측 창구 → 평균 바쁜 코어의 분자(프로세스 CPU 시간)
        int procs = Runtime.getRuntime().availableProcessors();       // 논리 코어 수(이 기계 12) = improve() 풀 병렬도이자 평균코어 상한
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();   // 스레드 CPU 계측 창구(getCurrentThreadCpuTime·getThreadCpuTime)
        com.sun.management.OperatingSystemMXBean os =                 // 프로세스 CPU 계측 창구
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();  // 표준엔 getProcessCpuTime 없어 com.sun 확장으로 캐스팅

        if (threads.isThreadCpuTimeSupported() && !threads.isThreadCpuTimeEnabled()) {
            threads.setThreadCpuTimeEnabled(true);
        }

        /* [할당 계측] 스레드별 할당 바이트를 읽을 준비.
           표준 ThreadMXBean에는 getThreadAllocatedBytes가 없다. 위에서 받은 같은 객체를
           com.sun 확장 타입으로도 잡아 두면 그 메서드를 부를 수 있다(os를 캐스팅한 것과 같은 방식).
           켜는 일은 JVM 전체에 한 번만 하면 되므로 여기서 처리한다. */
        com.sun.management.ThreadMXBean allocThreads = (com.sun.management.ThreadMXBean) threads;
        if (allocThreads.isThreadAllocatedMemorySupported() && !allocThreads.isThreadAllocatedMemoryEnabled()) {
            allocThreads.setThreadAllocatedMemoryEnabled(true);
        }

        boolean procCpuOk = os.getProcessCpuTime() >= 0;

        System.out.println("=== improve() CPU 점유 측정판 (분할 개수 스윕 · 병렬도 6·12 고정 · CPU 시간을 쓴 스레드별로 나눠 측정 · 진단용) ===");
        System.out.printf("availableProcessors()=%d (논리). 이 기계: 물리 6 / 논리 12. 풀 병렬도를 6과 12 두 점으로 고정하고, 각각에서 목표 분할 개수를 스윕한다.%n", procs);
        System.out.printf("PID=%d — 보조 관찰: 실행 중 별도 창에서 [jcmd %d Thread.print] 1회 실행하면 JVM 내부 스레드 목록을 볼 수 있다%n",
                ProcessHandle.current().pid(), ProcessHandle.current().pid());
        System.out.printf("프로세스 CPU 타이머: %s%n", procCpuOk ? "지원" : "미지원(-1) → 평균코어 수치 무의미");
        // [할당 계측] 꺼져 있으면 값이 -1로 나오고 0으로 담기므로, 할당 0을 실측으로 오해하지 않도록 상태를 찍는다
        System.out.printf("스레드별 할당 바이트 계측: %s%n",
                allocThreads.isThreadAllocatedMemoryEnabled() ? "지원(켬)" : "미지원 또는 꺼짐 → 할당 값 무의미");
        System.out.printf("고정 입력: 정원 %d · 시드 %d · 허용폭 %.0f%% · 시작=greedy   |   워밍업 %d · 측정 %d회 평균%n",
                TEAM_CAPACITY, SEED, CAP_PERCENT, WARMUP, MEASURE);
        System.out.print("측정 N: ");
        for (int n : loads) System.out.print(n + " ");
        System.out.println();
        System.out.print("스윕 병렬도: ");
        for (int p : PARALLELISMS) System.out.print(p + " ");
        System.out.println();
        System.out.print("스윕 목표 분할 개수: ");
        for (int s : SPLIT_COUNTS) System.out.print(s + " ");
        System.out.println();
        System.out.println("(워커별 장부는 엔진 improve()의 pool.shutdown()이 주석 상태여야 채워진다 — 현재 주석 상태)");
        System.out.println();

        System.out.println("[상세 로그 · 임시] IMPLOG 줄 형식 — 측정 5회에서만 기록(워밍업·워커 장부 실행·순차는 제외), 파이썬 파싱용 쉼표 구분");
        System.out.println("  IMPLOG,leaf,스레드,병렬도,분할,회차,스캔,복사ns,4중루프ns,sumDev횟수,sumDevns,distDev횟수,distDevns,시작시각ms");
        System.out.println("  IMPLOG,scan,main,병렬도,분할,회차,스캔,바퀴ns,시작시각ms");
        System.out.println("  IMPLOG,run,구현,병렬도,분할,회차,elapsedNs,procCPUns,mainCPUns,workerCPUns,기타자바ns,자바아닌ns,GCms,main할당B,워커할당B,기타할당B");
        System.out.println("  · run = 측정 회차별 원값(순차 포함, 순차는 병렬도·분할이 0). busyCore와 speedup은 파생값이라 로그에 없고 엑셀 변환이 계산한다");
        System.out.println("  · main할당B·워커할당B·기타할당B = 그 회차에 main과 워커와 기타 Java 스레드가 각각 새로 만든 객체의 누적 바이트 증가분. 셋을 더하면 호출 1회의 총 할당량이다");
        System.out.println("  · 시작시각ms = 그 구간이 시작된 시각(System.currentTimeMillis, epoch ms). typeperf 클럭 CSV의 시각 열과 매핑용");
        System.out.println("  · 시간은 전부 ns. sumDev횟수는 [계측] 줄의 평가 횟수와, distDev횟수는 통과 횟수와 같아야 한다(교차 검산용)");
        System.out.println("  · 두 메소드의 순회 크기는 실행 내내 상수라(N=200 기준 sumDev 200명, distDev 400명 분량) 레코드에 넣지 않는다");
        System.out.println("  · 이 로그가 있는 실행은 평가마다 nanoTime이 추가되어(약 1~2%) 절대값을 기존 스윕 결과와 직접 비교하지 않는다");
        System.out.println();

        System.out.println("[열 설명]");
        System.out.println("  팀당 인원 : 팀 하나에 배정한 인원 수(정원). 이번 측정은 10으로 고정");
        System.out.println("  작업 분할 개수 : 이 설정에서 실제로 만들어지는 말단 작업 수. 목표 분할 개수와 같아야 한다 (순차 행은 '-')");
        System.out.println("  스레드   : 풀 병렬도 (순차 행은 '-')");
        System.out.println("  elapsed  : 메소드 전체 수행 시간(평균, ns)");
        System.out.println("  procCPU  : 프로세스 전체 CPU 시간(평균, ns) = 코어들이 일한 시간의 총합");
        System.out.println("  busyCore : 평균 바쁜 코어 수 = procCPU ÷ elapsed (상한 논리 12 / 물리 6)");
        System.out.println("  mainCPU  : main 스레드 CPU 시간(ns)");
        System.out.println("  workerCPU: ForkJoinPool 워커 스레드 CPU 합(ns) — 이번 호출 구간의 증가분");
        System.out.println("  기타 자바 스레드 : main·워커 외 Java 스레드 CPU 합(ns) - 출처 : getThreadInfo() ");
        System.out.println("  자바 아닌 스레드 : procCPU에서 Java 스레드 CPU 합(main + 워커 + 기타 자바 스레드)을 뺀 나머지(ns).\n"
                + "                     ThreadMXBean이 id를 주지 않아 스레드별로 잴 수 없는 몫이다.\n"
                + "                     GC 작업 스레드·JIT 컴파일러 스레드·VM Thread 등이 들어가고, 측정에서 빠진 몫도 섞인다.");
//        System.out.println("  GC / JIT : GC 수집 시간 증가분(실제 흐른 시간 근사) / JIT 컴파일 시간 증가분(ms) — 잔여를 설명할 후보");   // [이전 설정 · 보존] JIT 제외 전(2026-07-28)
        System.out.println("  GC       : GC 수집 시간 증가분(ms, 실제 흐른 시간 근사). '자바 아닌 스레드' 값을 설명할 후보");
        System.out.println("  speedup  : 순차 elapsed ÷ 병렬 elapsed (순차 행은 '-')");
        System.out.println();
        // [이전 설정 · 보존] JIT 열이 있던 13열 머리줄(2026-07-28 제외)
//        System.out.printf("%-5s %-6s %6s %10s %10s %8s %9s %10s %10s %9s %6s %6s %8s%n",
//                "N", "구현", "스레드", "elapsed", "procCPU", "busyCore", "mainCPU", "workerCPU", "otherJava", "jvm잔여", "GC", "JIT", "speedup");
        // [이전 설정 · 보존] 단위 표기 전 머리줄(2026-07-28 교체). 한글 열은 폭이 어긋나 값과 자리가 맞지 않았다.
//        System.out.printf("%-5s %-6s %6s %10s %10s %8s %9s %10s %10s %9s %6s %8s%n",
//                "N", "구현", "스레드", "elapsed", "procCPU", "busyCore", "mainCPU", "workerCPU", "otherJava", "jvm잔여", "GC", "speedup");
        printCells(COLS);                       // 머리줄 — 열 이름에 단위를 괄호로 붙인 COLS 그대로
        System.out.println(ruleLine(COL_CJK, COL_ASCII));   // 구분선 — 값 줄과 같은 글자 구성으로 만든다

        TeamFormationEngine engine = new TeamFormationEngine();

        for (int n : loads) {
            // 이 N의 고정 입력 준비 (정원10·시드42·허용폭10%·greedy 시작 배정)
            int teamCount = n / TEAM_CAPACITY;
            List<Member> sorted = buildSortedMembers(n, SEED);
            double tolerance = tolerance(sorted, teamCount);
            List<List<Member>> greedySeed = engine.seedGreedy(sorted, teamCount, TEAM_CAPACITY);

            // 이 N에서의 실행 순서
            //   ① 순차 측정 1회       → 대조군 (busyCore ≈ 1.0이어야 정상)
            //   ② 병렬 측정         → 병렬도 두 점 × workSize 목록으로 본 측정 + CPU 시간을 누가 썼는지 나눠 잰다
            //   ③ 워커 분해         → 워커별 CPU 장부 (병렬 전용 — 순차는 워커가 없음)

            int pairCount = teamCount * (teamCount - 1) / 2;   // 팀 짝 수 K(K-1)/2 — 작업 분할 개수를 세는 구간의 길이

            // ① 순차 측정 — false = improveSequential (풀 없음, main 스레드 하나로 실행. 병렬도·workSize 인자 0은 무시된다)
            System.out.println("[구간 시각 · 임시] " + java.time.LocalTime.now() + " 시작 · 순차 (N=" + n + ")");   // typeperf CSV 시각과 매핑용
            Result seq = measure(engine, greedySeed, tolerance, false, 0, 0, os, threads);
            System.out.println("[구간 시각 · 임시] " + java.time.LocalTime.now() + " 끝 · 순차 (N=" + n + ")");
            printRow(n, "순차", "-", "-", seq, Double.NaN);
            for (String runLine : seq.runLines) System.out.println(runLine);   // [회차별 기록] 순차 5회의 회차별 원값

            // ② 병렬도 두 점 × workSize 스윕 — 같은 입력에서 풀 병렬도와 분할 크기만 바꾼다.
            for (int p : PARALLELISMS) {
                for (int s : SPLIT_COUNTS) {
                    System.out.println("[구간 시각 · 임시] " + java.time.LocalTime.now() + " 시작 · 병렬도 " + p + " 분할 " + s);   // typeperf CSV 시각과 매핑용
                    Result par = measure(engine, greedySeed, tolerance, true, p, s, os, threads);
                    double speedup = seq.elapsedNs / par.elapsedNs;   // 배율 = 순차 elapsed ÷ 병렬 elapsed
                    printRow(n, "병렬", String.valueOf(p), String.valueOf(leafCount(0, pairCount, s)), par, speedup);

                    // [계측] 행 바로 아래 계측 줄. 마지막 값(평가(단말 작업) 1회당 평균 워커 CPU)은 재는 값이 아니라
                    // 표의 workerCPU 열을 계측 줄의 평가 횟수로 나눈 계산값이다.
                    System.out.printf("   [계측] 평가 %.0f회 · 통과 %.0f회 · 복사 합 %.0fns · 평가 반복문 합 %.0fns · 평가(단말 작업) 1회당 평균 워커 CPU %.0fns%n",
                            par.evalCount, par.passCount, par.copyNs, par.evalLoopNs,
                            par.evalCount > 0 ? par.workerNs / par.evalCount : 0.0);

                    /* [할당 계측] 호출 1회당 총 할당과 평가 1회당 할당. 확인할 것은 둘이다.
                       총 할당이 관찰 값 근처인가, 그리고 분할 개수를 바꿔도 총 할당이 같은가(일의 양이 같으니 같아야 맞다). */
                    double allocTotalB = par.mainAllocB + par.workerAllocB + par.otherAllocB;
                    System.out.printf("   [할당] 호출 1회당 총 %.0f바이트 (main %.0f · 워커 %.0f · 기타 %.0f) · 평가 1회당 %.1f바이트%n",
                            allocTotalB, par.mainAllocB, par.workerAllocB, par.otherAllocB,
                            par.evalCount > 0 ? allocTotalB / par.evalCount : 0.0);

                    for (String runLine : par.runLines) System.out.println(runLine);   // [회차별 기록] 측정 5회의 회차별 원값

                    /* [상세 기록] 이 행의 측정 5회 동안 쌓인 IMPLOG 레코드를 출력한다(측정 창 밖).
                       분할 36 행은 말단 레코드만 8,100건이라 줄 수가 많다 — 콘솔 버퍼를 늘리거나 출력을 파일로 받는다. */
                    // [비활성 2026-08-14] 엔진 계측 제거로 주석 처리. 원본 엔진은 docs/병목개선/ForkJoinPool/기타/코드백업 참조.
                    // for (String dlogLine : TeamFormationEngine.drainDetailLog()) {
                    // System.out.println(dlogLine);
                    // }

                    // ③ 워커 분해 — 이 설정으로 improve를 한 번 더 돌려 워커별 CPU 장부를 찍는다.
                    captureWorkerCpu(engine, greedySeed, tolerance, threads, n, p, s);
                    System.out.println("[구간 시각 · 임시] " + java.time.LocalTime.now() + " 끝 · 병렬도 " + p + " 분할 " + s);
                }
            }

            System.out.println();
        }

        printOtherJavaThreads();   // 표가 모두 끝난 뒤 기타 자바 스레드 목록을 한 번 출력한다

//        System.out.printf("(sink=%.6f — 소비 확인용, 무시)%n", sink);
    }

    /**
     * [main 루프 ①·②] 한 (구현 × N × 병렬도)에 대해 WARMUP회 버린 뒤 MEASURE회 재서 평균(호출당, ms).
     * parallel=false면 improveSequential(병렬도 인자 무시), true면 improve(t, tolerance, parallelism).
     * 측정 창(nanoTime)은 improve 호출 구간에 가장 바짝 붙인다(CPU 시계보다 안쪽에서 찍음).
     *
     * [procCPU를 누가 썼는지 나누기 — 2026-07-27 추가] 호출 전후의 차이로 함께 재는 것:
     *   · Java 스레드별 CPU: 전후 전체 스레드 스냅샷을 비교해 증가분을 합산하고, main / ForkJoinPool 워커 / 기타로 분류.
     *     호출 중 새로 생긴 스레드는 누적값 전체를 증가분으로 본다.
     *   · GC 시간: GarbageCollectorMXBean.getCollectionTime() 합의 증가분(실제 흐른 시간 기준 근사).
     *   · JIT 시간: 측정 계획에서 제외(2026-07-28). CompilationMXBean 관련 줄은 아래에 주석으로 남겨 두었다.
     *   · 자바 아닌 스레드 몫 = procCPU 증가분 − Java 스레드 CPU 증가분 합. GC·JIT 스레드는 Java 스레드가 아니라
     *     ThreadMXBean에 잡히지 않아 스레드별로 잴 수 없으므로, 그 몫이 이 값으로 남는다.
     */
    private static Result measure(TeamFormationEngine engine, List<List<Member>> seedTeams,
                                  double tolerance, boolean parallel, int parallelism, int splitCount,
                                  com.sun.management.OperatingSystemMXBean os, ThreadMXBean threads) {

        /* 워밍업 구간이다. 기록 스위치를 켠 채로 돌리고 쌓인 기록은 버린다.
           꺼 둔 채로 돌리면 기록하는 자리가 워밍업에서 한 번도 실행되지 않아,
           측정 1회차에 그 자리가 처음 실행될 때 컴파일해 둔 코드를 버리고 되돌아간다.
           2026-08-13 실측: 병렬도 6 분할 1의 측정 1회차 18ms 지점에서 엔진 1288행이 unstable_if로 되돌아갔다.
           되돌리려면 아래 네 줄을 지우고 다음 한 줄을 넣는다.
             TeamFormationEngine.detailLogEnabled = false; */
        // [비활성 2026-08-14] 엔진 계측 제거로 주석 처리. 원본 엔진은 docs/병목개선/ForkJoinPool/기타/코드백업 참조.
        // TeamFormationEngine.detailLogRunIndex = 0;                      // [상세 기록] 워밍업 표시. 이 기록은 버린다
        // TeamFormationEngine.detailLogEnabled = parallel;                // [상세 기록] 병렬에서만 켠다
        for (int i = 0; i < WARMUP; i++) {                              // 워밍업: JIT 컴파일 안정화용, 측정 없이 버린다
            List<List<Member>> t = deepCopy(seedTeams);                 // 매번 새 시작 배정(improve가 제자리 수정하므로)
            if (parallel) engine.improveBySplitCount(t, tolerance, parallelism, splitCount); else engine.improveSequential(t, tolerance);  // 해당 구현 1회 실행(결과는 버림)
            sink += engine.sumDeviation(t);                             // 결과 소비(죽은 코드 제거 방지)
        }
        // [비활성 2026-08-14] 엔진 계측 제거로 주석 처리. 원본 엔진은 docs/병목개선/ForkJoinPool/기타/코드백업 참조.
        // TeamFormationEngine.detailLogEnabled = false;                   // [상세 기록] 측정 전에 끈다
        // TeamFormationEngine.drainDetailLog();                           // [상세 기록] 워밍업에서 쌓인 기록을 꺼내 버린다

        /* 측정 구간이다. 다섯 번 돌려 평균을 낸다. */
        double sumElapsed = 0, sumProc = 0, sumMain = 0;                // 기존 세 지표의 합 누적기(끝에서 MEASURE로 나눠 평균)
        double sumWorker = 0, sumOtherJava = 0, sumNonJava = 0;         // CPU 시간을 쓴 스레드별로 나눠 담는 누적기(ns)
//        double sumGc = 0, sumJit = 0;                                 // [이전 설정 · 보존] GC·JIT 시간 누적기(ms 단위)
        double sumGc = 0;                                               // GC 시간 누적기(ms 단위)
        double sumMainAllocB = 0, sumWorkerAllocB = 0, sumOtherAllocB = 0;   // [할당 계측] 할당 바이트 누적기(main / 워커 / 기타)
        double sumEvalCount = 0, sumPassCount = 0;                      // [계측] 평가 횟수·통과 횟수 누적기(회)
        double sumCopyNs = 0, sumEvalLoopNs = 0;                        // [계측] 복사·평가 반복문 시간 누적기(ns)
//        java.lang.management.CompilationMXBean jit = ManagementFactory.getCompilationMXBean();   // [JIT 제외 · 보존] JIT 컴파일 시간 계측 창구
        List<String> runLines = new ArrayList<>();                      // [회차별 기록] 측정 5회의 회차별 원값 버퍼 — 출력은 표 행 뒤에 한다(측정 사이 I/O 방지)
        for (int i = 0; i < MEASURE; i++) {                             // 측정: MEASURE회 재서 평균낸다
            List<List<Member>> t = deepCopy(seedTeams);                 // 매번 새 복사본(측정 창 밖 — 시간에 안 잡힘)

            // [비활성 2026-08-14] 엔진 계측 제거로 주석 처리. 원본 엔진은 docs/병목개선/ForkJoinPool/기타/코드백업 참조.

            // TeamFormationEngine.resetImproveInstrumentation();          // [계측] 호출 직전 엔진 카운터 4개를 0으로 리셋

            /* [상세 기록] 이 측정 회차의 식별자와 스위치 — 병렬 호출에서만 켠다.
               끄기는 호출이 끝나고 시간 값들을 읽은 뒤에 한다. 켠 채로 두면 워커 장부 실행까지 기록되기 때문. */
            // [비활성 2026-08-14] 엔진 계측 제거로 주석 처리. 원본 엔진은 docs/병목개선/ForkJoinPool/기타/코드백업 참조.
            // if (parallel) {
            // TeamFormationEngine.detailLogRunIndex = i + 1;
            // TeamFormationEngine.detailLogEnabled = true;
            // }

            Map<Long, long[]> cpuBefore = snapshotAllThreadCpu(threads);  // 호출 직전: 전체 Java 스레드 별 CPU 사용량 목록 - key : ThreadId / value : cpu 시간 - ForkJoinPool 구분자
            /* [추가 계측] */
            long gcBefore  = totalGcTimeMs();                           // 호출 직전: 자바 가상 머신(JVM)에서 가비지 컬렉션(GC) 수행에 소요된 누적 경과 시간을 밀리초(ms)
//            long jitBefore = (jit != null && jit.isCompilationTimeMonitoringSupported()) ? jit.getTotalCompilationTime() : 0;  // [JIT 제외 · 보존] 호출 직전 JIT 누적(ms)
            /* [기존 계측] */
            long procBefore = os.getProcessCpuTime();                   // 호출 직전: 프로세스 누적 CPU
            long mainBefore = threads.getCurrentThreadCpuTime();        // 호출 직전: main 스레드 누적 CPU
            long timeBefore = System.nanoTime();                        // 호출 직전: 경과 시간 기준(호출에 가장 바짝)

            if (parallel) engine.improveBySplitCount(t, tolerance, parallelism, splitCount); else engine.improveSequential(t, tolerance);  // 측정 대상: 이 호출 구간만 잰다

            /* [기존 계측] */
            long timeAfter = System.nanoTime();                         // 호출 직후: 경과 시간(가장 먼저 찍음)
            long mainAfter = threads.getCurrentThreadCpuTime();         // 호출 직후: main 스레드 누적 CPU
            long procAfter = os.getProcessCpuTime();                    // 호출 직후: 프로세스 누적 CPU
            /* [추가 계측] */
            long gcAfter  = totalGcTimeMs();                            // 호출 직후: 자바 가상 머신(JVM)에서 가비지 컬렉션(GC) 수행에 소요된 누적 경과 시간을 밀리초(ms)
//            long jitAfter = (jit != null && jit.isCompilationTimeMonitoringSupported()) ? jit.getTotalCompilationTime() : 0;   // [JIT 제외 · 보존] 호출 직후 JIT 누적(ms)

            Map<Long, long[]> cpuAfter = snapshotAllThreadCpu(threads); // 호출 직후: 전체 Java 스레드 별 CPU 사용량 목록 - key : ThreadId / value : cpu 시간 - ForkJoinPool 구분자

            // [비활성 2026-08-14] 엔진 계측 제거로 주석 처리. 원본 엔진은 docs/병목개선/ForkJoinPool/기타/코드백업 참조.

            // TeamFormationEngine.detailLogEnabled = false;               // [상세 기록] 측정 창 밖에서 끈다 — 뒤따르는 워커 장부 실행은 기록에서 제외

            /* [계측] 호출 직후 엔진 카운터 4개를 읽어 누적한다. 직전에 리셋했으므로 이 호출 한 번의 총합이다.
               순차(improveSequential)는 카운터를 건드리지 않아 전부 0으로 남는다. */
            // [비활성 2026-08-14] 엔진 계측 제거로 주석 처리. 원본 엔진은 docs/병목개선/ForkJoinPool/기타/코드백업 참조.
            // sumEvalCount    += TeamFormationEngine.evalCount.get();
            // sumPassCount    += TeamFormationEngine.passCount.get();
            // sumCopyNs       += TeamFormationEngine.copyTimeNs.get();
            // sumEvalLoopNs   += TeamFormationEngine.evalLoopTimeNs.get();

            sink += engine.sumDeviation(t);                             // 결과 소비(측정 창 밖)

            /* procCPU를 누가 썼는지 나누는 블록
               쓰는 값: cpuBefore(호출 직전 스냅샷)와 cpuAfter(호출 직후 스냅샷).
               둘 다 {스레드 id → [누적 CPU(ns), 분류]} 맵이다. CPU 값은 스레드가 시작된 뒤 쓴 누적치라,
               "직후 누적 − 직전 누적"이 이번 improve() 호출 구간에 쓴 몫이 된다.
               예) 워커 A가 직전 100ms · 직후 620ms → 이번 호출에 520ms를 썼다.
                    호출 중 처음 생긴 워커 B는 직전 스냅샷에 없으므로(prev == null) 누적치 전체가 이번 몫.
               나누는 기준 3갈래: main(스레드 id 일치) / ForkJoinPool 워커(이름이 "ForkJoinPool"로 시작, 분류 1) / 기타(나머지).
               이 세 값과 procCPU의 차이가 뒤에서 표의 '자바 아닌 스레드' 열 값이 된다.
 */
            long mainNs = 0, workerNs = 0, otherNs = 0;                 // 3갈래 누적 변수: main / ForkJoinPool 워커 / 기타 Java 스레드 (단위 ns)
            long mainAllocB = 0, workerAllocB = 0, otherAllocB = 0;     // [할당 계측] main / ForkJoinPool 워커 / 기타로 나눈 할당 바이트 (단위 B)
            long selfId = Thread.currentThread().getId();               // 지금 이 코드를 실행 중인 스레드(main)의 id. main은 이름이 아니라 id로 가른다

            /* 증가분 계산 반복문
               직후 스냅샷(cpuAfter)의 스레드를 한 개씩 꺼내서 다음 세 단계를 반복한다. - 이유는 "Map<Long, long[]> cpuBefore" 와 "Map<Long, long[]> cpuAfter" 안에는
               모든 스레드의 Id 별 실행시간+ForkJoinPool구분자(1 혹은 2 값이 실행시간(Long) 값과 순차적으로 합쳐져 있음)로 찾아내기 위함.
                 1) 같은 id를 직전 스냅샷(cpuBefore)에서 찾는다.
                 2) 증가분(변화량)을 계산한다 (직후 누적 − 직전 누적. 직전에 없던 새 스레드는 누적치 전체).
                 3) 증가분(변화량)을 세 갈래(main / ForkJoinPool 워커 / 기타) 중 맞는 변수에 더한다.
               직전 스냅샷에만 있고 직후에 없는 스레드(호출 중 종료)는 cpuAfter에 안 나오므로 자연히 제외된다.
 */
            for (Map.Entry<Long, long[]> e : cpuAfter.entrySet()) {     // 직후 스냅샷의 모든 스레드를 하나씩 본다
                long[] prev = cpuBefore.get(e.getKey());                 // 같은 id가 직전 스냅샷에도 있었나 (null = 호출 중 새로 생긴 스레드)
                long delta = e.getValue()[0] - (prev != null ? prev[0] : 0);   // 증가분 = 직후 누적 − 직전 누적. 새 스레드는 0을 빼므로 누적치 전체

                /* [할당 계측] 할당은 아래 CPU 증가분 검사보다 앞에서 더한다.
                   Windows 스레드 CPU 해상도가 15.625ms라 CPU 증가분이 0으로 잡히는 스레드가 나오는데,
                   그런 스레드도 할당은 했을 수 있어 검사 뒤에 두면 그 몫이 빠진다. */
                long allocDelta = e.getValue()[2] - (prev != null ? prev[2] : 0);
                if (allocDelta > 0) {
                    if (e.getKey() == selfId)      mainAllocB   += allocDelta;
                    else if (e.getValue()[1] == 1) workerAllocB += allocDelta;
                    else                           otherAllocB  += allocDelta;
                }

                if (delta <= 0) continue;                               // 호출 동안 CPU를 안 썼으면 어느 갈래에도 더하지 않는다
                // == 실제 증분 저장 ==
                if (e.getKey() == selfId)      mainNs   += delta;       // 1갈래: main 스레드의 몫
                else if (e.getValue()[1] == 1) workerNs += delta;       // 2갈래: ForkJoinPool 워커의 몫 (스냅샷 때 이름으로 분류 1을 받아 둠)
                else {
                    otherNs += delta;                                   // 3갈래: Main 스레드와 ForkJoinPool 스레드 동작 시간 외 Java 스레드 전체의 몫 (Common-Cleaner 등)
                    recordOtherJavaThread(threads, e.getKey(), delta);  // 같은 몫을 id별로도 모아 둔다(맨 끝에 목록으로 출력)
                }
            }

            long javaSumNs = mainNs + workerNs + otherNs;               // Java 스레드 CPU 증가분 총합
            /* [기존 계측] */
            sumElapsed += (timeAfter - timeBefore);                     // 이번 호출 스레드의 경과 시간(차이)을 누적
            sumMain    += (mainAfter - mainBefore);                     // 이번 호출 동안의 main CPU(차이)를 누적
            sumProc    += (procAfter - procBefore);                     // 이번 호출 동안의 프로세스 CPU(차이)를 누적

            /* [추가 계측] */
            sumWorker    += workerNs;                                   // ForkJoinPool 워커 CPU 증가분(ns)
            sumOtherJava += otherNs;                                    // main·워커 외 Java 스레드 CPU 증가분(ns)
            sumNonJava   += (procAfter - procBefore) - javaSumNs;       // 자바 아닌 스레드 몫 = procCPU − Java 스레드 합(ns)( main 스레드 값 + ForkJoinPool 스레드 값 + otherNs == getThreadCpuTime() 전체 값 )
            sumGc        += (gcAfter - gcBefore);                       // GC 증가분(ms)
            sumMainAllocB   += mainAllocB;                              // [할당 계측] main 스레드가 새로 만든 바이트
            sumWorkerAllocB += workerAllocB;                            // [할당 계측] 워커들이 새로 만든 바이트
            sumOtherAllocB  += otherAllocB;                             // [할당 계측] 기타 Java 스레드가 새로 만든 바이트
//            sumJit       += (jitAfter - jitBefore);                   // [JIT 제외 · 보존] JIT 증가분(ms)

            /* [회차별 기록] 이 회차의 원값 한 줄. 위 평균 누적은 그대로 두고 추가로 남긴다.
               busyCore와 speedup은 원값에서 나오는 파생값이라 로깅하지 않고 엑셀 변환 스크립트가 계산한다. */
            runLines.add("IMPLOG,run," + (parallel ? "병렬" : "순차")
                    + "," + parallelism + "," + splitCount + "," + (i + 1)
                    + "," + (timeAfter - timeBefore) + "," + (procAfter - procBefore)
                    + "," + (mainAfter - mainBefore) + "," + workerNs + "," + otherNs
                    + "," + ((procAfter - procBefore) - javaSumNs) + "," + (gcAfter - gcBefore)
                    + "," + mainAllocB + "," + workerAllocB + "," + otherAllocB);   // [할당 계측] 할당 3칸 추가
        }

        Result r = new Result();                                        // 평균 결과를 담을 그릇
        /* [기존 계측] */
        r.elapsedNs = sumElapsed / MEASURE;                             // 평균 경과 시간(ns 그대로)
        r.mainNs    = sumMain    / MEASURE;                             // 평균 main 스레드 CPU(ns 그대로)
        r.procNs    = sumProc    / MEASURE;                             // 평균 프로세스 CPU(ns 그대로)

        /* [추가 계측] */
        r.workerNs    = sumWorker    / MEASURE;                         // 평균 ForkJoinPool 워커 CPU 합(ns 그대로)
        r.otherJavaNs = sumOtherJava / MEASURE;                         // 평균 기타 Java 스레드 CPU 합(ns 그대로)
        r.nonJavaNs   = sumNonJava   / MEASURE;                         // 평균 '자바 아닌 스레드' 값(ns 그대로)
        r.gcMs        = sumGc  / MEASURE;                               // 평균 GC 시간(ms)

        /* [할당 계측] 할당 바이트 3갈래의 5회 평균 */
        r.mainAllocB   = sumMainAllocB   / MEASURE;                     // 평균 main 할당(B)
        r.workerAllocB = sumWorkerAllocB / MEASURE;                     // 평균 워커 할당(B)
        r.otherAllocB  = sumOtherAllocB  / MEASURE;                     // 평균 기타 Java 스레드 할당(B)

        /* [계측] 엔진 카운터 4개의 5회 평균 */
        r.evalCount  = sumEvalCount  / MEASURE;                         // 평균 말단 작업(평가 횟수)(회)
        r.passCount  = sumPassCount  / MEASURE;                         // 평균 통과 횟수(회)
        r.copyNs     = sumCopyNs     / MEASURE;                         // 평균 복사 구간 시간 합(ns)
        r.evalLoopNs = sumEvalLoopNs / MEASURE;                         // 평균 평가 반복문 구간 시간 합(ns)
//        r.jitMs       = sumJit / MEASURE;                             // [JIT 제외 · 보존] 평균 JIT 시간(ms)
        r.runLines = runLines;                                          // [회차별 기록] 회차별 원값 줄 — main()이 표 행 뒤에 출력한다
        return r;                                                       // 이 (구현×N×병렬도)의 평균 지표 반환
    }

    /** 살아있는 모든 Java 스레드의 {id → [누적 CPU(ns), 분류, 누적 할당 바이트]} 스냅샷. 분류: 1 = ForkJoinPool 워커, 2 = 그 외(main 포함 — main은 호출부에서 id로 따로 가른다).
     *  [할당 계측] 세 번째 값은 그 스레드가 시작된 뒤 새로 만든 객체의 누적 바이트다. CPU 시간과 같은 누적값이라 호출 앞뒤로 두 번 읽어 뺀다. */
    private static Map<Long, long[]> snapshotAllThreadCpu(ThreadMXBean threads) {

        com.sun.management.ThreadMXBean alloc = (com.sun.management.ThreadMXBean) threads;   // 같은 객체를 확장 타입으로 본다. 표준 ThreadMXBean에는 getThreadAllocatedBytes가 없다
        Map<Long, long[]> m = new HashMap<>();                                               // 돌려줄 스냅샷. 열쇠 = 스레드 id, 값 = 칸 셋짜리 배열

        for (long id : threads.getAllThreadIds()) {                     // 지금 살아있는 Java 스레드 id를 하나씩 본다

            long cpu = threads.getThreadCpuTime(id);                    // 이 스레드가 시작된 뒤 코어에서 실행된 누적 시간(ns)
            if (cpu < 0) continue;                                      // -1 = 이미 종료된 스레드. 스냅샷에 넣지 않는다

            long allocB = alloc.getThreadAllocatedBytes(id);            // 이 스레드가 시작된 뒤 새로 만든 객체의 누적 바이트
            if (allocB < 0) allocB = 0;                                 // -1 = 종료됐거나 계측이 꺼진 상태. 뒤에서 빼는 값이 음수가 되지 않게 0으로 둔다

            ThreadInfo info = threads.getThreadInfo(id);                // 스레드 이름을 얻는다. 바로 아래에서 ForkJoinPool 워커인지 판정하는 데 쓴다
            String name = (info != null) ? info.getThreadName() : "";   // 정보를 못 받으면(그 사이에 종료) 빈 이름으로 둔다
            long kind = (name != null && name.startsWith("ForkJoinPool")) ? 1 : 2;   // 이름이 ForkJoinPool로 시작하면 워커(1), 아니면 그 외(2)

            m.put(id, new long[]{cpu, kind, allocB});                   // 한 스레드분을 담는다. 칸 순서 = [0] 누적 CPU, [1] 분류(1 워커, 2 그 외), [2] 누적 할당
//            System.out.println("Debug_snapshotAllThreadCpu: " + id + " " + name + " " + cpu + " " + kind);
        }

        return m;                                                       // 호출부(measure)가 호출 앞뒤로 두 벌을 받아 빼면 그 구간의 몫이 된다
    }

    /**
     * 모든 GC 빈의 수집 시간을 더한 누적 합(ms).
     *
     * [쓰는 곳] measure()가 improve() 호출 직전·직후에 한 번씩 부른다. getCollectionTime()은 JVM 시작 이후
     *           계속 쌓이는 누적값이라, 두 값의 차이로만 그 호출 구간의 GC 시간을 얻는다.
     * [t > 0 검사] getCollectionTime()은 그 수집기에 대해 정의되지 않으면 -1을 돌려준다(Javadoc 명시).
     *              음수가 합에 섞이는 것을 막는다.
     * [해석 주의]
     *   1) 실제 흐른 시간 기준이라 CPU 시간이 아니다. G1은 수집을 여러 스레드로 나눠 하므로, 같은 구간의 CPU 시간은
     *      이 값의 몇 배가 될 수 있다. '자바 아닌 스레드' 값에서 이 값을 그대로 빼지 말고, 그 몫을 설명할 후보로만 쓴다.
     *   2) 수집 시간이 아주 짧으면 수집 횟수가 늘어도 값이 그대로일 수 있다(Javadoc 명시). 0ms로 찍혀도
     *      수집은 여러 번 일어났을 수 있다. 필요하면 getCollectionCount()를 함께 찍는다.
     */
    private static long totalGcTimeMs() {
        long sum = 0;
        /* ManagementFactory.getGarbageCollectorMXBeans() 란
             - ManagementFactory : JVM 상태를 알려주는 관리용 객체(MXBean)를 얻는 진입점.
                                   이 프로브의 ThreadMXBean·OperatingSystemMXBean·CompilationMXBean도 여기서 얻는다.
             - 목록인 이유       : GC가 세대별로 나뉘어 있다. JDK 17 기본 G1이면 빈 2개.
                                     · G1 Young Generation : Eden이 찰 때 도는 수집
                                     · G1 Old Generation   : Old를 대상으로 하는 수집
                                   수집기를 바꾸면 이름과 개수가 달라진다.
             - 빈에서 읽는 값    : getName() / getCollectionCount() / getCollectionTime(). 여기서는 시간만 쓴다.
             - 매번 새로 받는 이유 : JVM이 실행 중에 빈을 추가·제거할 수 있다(Javadoc 명시). */
        for (java.lang.management.GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long t = gc.getCollectionTime();
            if (t > 0) sum += t;
        }
        return sum;
    }

    /**
     * [main 루프 ③ · 병렬 전용] 워커별 CPU 장부(3.2): improve() 호출 직전·직후의 ForkJoinPool 워커 id 집합을 비교해,
     * 이번 호출이 새로 만든 워커만 골라 누적 CPU 시간을 읽는다(= 이번 호출의 워커별 몫).
     * 순차(improveSequential)는 풀·워커가 없어 이 단계가 없다(그래서 false/true 매개변수도 없다).
     * 엔진 improve()의 pool.shutdown()이 활성이면 호출 후 워커가 죽어 읽히지 않으므로 안내만 찍는다.
     * parallelism·splitCount = 이번 장부를 찍을 설정(스윕 값 그대로 전달).
     */
    private static void captureWorkerCpu(TeamFormationEngine engine, List<List<Member>> seedTeams,
                                         double tolerance, ThreadMXBean threads, int n, int parallelism, int splitCount) {

        // improve() 호출 직전, 지금 살아있는 fork-join 워커 목록.
        //   여기 담기는 건 이번 호출 워커가 아니라, 앞서 돈 ② 병렬 측정(measure(...,true,...))이 남긴
        //   '기존' 워커들이다 — 엔진 improve()의 shutdown이 주석 상태라 그 풀 워커들이 종료되지 않고 유지된
        //   상태라서 잡힌다. 이 목록을 기준선으로 삼아, 아래 after와 비교해 '새로 생긴 워커'만
        //   이번 호출 것으로 골라낸다. (shutdown이 활성이면 남은 워커가 없어 before는 비어 있다.)
        Map<Long, String> before = snapshotFjpWorkers(threads);

        // 이번 호출을 실제로 돌리고, 그 호출이 만든 워커만 골라 CPU를 모은다
        //   improve를 1회 실행(→ 이번 호출의 새 워커 생성) → 직후 워커 목록을 다시 찍고 →
        //   before에 없던 '새 워커'만 추려 그 이름·CPU를 workers 리스트에 담는다.

        List<List<Member>> t = deepCopy(seedTeams);   // improve는 제자리 수정 → 매번 새 시작 배정 복사본
        engine.improveBySplitCount(t, tolerance, parallelism, splitCount);    // 이 설정으로 improve 1회 = 여기서 이번 호출의 새 워커가 생긴다
        sink += engine.sumDeviation(t);               // 결과 소비(죽은 코드 제거 방지)

        Map<Long, String> after = snapshotFjpWorkers(threads);   // 실행 직후 워커 = 기존 워커 + 이번 새 워커

        // 워커 하나 = 이름 + CPU(ms)를 한 객체로 묶는다(이름·CPU를 두 리스트로 나눠 위치로 맞추던 취약함 제거).
        record WorkerCpu(String name, double cpuNs) {}

        // before에 없던 '새 워커'만 추려 이름·CPU를 한 객체로 담는다. anyDead = 죽어서 못 읽은 워커가 있었는지.
        List<WorkerCpu> workers = new ArrayList<>();
        boolean anyDead = false;
        for (Map.Entry<Long, String> e : after.entrySet()) {      // after의 모든 워커를 하나씩 훑는다
            if (before.containsKey(e.getKey())) continue;         // before에도 있던 워커 = 기존 것 → 건너뜀
            long cpu = threads.getThreadCpuTime(e.getKey());      // before에 없던 '새 워커'의 누적 CPU(ns)를 읽는다
            if (cpu < 0) { anyDead = true; continue; }            // -1 = 이미 종료된 워커(shutdown 활성) → 건너뜀
            workers.add(new WorkerCpu(e.getValue(), cpu));        // 이름·CPU를 한 객체로 묶어 담는다(ns 그대로)
        }

        if (workers.isEmpty()) {
            System.out.printf("   [N=%d 스레드 %d 분할 %d 워커별 CPU] 읽을 워커 없음 — 엔진 improve()의 pool.shutdown()이 활성인 듯. "
                    + "그 줄을 주석 처리 후 다시 실행하면 이 장부가 채워진다.%n", n, parallelism, splitCount);
            return;
        }

        // 워커별 CPU를 정렬·집계해서 출력
        //   (1) CPU 많은 순 정렬 → (2) 합·최대·최소 → (3) 워커별 CPU·비중(%)·쏠림 출력.

        // (1) 정렬 — 이름이 같은 객체에 붙어 함께 움직이니 짝이 깨질 일이 없다(CPU 내림차순).
        workers.sort(Comparator.comparingDouble(WorkerCpu::cpuNs).reversed());

        // (2) 집계 — 전체 워커 CPU의 합·최댓값·최솟값을 한 번의 순회로 구한다(비중·쏠림 계산용).
        double sum = 0, max = Double.NEGATIVE_INFINITY, min = Double.POSITIVE_INFINITY;
        for (WorkerCpu w : workers) { sum += w.cpuNs(); max = Math.max(max, w.cpuNs()); min = Math.min(min, w.cpuNs()); }

        // (3) 출력 — 머리줄: 활동 워커 수, CPU 합(죽어서 빠진 워커가 있으면 꼬리에 표시)
        System.out.printf("   [N=%d 스레드 %d 분할 %d 워커별 CPU 장부] 활동 워커 %d개, CPU 합 %.0fns%s%n",
                n, parallelism, splitCount, workers.size(), sum, anyDead ? " (일부 워커 종료되어 제외)" : "");
        for (WorkerCpu w : workers) {                             // CPU 큰 워커부터(정렬 순)
            // 그 워커의 이름, CPU(ms), 전체 대비 비중(%)을 한 줄로 찍는다
            System.out.printf("      %-28s %13.0f ns  (%4.1f%%)%n",
                    w.name(), w.cpuNs(), 100.0 * w.cpuNs() / sum);
        }
        // 쏠림 지표 — 최대÷최소. 1에 가까우면 고르게 분배, 크면 특정 워커에 일이 몰린 것
        System.out.printf("      최대/최소 = %.2f배 (1.0에 가까울수록 고르게 분배, 클수록 쏠림)%n", max / min);
        System.out.println("\n");
    }

    /** 이 설정에서 실제로 만들어지는 말단 작업 수 = 표의 '작업 분할 개수' 열.
     *  엔진 ImproveTask.compute()의 목표 분할 개수 규칙과 같은 방식으로 센다.
     *  목표를 반으로 나누고 구간도 그 비율로 자르며, 목표가 1이거나 더 못 자를 자리면 말단 하나로 센다. */
    private static int leafCount(int lo, int hi, int splitCount) {
        if (splitCount > 1) {
            int leftSplit = splitCount / 2;
            int mid = lo + (int) ((long) (hi - lo) * leftSplit / splitCount);
            if (mid > lo && mid < hi) {
                return leafCount(lo, mid, leftSplit) + leafCount(mid, hi, splitCount - leftSplit);
            }
        }
        return 1;
    }

    /** 현재 살아있는 스레드 중 이름이 "ForkJoinPool"로 시작하는 워커들의 {id → 이름} 맵. */
    private static Map<Long, String> snapshotFjpWorkers(ThreadMXBean threads) {
        Map<Long, String> m = new HashMap<>();
        for (long id : threads.getAllThreadIds()) {
            ThreadInfo info = threads.getThreadInfo(id);
            if (info == null) continue;
            String name = info.getThreadName();
            if (name != null && name.startsWith("ForkJoinPool")) m.put(id, name);
        }
        return m;
    }

    /* 표 출력 형식
       COLS       : 머리줄에 찍는 열 이름. 괄호 안이 그 열의 단위다(ms = 밀리초, 개 = 개수, 배 = 배수).
       COL_WIDTH  : 열마다 쓸 표시 폭. COLS와 순서·개수가 같아야 한다.
       정렬 방식: 한글이 콘솔에서 몇 픽셀로 그려지는지는 글꼴이 정한다. "한글 = 보통 글자 두 개"로 보고
       공백을 채우면 그 비율이 정확히 2가 아닌 글꼴에서 머리줄과 값의 자리가 어긋난다(2026-07-28 확인).
       그래서 폭을 픽셀로 맞추지 않고, 모든 줄이 같은 열에서 한글 글자 수와 보통 글자 수를 똑같이 갖게 만든다.
       빈자리를 채울 때 한글 자리에는 전각 공백(U+3000)을, 보통 자리에는 보통 공백을 쓴다.
       전각 공백은 한글과 같은 글꼴·같은 폭으로 그려지므로, 글꼴이 무엇이든 열의 끝 위치가 같아진다.
       COL_CJK   : 열마다 한글(전각) 글자 수
       COL_ASCII : 열마다 보통(반각) 글자 수 */
    private static final String[] COLS = {
            "N(명)", "팀당 인원(명)", "구현", "스레드(개)", "작업 분할 개수(개)",
            "elapsed(ns)", "speedup(배)",
            "procCPU(ns)", "busyCore(개)", "mainCPU(ns)", "workerCPU(ns)", "기타 자바 스레드(ns)", "자바 아닌 스레드(ns)", "GC(ms)"};
    private static final int[] COL_CJK   = {1,  5, 2, 4,  7,  0, 1,  0,  1,  0,  0,  7,  7, 0};
    private static final int[] COL_ASCII = {4,  4, 0, 3,  5, 11, 9, 12, 10, 11, 13, 12, 12, 6};

    /** 열 사이 구분자. 머리줄과 값 줄 모두 이 문자열로 이어 붙인다. */
    private static final String COL_SEP = " | ";

    /** 구분선 한 줄 — 값 줄과 글자 구성이 같아야 길이가 맞는다.
     *  한글 자리는 전각 공백으로 비우고, 보통 자리는 '-'로 채운다. 열 사이는 구분자와 길이가 같은 '-+-'를 쓴다. */
    private static String ruleLine(int[] cjkWidths, int[] asciiWidths) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cjkWidths.length; i++) {
            if (i > 0) line.append("-+-");
            line.append("　".repeat(cjkWidths[i]));
            line.append("-".repeat(asciiWidths[i]));
        }
        return line.toString();
    }

    /** 표 한 칸 — 오른쪽 정렬. 한글 글자 수는 전각 공백으로, 보통 글자 수는 보통 공백으로 각각 채운다.
     *  두 종류를 따로 채우므로 모든 줄의 글자 구성이 같아지고, 글꼴과 무관하게 열의 끝 위치가 맞는다. */
    private static String cell(String value, int cjkWidth, int asciiWidth) {
        int cjk = 0, ascii = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) >= 0x1100) cjk++; else ascii++;   // 0x1100 이상 = 한글·한자 등 전각 문자
        }
        StringBuilder sb = new StringBuilder();
        for (int i = cjk; i < cjkWidth; i++) sb.append('　');     // 전각 공백 — 한글과 같은 폭
        for (int i = ascii; i < asciiWidth; i++) sb.append(' ');      // 보통 공백
        return sb.append(value).toString();
    }

    /** 한 줄 출력 — 값을 COL_CJK·COL_ASCII 순서대로 한 칸씩 맞춰 구분자로 이어 붙인다. */
    private static void printCells(String... values) {
        printCells(COL_CJK, COL_ASCII, values);
    }

    /** 위와 같되 열 구성 배열을 받는다. 기타 자바 스레드 목록처럼 열 구성이 다른 표에 쓴다. */
    private static void printCells(int[] cjkWidths, int[] asciiWidths, String... values) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) line.append(COL_SEP);          // 열 사이 구분자 — 어느 값이 어느 열인지 바로 보이게 한다
            line.append(cell(values[i], cjkWidths[i], asciiWidths[i]));
        }
        System.out.println(line);
    }

    /** [기타 자바 스레드 · 수집] measure()가 '기타'로 분류한 증가분을 id별로 더한다.
     *  이름은 처음 만났을 때 한 번만 읽는다. 스레드가 끝난 뒤에는 getThreadInfo가 null을 주기 때문이다. */
    private static void recordOtherJavaThread(ThreadMXBean threads, long id, long deltaNs) {
        long[] acc = otherJavaCpu.get(id);
        if (acc == null) {
            acc = new long[2];
            otherJavaCpu.put(id, acc);
            ThreadInfo info = threads.getThreadInfo(id);
            String name = (info != null) ? info.getThreadName() : null;
            otherJavaName.put(id, (name != null) ? name : "(이름 확인 불가)");
        }
        acc[0] += deltaNs;   // 누적 CPU 증가분(ns)
        acc[1]++;            // 이 스레드가 잡힌 측정 횟수
    }

    /** [기타 자바 스레드 · 출력] 표가 모두 끝난 뒤 main()이 한 번 부른다.
     *  표의 '기타 자바 스레드' 열은 한 줄에 합계만 담으므로, 그 안에 어떤 스레드가 있었는지를 여기서 id별로 편다.
     *  N과 병렬도를 가리지 않고 이번 실행의 모든 측정을 합친 값이다.
     *  잡힌 횟수 = 그 스레드가 CPU 증가분을 보인 측정 횟수(measure() 한 번이 1회). */
    private static void printOtherJavaThreads() {
        System.out.println();
        System.out.println("[기타 자바 스레드 목록] main도 ForkJoinPool 워커도 아닌 Java 스레드. 이번 실행의 모든 측정을 합쳤다.");

        if (otherJavaCpu.isEmpty()) {
            System.out.println("  CPU 증가분을 보인 기타 자바 스레드가 없었다.");
            return;
        }

        int[] listCjk   = {0,  2,  2, 4};
        int[] listAscii = {8, 34, 11, 2};
        printCells(listCjk, listAscii, "id", "이름", "누적 CPU(ns)", "잡힌 횟수");
        System.out.println(ruleLine(listCjk, listAscii));

        List<Long> ids = new ArrayList<>(otherJavaCpu.keySet());
        ids.sort((a, b) -> Long.compare(otherJavaCpu.get(b)[0], otherJavaCpu.get(a)[0]));   // CPU 큰 스레드부터

        double totalNs = 0;
        for (long id : ids) {
            long[] acc = otherJavaCpu.get(id);
            double cpuNs = acc[0];
            totalNs += cpuNs;
            printCells(listCjk, listAscii, String.valueOf(id), otherJavaName.get(id),
                    String.format("%.0f", cpuNs), String.valueOf(acc[1]));
        }
        System.out.printf("  스레드 %d개, 누적 CPU 합 %.0fns%n", ids.size(), totalNs);
    }

    private static void printRow(int n, String label, String par, String splitCount, Result r, double speedup) {
        // 평균 바쁜 코어 수 = procMs ÷ elapsedMs. procMs(코어들이 일한 시간의 총합, 단위 코어·ms)를
        // elapsedMs(실제 흐른 시간)로 나눠 "그 구간에 평균 몇 개 코어가 동시에 돌았나"를 낸다.
        double busyCores = r.procNs / r.elapsedNs;   // 둘 다 ns라 단위가 상쇄된다
        String sp = Double.isNaN(speedup) ? "-" : String.format("%.2f", speedup);
        // [이전 설정 · 보존] JIT 열이 있던 13열 출력(2026-07-28 제외)
//        System.out.printf("%-5d %-6s %6s %10.2f %10.2f %8.2f %9.2f %10.2f %10.2f %9.2f %6.0f %6.0f %8s%n",
//                n, label, par, r.elapsedMs, r.procMs, busyCores, r.mainMs,
//                r.workerMs, r.otherJavaMs, r.residualMs, r.gcMs, r.jitMs, sp);
        // [이전 설정 · 보존] 단위 표기 전 출력(2026-07-28 교체)
//        System.out.printf("%-5d %-6s %6s %10.2f %10.2f %8.2f %9.2f %10.2f %10.2f %9.2f %6.0f %8s%n",
//                n, label, par, r.elapsedMs, r.procMs, busyCores, r.mainMs,
//                r.workerMs, r.otherJavaMs, r.residualMs, r.gcMs, sp);
        printCells(String.valueOf(n), String.valueOf(TEAM_CAPACITY), label, par, splitCount,
                String.format("%.0f", r.elapsedNs), sp,
                String.format("%.0f", r.procNs),      String.format("%.2f", busyCores),
                String.format("%.0f", r.mainNs),      String.format("%.0f", r.workerNs),
                String.format("%.0f", r.otherJavaNs), String.format("%.0f", r.nonJavaNs),
                String.format("%.0f", r.gcMs));
    }

    // 입력 구성 (SeedGreedyBench·ImproveEquivalenceCheck와 동일한 방식)

    private static int[] parseLoads(String[] args) {
        List<Integer> xs = new ArrayList<>();
        for (String a : args) {
            for (String tok : a.split("[,\\s]+")) {
                if (tok.isEmpty()) continue;
                try { xs.add(Integer.parseInt(tok.trim())); } catch (NumberFormatException ignore) { }
            }
        }
        if (xs.isEmpty()) return DEFAULT_LOADS;
        int[] out = new int[xs.size()];
        for (int i = 0; i < out.length; i++) out[i] = xs.get(i);
        return out;
    }

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

    private static double tolerance(List<Member> members, int teamCount) {
        double totalSkill = members.stream().mapToDouble(m -> m.skill().doubleValue()).sum();
        double avgTeamSum = totalSkill / teamCount;
        return avgTeamSum * (CAP_PERCENT / 100.0);
    }

    private static List<List<Member>> deepCopy(List<List<Member>> teams) {
        List<List<Member>> copy = new ArrayList<>(teams.size());
        for (List<Member> team : teams) copy.add(new ArrayList<>(team));
        return copy;
    }

    private static final class Result {
        double elapsedNs;    // 메소드 전체 동작 시간(시작~끝 경과, ns)
        double procNs;       // 프로세스 전체 CPU 시간(ns)
        double mainNs;       // main 스레드 CPU 시간(직렬 구간 근사, ns)
        double workerNs;     // ForkJoinPool 워커 스레드 CPU 합(호출 구간 증가분, ns)
        double otherJavaNs;  // 표의 '기타 자바 스레드' 열(ns). main·워커 외 Java 스레드 CPU 합
        double nonJavaNs;    // 표의 '자바 아닌 스레드' 열(ns). procCPU − Java 스레드 CPU 합 = 스레드별로 잴 수 없는 몫(GC·JIT·VM Thread 등)
        double gcMs;         // GC 수집 시간 증가분(실제 흐른 시간 근사)

        /* [할당 계측] 호출 구간에 새로 만든 객체의 바이트. 셋을 더하면 호출 1회의 총 할당량 */
        double mainAllocB;   // main 스레드 몫(B)
        double workerAllocB; // ForkJoinPool 워커 몫의 합(B)
        double otherAllocB;  // main·워커 외 Java 스레드 몫의 합(B)

        /* [계측] 엔진 정적 카운터 4개의 5회 평균 */
        double evalCount;    // 말단 작업(평가 횟수)(회)
        double passCount;    // 통과 횟수(회)
        double copyNs;       // 복사 구간 시간 합(ns)
        double evalLoopNs;   // 평가 반복문 구간 시간 합(ns)
        List<String> runLines;   // [회차별 기록] 측정 5회의 회차별 원값 줄(IMPLOG,run 형식)
//        double jitMs;      // [JIT 제외 · 보존] JIT 컴파일 시간 증가분
    }
}
