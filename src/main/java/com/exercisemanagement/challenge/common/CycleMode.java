package com.exercisemanagement.challenge.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 인증·목표 주기 방식 (명세서 7.2.1).
 * DB에는 상수명(EVERY_N_DAYS 등)을 저장하고, JSON에는 한글 값을 내보낸다.
 */
public enum CycleMode {
    EVERY_N_DAYS("며칠에 한 번"),
    N_PER_WEEK("주 며칠");

    private final String label;

    CycleMode(String label) { this.label = label; }

    @JsonValue
    public String getLabel() { return label; }

    @JsonCreator
    public static CycleMode from(String label) {
        for (CycleMode v : values()) {
            if (v.label.equals(label) || v.name().equals(label)) return v;
        }
        throw new IllegalArgumentException("알 수 없는 주기 방식: " + label);
    }
}
