package com.exercisemanagement.challenge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 예치 잔액 현재 값 (명세서 7.1.6). 원본은 원장이고 이 표는 조회용 현재 값. */
@Entity
@Table(name = "deposit_balance")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepositBalance {

    @Id
    @Column(name = "participant_id")
    private String participantId;

    @Column(name = "balance")
    private long balance;
}
