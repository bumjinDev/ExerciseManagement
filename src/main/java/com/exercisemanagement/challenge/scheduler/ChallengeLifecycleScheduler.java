package com.exercisemanagement.challenge.scheduler;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.exercisemanagement.challenge.common.ChallengeStatus;
import com.exercisemanagement.challenge.common.ParticipationStatus;
import com.exercisemanagement.challenge.entity.Challenge;
import com.exercisemanagement.challenge.repository.ChallengeRepository;
import com.exercisemanagement.challenge.repository.ParticipationRepository;
import com.exercisemanagement.challenge.service.ParticipationService;
import com.exercisemanagement.challenge.service.SettlementService;

/**
 * 마감·윈도우 스케줄러 (명세 9장).
 * 실행 주기: 60초 고정 지연 폴링 (구현 확정 — 변경사항 문서 4-13).
 *
 * B-01 모집 종료 감지 → 미달 무산·예치 반환
 * B-02 제출 완전 마감: 별도 처리 없음 — 제출 API가 시각 대조로 집행 (명세 8.2.1 주석)
 * B-03 윈도우 종료 감지 → B-04 만료 처리 → B-05 정산 단일 실행
 */
@Component
public class ChallengeLifecycleScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ChallengeLifecycleScheduler.class);

    private final ChallengeRepository challengeRepository;
    private final ParticipationRepository participationRepository;
    private final ParticipationService participationService;
    private final SettlementService settlementService;

    public ChallengeLifecycleScheduler(ChallengeRepository challengeRepository,
                                       ParticipationRepository participationRepository,
                                       ParticipationService participationService,
                                       SettlementService settlementService) {
        this.challengeRepository = challengeRepository;
        this.participationRepository = participationRepository;
        this.participationService = participationService;
        this.settlementService = settlementService;
    }

    @Scheduled(fixedDelay = 60_000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        detectRecruitClosed(now);   // B-01
        detectWindowClosed(now);    // B-03 → B-04 → B-05
    }

    /** B-01: 모집 종료 시각이 지난 모집 상태 챌린지 — 목표 인원 미달이면 무산 처리 */
    private void detectRecruitClosed(LocalDateTime now) {
        for (Challenge c : challengeRepository.findByStatusAndRecruitEndBefore(ChallengeStatus.RECRUITING, now)) {
            try {
                long active = participationRepository.countByChallengeIdAndStatus(
                        c.getChallengeId(), ParticipationStatus.ACTIVE);
                if (active < c.targetParticipants()) {
                    participationService.voidChallenge(c.getChallengeId());
                }
                // 인원이 찼다면 신청 완료 시점에 이미 편성·시작되었어야 하므로 여기서는 손대지 않는다
            } catch (Exception e) {
                logger.error("모집 종료 처리 실패: {}", c.getChallengeId(), e);
            }
        }
    }

    /** B-03: 확인 윈도우 종료 시점 도달 → 만료 처리와 정산 실행 */
    private void detectWindowClosed(LocalDateTime now) {
        for (Challenge c : challengeRepository.findByStatus(ChallengeStatus.STARTED)) {
            if (now.isBefore(c.confirmWindowEnd())) {
                continue;
            }
            try {
                settlementService.expireAndSettle(c.getChallengeId());
            } catch (Exception e) {
                logger.error("윈도우 종료 처리 실패: {}", c.getChallengeId(), e);
            }
        }
    }
}
