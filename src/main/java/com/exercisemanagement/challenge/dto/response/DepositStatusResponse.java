package com.exercisemanagement.challenge.dto.response;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 예치 현황 응답 (신설 API — 변경사항 문서 3-2).
 * 잔액과 챌린지별 예치 상태(F010: 진행 중 / 환급 / 차감 / 무산 반환)를 담는다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepositStatusResponse {

    private long balance;
    private List<ChallengeDepositEntry> challengeDeposits;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChallengeDepositEntry {
        private String challengeId;
        private long amount;      // 그 챌린지에 건 예치금
        private String state;     // 진행 중 / 환급 / 차감 / 무산 반환
    }
}
