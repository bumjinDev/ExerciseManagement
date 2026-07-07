package com.exercisemanagement.challenge.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 팀원 확인 판정 (명세서 7.2.1). */
public enum ConfirmationDecision {
    CONFIRM("확인"), REJECT("반려");

    private final String label;

    ConfirmationDecision(String label) { this.label = label; }

    @JsonValue
    public String getLabel() { return label; }

    @JsonCreator
    public static ConfirmationDecision from(String label) {
        for (ConfirmationDecision v : values()) {
            if (v.label.equals(label) || v.name().equals(label)) return v;
        }
        throw new IllegalArgumentException("알 수 없는 판정: " + label);
    }
}
