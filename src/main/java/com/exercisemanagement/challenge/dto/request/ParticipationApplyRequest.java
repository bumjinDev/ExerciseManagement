package com.exercisemanagement.challenge.dto.request;

import java.math.BigDecimal;

import com.exercisemanagement.challenge.common.CycleMode;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 참가 신청 요청 (명세서 7.2.2). intensityCoefficient는 선택값(미입력 시 1). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParticipationApplyRequest {

    @DecimalMin(value = "1.0", message = "강도 계수는 1 이상이어야 합니다")
    private BigDecimal intensityCoefficient;

    @NotNull(message = "목표 빈도 방식은 필수입니다")
    private CycleMode goalCycleMode;

    @NotNull(message = "목표 빈도 간격은 필수입니다")
    @Positive(message = "목표 빈도 간격은 1 이상이어야 합니다")
    private Integer goalCycleInterval;
}
