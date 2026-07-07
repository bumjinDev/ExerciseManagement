package com.exercisemanagement.challenge.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 예치 충전 요청 (신설 API — 변경사항 문서 3-2). 모의 충전. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepositChargeRequest {

    @NotNull(message = "충전 금액은 필수입니다")
    @Positive(message = "충전 금액은 0보다 큰 값이어야 합니다")
    private Long amount;
}
