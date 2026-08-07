# improve() JFR 할당 측정 데이터 소스

| 항목 | 내용 |
|---|---|
| 작성일 | 2026-08-06 |
| 문서의 성격 | 2026-08-05 JFR 기록과 2026-08-06 JMC 직접 분석에서 얻은 데이터를 빠짐없이 옮겨 적은 소스 문서다. 최종 보고서 작성의 근거 자료로 쓴다. 새 해석은 없다 |
| 취급 | 원본데이터에 준해서 다룬다. 취합이나 재작성의 대상이 아니라 근거로만 쓴다 |
| 측정 환경 | i5-11400H (물리 코어 6개, 논리 프로세서 12개), JDK 17, Windows. JMC 9.1.2는 JDK 21로 실행 |
| 고정 입력 | 인원 200명, 정원 10명, 시드 42, 허용폭 10%, 시작 배정 greedy |

---

## 1. 기록 조건과 실행 구성

1. 기록 옵션은 `-XX:StartFlightRecording=settings=profile,filename=improve_alloc.jfr`이고, 프로브를 병렬도 6, 분할 36 한 점 설정으로 1회 실행했다(2026-08-05).
2. 이 실행의 improve() 호출은 15회다. 순차 7회(워밍업 2, 측정 5)는 main 스레드가, 병렬 8회(워밍업 2, 측정 5, 워커별 CPU 확인 1)는 주로 워커가 실행했다.
3. 기록을 켠 실행이므로 이 실행의 시간 값은 어떤 판정에도 쓰지 않는다.

## 2. 카운터 실측 (같은 실행의 프로브 콘솔)

1. 호출 1회당 총 할당 9,522.0MB (getThreadAllocatedBytes 증가분, main과 워커와 기타의 합).
2. 2026-08-04 실측 9,522.1MB와 일치. 평가 855,000회와 통과 352,485회도 같다.
3. 총량의 확정 값은 이 카운터 실측이다. 아래의 JFR 값은 전부 표본 추정치다.

## 3. 표본 집계 총괄

| 항목 | 2026-08-05 집계 | 2026-08-06 재작성 집계 |
|---|---:|---:|
| 표본 수 (Object Allocation Sample) | 11,568개 | 11,568개 |
| 추정 총량 (MB, 100만 바이트) | 140,659 | 140,674 |

1. 두 집계의 총량 차이는 0.01%다. 기록에 표본 무게가 소수 첫째 자리로 반올림되어 있어 생기는 몫이다.
2. 카운터 실측과의 교차: 140,659 ÷ (15회 × 9,522.1) = 98.5%.

## 4. 문장별 집계 (무게 비중)

| 문장 | 코드 위치 | 무게 비중 |
|---|---|---:|
| 합 편차의 합 스트림 | 1230행 | 52.3% |
| 분포 편차의 평균 스트림 | 1278행 | 25.2% |
| 분포 편차의 분산 스트림 | 1280·1281·1284행 | 22.5% |
| 두 메서드 밖 (작업 객체, JFR 시작 표본 등) | | 0.03% |

1. 메서드 몫: 합 편차 계산 52.3% 대 분포 편차 계산 47.7%. 조각 값 예상(53.1 대 46.9)과 0.8%포인트 차이.
2. 줄 번호는 2026-08-06 시점의 TeamFormationEngine.java 기준이다.

## 5. 클래스별 집계 (JMC Memory 화면의 Class 표)

| 클래스 | Alloc Total | Total Allocation(%) | 표본 수 |
|---|---:|---:|---:|
| java.util.stream.ReferencePipeline$6 | 25.7 GiB | 19.6 | 2,139 |
| java.util.stream.ReferencePipeline$Head | 25.4 GiB | 19.4 | 2,145 |
| double[] | 19.7 GiB | 15 | 1,725 |
| java.util.stream.ReduceOps$13ReducingSink | 14 GiB | 10.7 | |
| java.util.stream.ReduceOps$16 | 13.5 GiB | 10.3 | |
| java.util.ArrayList$ArrayListSpliterator | 13.5 GiB | 10.3 | |
| java.util.stream.ReferencePipeline$6$1 | 10.9 GiB | 8.33 | |
| java.util.stream.DoublePipeline$$Lambda$99 | 5.77 GiB | 4.4 | |
| TeamFormationEngine$$Lambda$105 | 2.54 GiB | 1.94 | 240 |
| TeamFormationEngine$ImproveTask | 35.1 MiB | 0.0262 | |
| byte[] | 1.82 MiB | 0.00135 | |

1. 그 아래 순위(java.util.OptionalDouble 793 KiB 등)는 비중이 0.001% 미만이다.
2. 표본 수 빈칸은 화면에서 따로 확인하지 않은 클래스다. 집계 엑셀(2_2)에 전부 있다.

## 6. 클래스 묶음별 구성 (무게 비중, 조각 값 예상과 대조)

