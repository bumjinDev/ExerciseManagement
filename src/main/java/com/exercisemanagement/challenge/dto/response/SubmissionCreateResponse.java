package com.exercisemanagement.challenge.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.exercisemanagement.challenge.common.SubmissionStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 제출 접수 응답 (명세서 7.2.3, 202 Accepted). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionCreateResponse {

    private String submissionId;
    private SubmissionStatus status;     // 접수 직후 "확인 대기"
    private LocalDate linkedDate;        // 제출이 연결된 수행 날짜
    private LocalDateTime registeredAt;  // 인증을 올린 시점
}
