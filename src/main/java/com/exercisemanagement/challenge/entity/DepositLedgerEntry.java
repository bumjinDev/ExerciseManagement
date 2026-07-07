package com.exercisemanagement.challenge.entity;

import java.time.LocalDateTime;

import com.exercisemanagement.challenge.common.DepositEntryType;

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

/** 예치금 이동 원장 (명세서 7.1.7). append 전용, 정정은 반대 방향 기록 추가. */
@Entity
@Table(name = "deposit_ledger")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepositLedgerEntry {

    @Id
    @Column(name = "entry_id")
    private String entryId;

    @Column(name = "participant_id")
    private String participantId;

    @Column(name = "challenge_id")
    private String challengeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type")
    private DepositEntryType entryType;

    /** 이동 금액(부호 포함). 원장 합 = 현재 잔액 (변경사항 문서 4-10) */
    @Column(name = "amount")
    private long amount;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
