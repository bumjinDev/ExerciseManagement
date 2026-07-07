package com.exercisemanagement.challenge.dto.request;

import com.exercisemanagement.challenge.common.ConfirmationDecision;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 팀원 확인 요청 (명세서 7.2.2). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmationRequest {

    @NotNull(message = "확인 또는 반려 판정은 필수입니다")
    private ConfirmationDecision decision;
}
