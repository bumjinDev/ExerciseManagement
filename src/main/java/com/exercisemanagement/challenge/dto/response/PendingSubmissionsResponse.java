package com.exercisemanagement.challenge.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 확인 대기 목록 응답 (신설 API — 변경사항 문서 3-1).
 * 요청자가 속한 팀의 확인 대기 제출(본인 제출 제외)을 반환한다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingSubmissionsResponse {

    private String challengeId;
    private String teamId;
    private List<PendingSubmissionEntry> pendingSubmissions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PendingSubmissionEntry {
        private String submissionId;
        private String participantId;        // 제출자
        private BigDecimal weight;
        private int reps;
        private BigDecimal volume;
        private LocalDate linkedDate;        // 수행 날짜
        private LocalDateTime registeredAt;  // 등록 시점
    }
}
