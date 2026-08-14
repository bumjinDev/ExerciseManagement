package com.exercisemanagement.practice.forkjoin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

/**
 * fork-join 실습 0단계: 재귀 없는 평면 버전.
 *
 * SumTaskPractice(재귀 버전)가 난잡하게 느껴질 때 먼저 볼 파일.
 * 재귀를 빼고, 네 단계를 main 안에 눈에 보이는 순서대로 늘어놓았다:
 *
 *   1) 쪼개기  : 잎 작업 4개를 우리가 직접 만든다 (재귀가 하던 일을 손으로 한 것)
 *   2) 제출    : 4개를 풀에 등록한다. 이 순간부터 "누가 맡을지"는 우리 소관이 아니다
 *   3) 결과 수령: join으로 4개의 답을 순서대로 받는다
 *   4) 병합    : 받은 답을 더한다
 *
 * 관찰 포인트:
 *   - 출력에서 각 구간을 맡은 워커 이름을 본다. 실행할 때마다 배정이 달라질 수 있다.
 *   - 코드 어디에도 "이 작업은 워커 1이 해라"라는 지시가 없다는 것을 확인한다.
 *     배정은 실행 시점에 풀이 정하고, 우리가 정하는 건 "무엇을 계산해 어떻게 합치는가"뿐이다.
 *   - 그래도 최종 합은 항상 39다.
 */
public class SumTaskFlatPractice {

    /** 잎 작업: 담당 구간을 직접 더하기만 한다. 쪼개기도 재귀도 없다. */
    static class LeafSumTask extends RecursiveTask<Long> {

        private final long[] arr;
        private final int from;   // 담당 시작 칸 (포함)
        private final int to;     // 담당 끝 칸 (미포함)

        LeafSumTask(long[] arr, int from, int to) {
            this.arr = arr;
            this.from = from;
            this.to = to;
        }

        @Override
        protected Long compute() {
            long sum = 0;
            for (int k = from; k < to; k++) {
                sum = sum + arr[k];
            }
            System.out.println(Thread.currentThread().getName()
                    + " 이(가) [" + from + "," + to + ") 를 맡아 계산 = " + sum);
            return sum;
        }
    }

    public static void main(String[] args) {
        long[] arr = {5, 3, 8, 1, 9, 2, 7, 4};
        ForkJoinPool pool = new ForkJoinPool(2);   // 워커 2개

        // ── 1) 쪼개기: 2칸씩 잎 작업 4개를 직접 만든다 ──────────────────
        //    [0,2) [2,4) [4,6) [6,8)
        List<LeafSumTask> tasks = new ArrayList<>();
        for (int start = 0; start < arr.length; start += 2) {
            tasks.add(new LeafSumTask(arr, start, start + 2));
        }

        // ── 2) 제출: 풀의 공용 큐에 넣는다. 여기서부터는 워커들이 알아서 가져간다 ──
        for (LeafSumTask task : tasks) {
            pool.submit(task);
        }

        // ── 3) 결과 수령 + 4) 병합: 만든 순서대로 답을 받아 더한다 ──────────
        //    join은 "그 작업이 끝날 때까지 기다렸다가 결과를 받는다".
        //    어느 워커가 계산했든 상관없이, 더하는 순서는 이 for문이 고정한다.
        long total = 0;
        for (LeafSumTask task : tasks) {
            total = total + task.join();
        }
        pool.shutdown();

        System.out.println("최종 합 = " + total + " (기대값 39)");
    }
}
