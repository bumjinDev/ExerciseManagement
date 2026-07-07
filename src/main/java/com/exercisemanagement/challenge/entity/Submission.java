package com.exercisemanagement.challenge.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.exercisemanagement.challenge.common.SubmissionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 인증 제출 (명세서 7.1.4). 기준 측정 행도 이 표에 담고 is_baseline으로 구분한다.
 * photo_path는 명세 외 추가 컬럼(변경사항 문서 2-1).
 */
@Entity
@Table(name = "submission")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Submission {

    @Id
    @Column(name = "submission_id")
    private String submissionId;

    @Column(name = "challenge_id")
    private String challengeId;

    @Column(name = "participant_id")
    private String participantId;

    @Column(name = "weight")
    private BigDecimal weight;

    @Column(name = "reps")
    private int reps;

    @Column(name = "volume")
    private BigDecimal volume;

    @Column(name = "photo_hash")
    private String photoHash;

    @Column(name = "photo_path")
    private String photoPath;

    @Column(name = "registered_at")
    private LocalDateTime registeredAt;

    /** 수행 날짜. 하루 상한·주기·30일 집계·날짜별 조회의 기준 */
    @Column(name = "linked_date")
    private LocalDate linkedDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private SubmissionStatus status;

    /** true(1): 기준 측정 행, false(0): 일반 인증 행 */
    @Column(name = "is_baseline")
    private boolean baseline;
}
