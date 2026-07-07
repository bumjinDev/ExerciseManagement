package com.exercisemanagement.challenge.dto.response;

import java.math.BigDecimal;

import com.exercisemanagement.challenge.common.SubmissionStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 확인 처리 응답 (명세서 7.2.3). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmationResponse {

    private String submissionId;
    private SubmissionStatus resultStatus;   // "확인 완료" 또는 "반려"
    private boolean volumeApplied;           // 확인이면 true, 반려면 false
    private BigDecimal appliedVolume;        // 확인 시 더해진 볼륨, 반려 시 0
}
