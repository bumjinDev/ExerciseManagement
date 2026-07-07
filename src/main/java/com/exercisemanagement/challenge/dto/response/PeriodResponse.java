package com.exercisemanagement.challenge.dto.response;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 기간 응답 공통 타입 (명세서 7.2.1). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeriodResponse {

    private LocalDateTime start;
    private LocalDateTime end;
}
