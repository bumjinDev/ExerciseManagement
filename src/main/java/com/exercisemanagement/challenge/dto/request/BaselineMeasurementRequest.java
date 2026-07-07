package com.exercisemanagement.challenge.dto.request;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 기준 측정 제출의 JSON 메타 파트 (명세서 7.2.2, multipart의 meta 파트). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BaselineMeasurementRequest {

    @NotNull(message = "무게는 필수입니다")
    @Positive(message = "무게는 0보다 커야 합니다")
    private BigDecimal weight;

    @NotNull(message = "횟수는 필수입니다")
    @Positive(message = "횟수는 1 이상이어야 합니다")
    private Integer reps;
}
