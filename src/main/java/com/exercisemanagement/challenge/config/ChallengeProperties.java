package com.exercisemanagement.challenge.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 챌린지 도메인 설정값 (application.yaml의 app.* 중 단계 3 항목).
 * base-prize-pool과 formation-sum-cap-percent는 잠정값이다(변경사항 문서 4-1·4-2).
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class ChallengeProperties {

    /** 운영 기본 상금풀. TODO: 잠정값 — 운영 정책 확정 대상 */
    private long basePrizePool;

    /** 팀 편성 합 상한 r(%). TODO: 잠정값 — 계측 후 재확정 (명세 12.3) */
    private double formationSumCapPercent;

    /** 인증샷 로컬 저장 디렉토리 */
    private String photoDir;

    /** 카테고리 → 종목 목록 (닫힌 목록, 명세 F001) */
    private Map<String, List<String>> exerciseCatalog;
}