| 묶음 | 구성 클래스 | 실측 | 조각 값 예상 |
|---|---|---:|---:|
| 목록을 훑는 도구 | ArrayListSpliterator | 10.3% | 10.8% |
| 스트림 시작 단계 | ReferencePipeline$Head | 19.4% | 18.9% |
| 변환 단계 | ReferencePipeline$6 | 19.6% | 18.9% |
| 합계·평균 실행분 | double[], ReduceOps 2종, $6$1, DoublePipeline 람다, TeamFormationEngine 람다 | 50.7% | 51.6% |
| 그중 mean을 담는 람다 | TeamFormationEngine$$Lambda | 1.9% | 1.7% |

계산식: 예상은 조각별 바이트(아래 10절)에 스트림 생성 횟수를 곱해 낸 비중이다.

## 7. JMC 스택 확인 상세 (2026-08-06, 사용자 직접 확인)

프레임 구분을 Line Number로 두고 클래스 네 개를 확인했다. 아래 표본 수와 %는 전부 개수 기준(JMC Stack Trace의 Samples, Percentage)이다.

### 7.1 ReferencePipeline$6 (표본 2,139개)

1. 만들어진 지점: ReferencePipeline.mapToDouble 241행. 2,139개 100%.
2. 부른 줄: sumDeviation 1230행 1,091개(51.0%), distributionDeviation 1281행 567개(26.5%), 1278행 481개(22.5%).
3. 산술 기대(호출 횟수 비): 54.8 / 22.6 / 22.6%. 표본 흔들림 범위에서 맞는다.

### 7.2 ReferencePipeline$Head (표본 2,145개)

1. 만들어진 지점: StreamSupport.stream 69행(2,145개 100%). 그 위 호출자 Collection.stream 743행(100%).
2. 부른 줄: sumDeviation 1230행 1,189개(55.4%), distributionDeviation 1278행 492개(22.9%), 1280행 464개(21.6%). 분산 문장에서 stream() 조각은 1280행, mapToDouble 조각은 1281행이라 7.1과 줄이 다르게 나오는 것이 확인됐다.
3. 1230행의 바깥 호출자: improveSequential 524행 801개(37.3%), ImproveTask.compute 875행 379개(17.7%), improve 675행 9개(0.42%).
4. improveSequential 갈래의 더 바깥: 프로브 measure의 측정 반복문 582개(27.1%), 워밍업 반복문 219개(10.2%). 비 219 대 582는 실행 횟수 비 2회 대 5회와 맞는다.
5. compute 갈래의 더 바깥: compute 722행 209개(9.74%), compute 806행 170개(7.93%). fork 재귀의 두 경로다.

### 7.3 TeamFormationEngine$$Lambda$105 (표본 240개)

1. 만들어진 지점: DirectMethodHandle.allocateInstance 520행 238개(99.2%)와 Unsafe.allocateInstance 2개(0.833%). 람다는 소스의 new가 아니라 JVM 내부 경로로 만들어져 맨 위 프레임 모양이 다르다.
2. 엔진 쪽 줄: distributionDeviation 1280행 하나로 238개 전부가 모인다. 1278행 0건. 평균 스트림의 람다는 바깥 값을 담지 않아 재사용되므로 표본에 없다.
3. 1280행의 바깥 호출자: improveSequential 539행 160개(66.7%), ImproveTask.compute 889행 75개(31.2%), improve 676행 3개(1.25%).

### 7.4 double[] (표본 1,725개)

1. 만들어진 지점 두 곳: DoublePipeline의 sum 안 람다(lambda$sum$1, 450행) 836개(48.5%)와 average 안 람다(lambda$average$4, 493행) 889개(51.5%). 각각 new double[3](누적 합, 보정 항, 단순 합)과 new double[4](개수 칸 추가)다.
2. sum 갈래의 엔진 줄: sumDeviation 1230행 836개. 바깥 호출자는 improveSequential 524행 536개(31.1%), compute 875행 292개(16.9%), improve 675행 8개(0.464%).
3. average 갈래의 엔진 줄: distributionDeviation 1284행 446개(25.9%)와 1278행 443개(25.7%). 두 갈래가 거의 반반인 것은 평균 스트림과 분산 스트림이 통과한 평가마다 20번씩 똑같이 average를 부르기 때문이다.
4. 이것으로 세 문장의 모든 줄(1230, 1278, 1280, 1281, 1284)이 화면에서 확인됐다.

## 8. 스레드별 나눔 (재작성 집계)

| 스레드 | 무게(MB) | 무게 비중 | 표본 수 |
|---|---:|---:|---:|
| main | 66,658 | 47.4% | 7,739개 |
| 워커 | 74,016 | 52.6% | 3,821개 |
| 기타 스레드 | 0 | 0.0% | 8개 |

1. 대조: 순차 7회 몫 계산은 7 × 9,522.1 = 66,655MB로 main 무게와 겹친다. 병렬 8회 몫 계산은 76,177MB이고, 병렬 호출의 스캔 기준값 계산을 main이 하므로 워커 무게가 그보다 조금 작은 것이 맞다.
2. 주의: 표본 개수 비율(main 67%)은 무게 비율(47.4%)과 다르다. 표본이 스레드에 따라 치우친다.

