package com.exercisemanagement.challenge.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 기준 측정 요청 응답 (명세서 7.2.3, 202 Accepted). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BaselineRequiredResponse {

    private String status;   // "기준 측정 필요"
}
