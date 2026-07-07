package com.exercisemanagement.challenge.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 정산 실행 기록 (명세서 7.1.8). 챌린지당 한 건 — 단일 실행 보장의 저장 단계 장치. */
@Entity
@Table(name = "settlement")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Settlement {

    @Id
    @Column(name = "settlement_id")
    private String settlementId;

    @Column(name = "challenge_id")
    private String challengeId;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;
}
