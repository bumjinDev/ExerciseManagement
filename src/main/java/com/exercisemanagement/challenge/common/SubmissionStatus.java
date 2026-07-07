package com.exercisemanagement.challenge.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 제출 상태 (명세서 7.2.1). 확인 대기가 유일한 진입 상태, 나머지는 종착. */
public enum SubmissionStatus {
    PENDING("확인 대기"), CONFIRMED("확인 완료"), REJECTED("반려"), EXPIRED("만료");

    private final String label;

    SubmissionStatus(String label) { this.label = label; }

    @JsonValue
    public String getLabel() { return label; }

    @JsonCreator
    public static SubmissionStatus from(String label) {
        for (SubmissionStatus v : values()) {
            if (v.label.equals(label) || v.name().equals(label)) return v;
        }
        throw new IllegalArgumentException("알 수 없는 제출 상태: " + label);
    }
}
