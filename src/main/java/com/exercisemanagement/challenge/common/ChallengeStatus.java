package com.exercisemanagement.challenge.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 챌린지 상태 (명세서 7.2.1). */
public enum ChallengeStatus {
    RECRUITING("모집"), STARTED("시작"), ENDED("종료"), VOID("무산");

    private final String label;

    ChallengeStatus(String label) { this.label = label; }

    @JsonValue
    public String getLabel() { return label; }

    @JsonCreator
    public static ChallengeStatus from(String label) {
        for (ChallengeStatus v : values()) {
            if (v.label.equals(label) || v.name().equals(label)) return v;
        }
        throw new IllegalArgumentException("알 수 없는 챌린지 상태: " + label);
    }
}
