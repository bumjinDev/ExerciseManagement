package com.exercisemanagement.challenge.dto.response;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 신청 완료 응답 (명세서 7.2.3). 즉시 완료·기준 측정 경유 두 경로가 공용한다. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParticipationCompleteResponse {

    private String participationId;
    private BigDecimal formationSkill;   // 확정된 편성 실력 (최근 30일 볼륨 1회 평균)
    private BigDecimal goalBaseline;     // 개인 목표 기준값 (편성 실력과 동일 값)
    private BigDecimal intensityFloor;   // goalBaseline × intensityCoefficient
    private long depositCharged;         // 차감된 예치금
    private boolean teamAssigned;        // 배정 여부 (신청 직후 false)
}
