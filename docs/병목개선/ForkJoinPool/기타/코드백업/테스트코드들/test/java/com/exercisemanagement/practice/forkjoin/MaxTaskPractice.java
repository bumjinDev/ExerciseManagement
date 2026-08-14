package com.exercisemanagement.practice.forkjoin;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

/**
 * fork-join 실습 2단계 (연습 문제): 최댓값과 그 인덱스 찾기.
 *
 * SumTask와 골격은 완전히 같고, 두 곳만 다르다:
 *   - 직접 계산: "더하기" 대신 "구간 내 최댓값 찾기"
 *   - 병합: "더하기" 대신 "두 부분 결과 중 더 나은 쪽 고르기"
 *
 * 규칙: 값이 큰 쪽이 이긴다. 값이 같으면(동점) 인덱스가 작은 쪽이 이긴다.
 *
 * 이 동점 규칙이 이 연습의 핵심이다. 규칙이 없으면 어느 스레드가 먼저 계산했느냐에 따라
 * 결과가 달라질 수 있다. 규칙이 있으면 누가 어떤 순서로 계산해도 결과가 같다.
 * improve() 병렬화에서 지켜야 할 tie-break 보존과 똑같은 문제의 축소판이다.
 *
 * TODO 1~3을 채워 완성한 뒤 실행해 보세요. 기대 결과는 파일 맨 아래 주석에 있습니다.
 * 막히면 채운 상태 그대로 가져오시면 같이 봅니다.
 */
public class MaxTaskPractice {

    /** 결과 묶음: 최댓값과 그 값이 있는 칸 번호. (SumTask의 long 하나와 달리 값이 두 개라 record로 묶는다) */
    record MaxResult(long value, int index) { }

    static class MaxTask extends RecursiveTask<MaxResult> {   // 결과 타입이 MaxResult

        private final long[] arr;
        private final int from;                   // 담당 시작 칸 (포함)
        private final int to;                     // 담당 끝 칸 (미포함)
        private static final int THRESHOLD = 2;   // 작업 한번 당 하나의 스레드가 한번에 처리할 작업 범위(여기서는 배열이니 배열 중 2개를 한번의 작업 범위로 침)

        MaxTask(long[] arr, int from, int to) {
            this.arr = arr;
            this.from = from;
            this.to = to;
        }

        @Override
        protected MaxResult compute() {

            System.out.println(Thread.currentThread().getName() + " : [" + from + "," + to + ") 시작");

            // TODO 1: 구간 [from, to)를 돌며 최댓값과 그 인덱스를 찾아 MaxResult로 반환한다.
            //   힌트: 첫 담당 칸(from)을 일단 최선으로 잡고, 그 다음 칸부터
            //         "지금 최선보다 클 때만" 최선을 교체한다.
            //         비교를 > 로 하면(>= 가 아니라) 동점일 때 먼저 만난(앞 인덱스) 쪽이 유지된다.

            int maxIndex = from;
            long maxValue = 0;

            /* 본인에게 할당된 작업 하기. */
            if (to - from <= THRESHOLD) {

                for (int k = from; k < to; k++) {

                    if(arr[k] > maxValue) {

                        maxValue = arr[k];
                        maxIndex = k;
                    }
                }
                System.out.println("MaxResult = " + maxValue + ", index = " + maxIndex + " (from, to) = [" + from + "," + to + "]");

                return new MaxResult(maxValue, maxIndex);
            }

            // TODO 2: 왼쪽·오른쪽 작업을 만들어 SumTask와 같은 순서로 fork / compute / join 한다.
            /* 분할 로직(지금은 invoke() 로 인덱스 0 부터 from(끝) 까지 지정 하였다.
                본인에게 할당된 작업 양은 한 스레드 당 최대 작업 범위(단위)인, "THRESHOLD" 과 인덱스 from, 과 to 이며,
                이때 본인에게 할당된 양 만큼만 작업하고 나머지는 별도의 워커 스레드들이 워크 스털링 알고리즘에 따라서 재분배 로직으로
                처리할 수 있도록 지금 작업 맥락으로써는 인덱스를 사용해서 작업 범위를 명확히 해서 작업 단위들을 나눠서 fork 로 전달한다.
                ** 여기서 "THRESHOLD" 단위로 세세하게 나눠서 다음 워커 스레드들이 현재 fork 단위 작업들을 추가로 compute() 에서 각자
                추가 분할 하게 할 수도 있고 그냥 인덱스로만 다음 작업 내역 지정해서 전달할 수도 있으나 지금은 후자를 택한다. 어차피
                fork-join-ppol 설계 맥락 따른 것은 마찬가지이니.
             */

            int mid = (from + to) / 2;                       // 내 구간의 중간

            MaxTask left  = new MaxTask(arr, from, mid);     // 내 구간의 왼쪽 절반 (나보다 작다 → 언젠가 끝난다)
            MaxTask right = new MaxTask(arr, mid, to);       // 내 구간의 오른쪽 절반

            left.fork();                                     // 왼쪽 절반: 큐에 등록 (누가 할지는 풀이 정함)
            MaxResult rightResult = right.compute();         // 오른쪽 절반: 내가 직접. 반환값 = 결과(MaxResult)
            MaxResult leftResult  = left.join();             // 왼쪽 절반의 결과를 수령. 반환값 = 결과(MaxResult)

            // TODO 3: 병합. 규칙(값이 크면 이김, 동점이면 인덱스 작은 쪽)대로 둘 중 하나를 골라 반환한다.
            //   생각해 볼 점: 왼쪽 구간의 인덱스는 항상 오른쪽 구간의 인덱스보다 작다.
            //                 그러면 동점일 때 어느 쪽을 반환해야 하는가?

            if (rightResult.value() > leftResult.value()) {
                return rightResult;   // 오른쪽이 "확실히 클 때만" 이긴다
            }
            return leftResult;        // 그 외 전부(왼쪽이 크거나, 동점) → 왼쪽
        }
    }

    public static void main(String[] args) {
        // 9가 두 번 있다(인덱스 4와 6). 동점 규칙이 제대로 구현됐는지 확인하기 위한 배치다.
//        long[] arr = {5, 3, 8, 1, 9, 2, 9, 4};
//
//        ForkJoinPool pool = new ForkJoinPool(2);
//        MaxResult result = pool.invoke(new MaxTask(arr, 0, arr.length));
//        pool.shutdown();
//
//        System.out.println("최댓값 = " + result.value() + ", 위치 = " + result.index());
    }

    /* ── 기대 결과 ─────────────────────────────────────────────
       최댓값 = 9, 위치 = 4
       위치가 6이 아니라 4여야 하고, 몇 번을 실행해도 4로 같아야 한다.
       실행할 때마다 6이 나오거나 4와 6이 번갈아 나온다면 동점 규칙이 깨진 것이다.
       (스레드 배정이 매번 달라져도 결과가 같은지 3~4회 반복 실행으로 확인해 보세요) */
}
