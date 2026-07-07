package com.exercisemanagement.challenge.dto.request;

import java.time.Duration;

import com.exercisemanagement.challenge.common.CycleMode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 챌린지 등록 요청 (명세서 7.2.2). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChallengeCreateRequest {

    @NotBlank(message = "카테고리는 필수입니다")
    private String category;

    @NotBlank(message = "종목은 필수입니다")
    private String exercise;

    @NotNull(message = "팀 정원은 필수입니다")
    @Positive(message = "팀 정원은 1 이상이어야 합니다")
    private Integer teamCapacity;

    @NotNull(message = "팀 수는 필수입니다")
    @Positive(message = "팀 수는 1 이상이어야 합니다")
    private Integer teamCount;

    @NotNull(message = "상금 받을 팀 수는 필수입니다")
    @Positive(message = "상금 받을 팀 수는 1 이상이어야 합니다")
    private Integer prizeTeamCount;

    @NotNull(message = "참가 예치금은 필수입니다")
    @Positive(message = "참가 예치금은 0보다 큰 값이어야 합니다")
    private Long depositAmount;

    @NotNull(message = "모집 기간은 필수입니다")
    @Valid
    private PeriodRequest recruitPeriod;

    @NotNull(message = "수행 기간은 필수입니다")
    @Valid
    private PeriodRequest performPeriod;

    /** ISO-8601 기간 (예: "P2D") */
    @NotNull(message = "확인 윈도우 길이는 필수입니다")
    private Duration confirmWindowLength;

    @NotNull(message = "하루 인증 횟수 상한은 필수입니다")
    @Min(value = 1, message = "하루 인증 횟수 상한은 1 이상이어야 합니다")
    private Integer dailyCap;

    @NotNull(message = "인증 주기 방식은 필수입니다")
    private CycleMode cycleMode;

    @NotNull(message = "인증 주기 간격은 필수입니다")
    @Positive(message = "인증 주기 간격은 1 이상이어야 합니다")
    private Integer cycleInterval;

    /** 교차 검증: 상금 받을 팀 수 < 팀 수. 위반 시 E-REG-PRIZE-COUNT (명세서 7.2.2) */
    @AssertTrue(message = "상금 받을 팀 수는 팀 수보다 작아야 합니다")
    public boolean isPrizeTeamCountValid() {
        if (prizeTeamCount == null || teamCount == null) return true;
        return prizeTeamCount < teamCount;
    }
}
