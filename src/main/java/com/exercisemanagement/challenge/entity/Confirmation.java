package com.exercisemanagement.challenge.entity;

import java.time.LocalDateTime;

import com.exercisemanagement.challenge.common.ConfirmationDecision;

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

/** 팀원 확인 기록 (명세서 7.1.5). append 전용 — 고치거나 지우지 않는다. */
@Entity
@Table(name = "confirmation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Confirmation {

    @Id
    @Column(name = "confirmation_id")
    private String confirmationId;

    @Column(name = "submission_id")
    private String submissionId;

    @Column(name = "confirmer_id")
    private String confirmerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision")
    private ConfirmationDecision decision;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;
}