## 9. 산술 기대값의 계산 근거

1. 스트림 생성 횟수: 합 스트림은 855,000 × 20 = 17,100,000번. 평균 스트림과 분산 스트림은 각각 352,485 × 20 = 7,049,700번. 합계 31,199,400번.
2. 스트림 한 번에 하나씩 생기는 객체(Head, $6)의 기대 비중: 54.8 / 22.6 / 22.6%.
3. double[]의 기대: sum용 17,100,000개 대 average용 14,099,400개로 개수 비 54.8 대 45.2. 무게로는 average용이 한 칸 커서 거의 반반.

## 10. 조각별 바이트와 클래스 대응 (다른 환경 JDK 21 실측, 관찰 문서 부록 D)

| 코드 조각 | 조각 바이트 | 대응 클래스 |
|---|---:|---|
| 목록을 훑는 도구 | 32 | ArrayListSpliterator |
| team.stream() 까지 | +56 | ReferencePipeline$Head |
| .mapToDouble(...) 까지 | +56 | ReferencePipeline$6 |
| .sum() 실행 | +144 | ReduceOps 2종, double[], $6$1 등 |
| 합 편차 계산 1회 (팀 20개) | 5,760 | |
| 분포 편차 계산 1회 (팀 20개, 스트림 2벌) | 12,320 | |
| 바깥 값을 담는 람다와 안 담는 람다의 차이 | 24 | TeamFormationEngine$$Lambda |

## 11. 재현 방법

1. 원본데이터 6번 폴더(`기타/원본데이터/6. improve_ForkJoinPool_JFR측정/`)에서 두 명령을 차례로 실행한다.

   `jfr print --events jdk.ObjectAllocationSample --stack-depth 64 "1_1. improve_ForkJoinPool_JFR측정_병렬도6_말단작업분할개수_36_alloc.jfr" > alloc_print.txt`

   `python "2. jfr_alloc_집계.py" alloc_print.txt`

2. 집계 엑셀(`2_2.`, 시트 5장: 클래스별, 스택 트레이스, 문장별, 스레드별, 판정 요약)이 만들어진다. 2026-08-06에 위 3절의 기준값 재현을 확인했다.
3. 같은 폴더의 `1. ..._ConsoleLog.txt`에 이 실행의 프로브 콘솔 전체([할당] 줄 포함)가 있다.

## 12. 한계점

1. JFR 값은 전부 표본 추정치다. 총량 판정에는 쓸 수 없고, 총량의 확정 값은 카운터 실측(getThreadAllocatedBytes)이다.
2. 기록을 켠 실행이라 이 실행의 시간 값(elapsed, CPU 시간)은 판정에 쓸 수 없다.
3. 표본 개수의 비율은 스레드에 따라 치우친다(main 무게 47.4% 대 표본 개수 67%). 스레드가 다른 몫의 비교는 개수가 아니라 무게로만 할 수 있다.
4. 기록 파일의 표본 무게가 소수 첫째 자리로 반올림되어 있어, 집계 총량에 0.01% 수준의 오차가 있다.
5. 표본이 적은 클래스일수록 비율이 흔들린다. TeamFormationEngine$$Lambda는 240개뿐이다.
6. 기록은 병렬도 6, 분할 36 한 점 설정에서만 떴다. 총 할당량이 병렬도와 분할에 무관하다는 것은 이 기록이 아니라 카운터 실측(순차 9,517.9 / 병렬도 6 9,522.1 / 병렬도 12 9,521.4MB)이 근거다.
7. 카운터 실측 총량이 조각 값 계산보다 2.7% 큰 것이 두 메서드 중 어느 쪽 몫인지는 표본 정밀도 밖이라 나누지 못했다.
8. 이 문서의 엔진 줄 번호는 2026-08-06 시점 소스 기준이다. 이후 코드를 고치면 줄 번호가 밀린다.

---

## 부록 A. 원본 파일 위치

| 항목 | 경로 |
|---|---|
| JFR 기록 파일 | `docs/병목개선/ForkJoinPool/기타/원본데이터/6. improve_ForkJoinPool_JFR측정/1_1. improve_ForkJoinPool_JFR측정_병렬도6_말단작업분할개수_36_alloc.jfr` |
| 실행 콘솔 로그 | 같은 폴더 `1. ..._ConsoleLog.txt` |
| 집계 도구와 사용법 | 같은 폴더 `2. jfr_alloc_집계.py`, `2_1. jfr_alloc_집계_사용법.txt` |
| 집계 엑셀 | 같은 폴더 `2_2. jfr_alloc_집계_병렬도6_분할36.xlsx` |
| JMC 사용법 문서 | 같은 폴더 `기타/JMC_사용법과_배경지식_20260806.md` |
| 조각별 바이트 원본 | `docs/병목개선/ForkJoinPool/improve_forkjoin_원인추적_관찰과다음테스트_20260802.md` 부록 D |
| 카운터 실측 원본 | `docs/병목개선/ForkJoinPool/improve_forkjoin_중간진행보고서_20260804.md` |
