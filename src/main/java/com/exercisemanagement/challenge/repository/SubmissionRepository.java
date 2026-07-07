package com.exercisemanagement.challenge.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.exercisemanagement.challenge.common.SubmissionStatus;
import com.exercisemanagement.challenge.entity.Submission;

public interface SubmissionRepository extends JpaRepository<Submission, String> {

    /** 해시 중복 1차 대조 (8.2.1) */
    boolean existsByChallengeIdAndPhotoHash(String challengeId, String photoHash);

    /** 하루 상한 판정: 같은 수행 날짜의 인정(PENDING·CONFIRMED) 일반 제출 수 */
    @Query("SELECT COUNT(s) FROM Submission s WHERE s.challengeId = :challengeId AND s.participantId = :participantId "
            + "AND s.linkedDate = :date AND s.baseline = false AND s.status IN :countable")
    long countDailySubmissions(@Param("challengeId") String challengeId,
                               @Param("participantId") String participantId,
                               @Param("date") LocalDate date,
                               @Param("countable") Collection<SubmissionStatus> countable);

    /** 인증 주기 판정: 인정 일반 제출의 수행 날짜 목록(중복 제거) */
    @Query("SELECT DISTINCT s.linkedDate FROM Submission s WHERE s.challengeId = :challengeId "
            + "AND s.participantId = :participantId AND s.baseline = false AND s.status IN :countable")
    List<LocalDate> findCountableDates(@Param("challengeId") String challengeId,
                                       @Param("participantId") String participantId,
                                       @Param("countable") Collection<SubmissionStatus> countable);

    /**
     * 편성 실력·개인 목표 기준값 산출용: 그 종목의 최근 30일 확인 완료 제출(기준 측정 행 포함, 명세 F003·7.1.4).
     * "그 종목" 판정은 제출이 속한 챌린지의 exercise로 한다(변경사항 문서 4-16).
     */
    @Query("SELECT s FROM Submission s, Challenge c WHERE s.challengeId = c.challengeId "
            + "AND c.exercise = :exercise AND s.participantId = :participantId "
            + "AND s.status = :status AND s.linkedDate >= :fromDate")
    List<Submission> findRecentConfirmedByExercise(@Param("participantId") String participantId,
                                                   @Param("exercise") String exercise,
                                                   @Param("status") SubmissionStatus status,
                                                   @Param("fromDate") LocalDate fromDate);

    /** 내 제출 현황 (6.7) — 일반 인증 전체 */
    List<Submission> findByChallengeIdAndParticipantIdAndBaselineFalseOrderByLinkedDateDescRegisteredAtAsc(
            String challengeId, String participantId);

    /** 내 제출 현황 (6.7) — 특정 수행 날짜 */
    List<Submission> findByChallengeIdAndParticipantIdAndLinkedDateAndBaselineFalseOrderByRegisteredAtAsc(
            String challengeId, String participantId, LocalDate linkedDate);

    /** 확인 대기 목록(신설 API)·만료 처리(B-04) */
    List<Submission> findByChallengeIdAndStatusAndBaselineFalse(String challengeId, SubmissionStatus status);

    /** 순위 산출: 확인 완료된 일반 제출 전체 (기준 구현은 DB 집계) */
    List<Submission> findByChallengeIdAndParticipantIdInAndStatusAndBaselineFalse(
            String challengeId, Collection<String> participantIds, SubmissionStatus status);

    /** 개인 목표 판정: 확인 완료 일반 제출 */
    List<Submission> findByChallengeIdAndParticipantIdAndStatusAndBaselineFalse(
            String challengeId, String participantId, SubmissionStatus status);
}
