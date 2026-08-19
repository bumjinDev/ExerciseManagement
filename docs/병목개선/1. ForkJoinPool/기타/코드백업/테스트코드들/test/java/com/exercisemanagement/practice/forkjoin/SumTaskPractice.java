package com.exercisemanagement.practice.forkjoin;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

/**
 * fork-join 실습 1단계: 배열 합 구하기 (재귀 버전, 역할별 메서드 분리).
 *
 * 먼저 SumTaskFlatPractice(0단계, 재귀 없는 평면 버전)를 실행해 보고 오는 것을 권장.
 *
 * 평면 버전과의 차이는 "쪼개기를 누가 하느냐" 하나다.
 *   - 평면 버전: main이 잎 4개를 손으로 만들었다. 배열 크기가 바뀌면 사람이 다시 계산해야 한다.
 *   - 재귀 버전: 작업이 스스로 "내 담당이 크면 반으로 쪼갠다"를 반복한다.
 *     쪼개진 조각도 또 클 수 있으므로 같은 판단을 조각에서도 해야 하고,
 *     "같은 판단의 반복"을 코드로 쓰면 자기 자신을 다시 만드는 모양(재귀)이 된다.
 *
 * compute()는 판단만 하고, 실제 일은 이름 붙인 메서드 둘로 분리했다:
 *   computeDirectly()   : 담당이 작을 때 - 직접 더하기
 *   splitAndCombine()   : 담당이 클 때 - 반으로 쪼개 맡기고, 결과를 합치기
 *
 * 관찰 포인트:
 *   1) 최종 합은 몇 번을 실행해도 항상 39.
 *   2) 구간별 담당 워커는 실행마다 달라질 수 있다 (배정은 코드가 아니라 실행 시점에 정해진다).
 *   3) THRESHOLD를 4/8로, 풀 크기를 1로 바꿔 가며 잎 개수와 스레드 종류가 어떻게 변하는지 본다.
 */
public class SumTaskPractice {

    static class SumTask extends RecursiveTask<Long> {   // <Long> = compute()가 돌려줄 결과 타입

        private final long[] arr;                 // 배열 전체. 모든 작업이 같은 배열을 읽기만 한다
        private final int from;                   // 내 담당 구간의 시작 칸 번호 (포함)
        private final int to;                     // 내 담당이 끝나는 칸 번호 (미포함. 이 칸에 오면 멈춤)
        private static final int THRESHOLD = 2;   // 담당이 이 개수 이하면 더 쪼개지 않는다

        SumTask(long[] arr, int from, int to) {
            this.arr = arr;
            this.from = from;
            this.to = to;
        }

        /** 판단만 한다: 내 담당이 작으면 직접 계산, 크면 쪼개서 맡긴다. */
        @Override
        protected Long compute() {
            if (to - from <= THRESHOLD) {
                return computeDirectly();
            }
            return splitAndCombine();
        }

        /** 담당이 작을 때: 구간을 직접 더한다. (평면 버전의 잎 작업과 같은 일) */
        private long computeDirectly() {
            long sum = 0;
            for (int k = from; k < to; k++) {
                sum = sum + arr[k];
            }
            System.out.println(Thread.currentThread().getName()
                    + " 이(가) [" + from + "," + to + ") 를 맡아 계산 = " + sum);
            return sum;
        }

        /** 담당이 클 때: 반으로 쪼개고, 왼쪽은 큐에 맡기고 오른쪽은 내가 하고, 결과를 합친다. */
        private long splitAndCombine() {
            int mid = (from + to) / 2;
            SumTask left  = new SumTask(arr, from, mid);   // 왼쪽 절반 담당의 새 작업
            SumTask right = new SumTask(arr, mid, to);     // 오른쪽 절반 담당의 새 작업

            left.fork();                          // 왼쪽: 큐에 등록. 누가 맡을지는 풀이 정한다
            long rightResult = right.compute();   // 오른쪽: 지금 이 스레드가 직접 진행
            long leftResult  = left.join();       // 왼쪽 결과 수령 (아무도 안 가져갔으면 내가 직접 계산)

            long merged = leftResult + rightResult;
            System.out.println(Thread.currentThread().getName()
                    + " : [" + from + "," + to + ") 병합 = " + leftResult + " + " + rightResult + " = " + merged);
            return merged;
        }
    }

    public static void main(String[] args) {

        long[] arr = {5, 3, 8, 1, 9, 2, 7, 4};  // 작업할 전체 내용.

        ForkJoinPool pool = new ForkJoinPool(2);   // 워커 스레드 2개짜리 풀

        long total = pool.invoke(new SumTask(arr, 0, arr.length));   // main은 여기서 결과를 기다린다
        pool.shutdown();

        System.out.println("최종 합 = " + total + " (기대값 39)");
    }
}
