package com.exercisemanagement.challenge.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.exercisemanagement.challenge.common.SubmissionStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 내 제출 현황 응답 (명세서 7.2.3). 수행 날짜 내림차순, 날짜 안은 등록 시점 순. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MySubmissionsResponse {

    private List<SubmissionDateGroup> submissionsByDate;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubmissionDateGroup {
        private LocalDate date;                        // 수행 날짜 (linked_date)
        private List<SubmissionSummary> submissions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubmissionSummary {
        private String submissionId;
        private BigDecimal weight;
        private int reps;
        private BigDecimal volume;
        private SubmissionStatus status;
        private LocalDateTime registeredAt;
    }
}
