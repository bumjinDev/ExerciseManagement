package com.exercisemanagement.challenge.dto.request;

import java.time.LocalDateTime;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 기간 공통 요청 타입 (명세서 7.2.1). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PeriodRequest {

    @NotNull(message = "시작 시각은 필수입니다")
    private LocalDateTime start;

    @NotNull(message = "종료 시각은 필수입니다")
    private LocalDateTime end;

    @AssertTrue(message = "종료 시각은 시작 시각보다 뒤여야 합니다")
    public boolean isRangeValid() {
        if (start == null || end == null) return true;
        return end.isAfter(start);
    }
}
