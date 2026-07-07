package com.exercisemanagement.challenge.common;

/** 상금 분배 기록 유형 (명세서 7.1.9·7.1.10). */
public enum PrizeEntryType {
    TEAM_SHARE,         // 팀 몫
    SYSTEM_RECLAIM,     // 순위 분배 나머지 시스템 환수
    MEMBER_SHARE,       // 팀원 분배
    REMAINDER_RECLAIM   // 팀 내 분배 나머지 환수
}
