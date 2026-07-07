package com.exercisemanagement.challenge.common;

/**
 * 예치 원장 이동 유형 (명세서 7.1.7: 충전 / 참가 차감 / 환급 / 차감 / 무산 반환).
 * 회계 규칙(변경사항 문서 4-10): 원장 amount 합 = 현재 잔액.
 * FORFEIT는 amount 0의 차감 확정 상태 기록이다(금액 이동은 JOIN_DEBIT에서 이미 발생).
 */
public enum DepositEntryType {
    CHARGE,       // 충전 (+)
    JOIN_DEBIT,   // 참가 차감 (−)
    REFUND,       // 환급 (+)
    FORFEIT,      // 차감 확정 (0)
    VOID_RETURN   // 무산 반환 (+)
}
