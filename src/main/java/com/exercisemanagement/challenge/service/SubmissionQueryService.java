package com.exercisemanagement.challenge.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exercisemanagement.challenge.common.ParticipationStatus;
import com.exercisemanagement.challenge.common.SubmissionStatus;
import com.exercisemanagement.challenge.dto.response.MySubmissionsResponse;
import com.exercisemanagement.challenge.dto.response.MySubmissionsResponse.SubmissionDateGroup;
import com.exercisemanagement.challenge.dto.response.MySubmissionsResponse.SubmissionSummary;
import com.exercisemanagement.challenge.dto.response.PendingSubmissionsResponse;
import com.exercisemanagement.challenge.dto.response.PendingSubmissionsResponse.PendingSubmissionEntry;
import com.exercisemanagement.challenge.entity.Participation;
import com.exercisemanagement.challenge.entity.Submission;
import com.exercisemanagement.challenge.exception.ChallengeApiException;
import com.exercisemanagement.challenge.exception.ErrorCode;
import com.exercisemanagement.challenge.repository.ParticipationRepository;
import com.exercisemanagement.challenge.repository.SubmissionRepository;

/**
 * 제출 조회: 내 제출 현황 (명세 6.7) + 확인 대기 목록 (신설 — 변경사항 문서 3-1).
 * 상태를 바꾸지 않는 읽기 전용 계층.
 */
@Service
public class SubmissionQueryService {

    private final ParticipationRepository participationRepository;
    private final SubmissionRepository submissionRepository;

    public SubmissionQueryService(ParticipationRepository participationRepository,
                                  SubmissionRepository submissionRepository) {
        this.participationRepository = participationRepository;
        this.submissionRepository = submissionRepository;
    }

    /** 내 제출 현황 (6.7): 일반 인증 한정, 수행 날짜 내림차순·날짜 안 등록 시점 순. */
    @Transactional(readOnly = true)
    public MySubmissionsResponse mySubmissions(String challengeId, String requesterId, LocalDate date) {
        requireParticipant(challengeId, requesterId, ErrorCode.E_SUB_NOT_PARTICIPANT);

        List<Submission> submissions = (date == null)
                ? submissionRepository.findByChallengeIdAndParticipantIdAndBaselineFalseOrderByLinkedDateDescRegisteredAtAsc(
                        challengeId, requesterId)
                : submissionRepository.findByChallengeIdAndParticipantIdAndLinkedDateAndBaselineFalseOrderByRegisteredAtAsc(
                        challengeId, requesterId, date);

        Map<LocalDate, List<SubmissionSummary>> grouped = new LinkedHashMap<>();
        for (Submission s : submissions) {
            grouped.computeIfAbsent(s.getLinkedDate(), d -> new ArrayList<>())
                    .add(new SubmissionSummary(s.getSubmissionId(), s.getWeight(), s.getReps(),
                            s.getVolume(), s.getStatus(), s.getRegisteredAt()));
        }

        List<SubmissionDateGroup> groups = grouped.entrySet().stream()
                .map(e -> new SubmissionDateGroup(e.getKey(), e.getValue()))
                .toList();
        return new MySubmissionsResponse(groups);
    }

    /** 확인 대기 목록 (신설): 요청자 팀의 확인 대기 제출(본인 제출 제외). */
    @Transactional(readOnly = true)
    public PendingSubmissionsResponse pendingSubmissions(String challengeId, String requesterId) {
        Participation requester = requireParticipant(challengeId, requesterId, ErrorCode.E_SUB_NOT_PARTICIPANT);

        if (requester.getTeamId() == null) {
            // 편성 전에는 확인 대상이 없다
            return new PendingSubmissionsResponse(challengeId, null, List.of());
        }

        List<String> teammateIds = participationRepository
                .findByTeamIdAndStatus(requester.getTeamId(), ParticipationStatus.ACTIVE).stream()
                .map(Participation::getParticipantId)
                .filter(id -> !id.equals(requesterId))
                .toList();

        List<PendingSubmissionEntry> entries = submissionRepository
                .findByChallengeIdAndStatusAndBaselineFalse(challengeId, SubmissionStatus.PENDING).stream()
                .filter(s -> teammateIds.contains(s.getParticipantId()))
                .map(s -> new PendingSubmissionEntry(s.getSubmissionId(), s.getParticipantId(),
                        s.getWeight(), s.getReps(), s.getVolume(), s.getLinkedDate(), s.getRegisteredAt()))
                .toList();

        return new PendingSubmissionsResponse(challengeId, requester.getTeamId(), entries);
    }

    /**
     * 인증샷 파일 조회 (신설 — 팀원 확인 화면용, 변경사항 문서 3-5).
     * 그 챌린지의 진행 중 참가자만 볼 수 있다.
     */
    @Transactional(readOnly = true)
    public byte[] photo(String submissionId, String requesterId) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ChallengeApiException(ErrorCode.E_SUB_NOT_FOUND));
        requireParticipant(submission.getChallengeId(), requesterId, ErrorCode.E_SUB_NOT_PARTICIPANT);

        if (submission.getPhotoPath() == null) {
            throw new ChallengeApiException(ErrorCode.E_SUB_NOT_FOUND, "인증샷 파일이 없습니다.");
        }
        try {
            return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(submission.getPhotoPath()));
        } catch (java.io.IOException e) {
            throw new ChallengeApiException(ErrorCode.E_SUB_NOT_FOUND, "인증샷 파일을 읽을 수 없습니다.");
        }
    }

    private Participation requireParticipant(String challengeId, String requesterId, ErrorCode errorCode) {
        return participationRepository.findByChallengeIdAndParticipantId(challengeId, requesterId)
                .filter(p -> p.getStatus() == ParticipationStatus.ACTIVE)
                .orElseThrow(() -> new ChallengeApiException(errorCode));
    }
}
