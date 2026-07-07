package com.exercisemanagement.challenge.common;

/** 참가 상태 (명세서 7.1.3: 진행 중 / 이탈). DB에는 상수명 저장. */
public enum ParticipationStatus {
    ACTIVE,     // 진행 중
    WITHDRAWN   // 이탈
}
