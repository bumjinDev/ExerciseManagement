package com.exercisemanagement.challenge.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 예치 충전 응답 (신설 API — 변경사항 문서 3-2). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepositChargeResponse {

    private long balance;   // 충전이 반영된 예치 잔액
}
