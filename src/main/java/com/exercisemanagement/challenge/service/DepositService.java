package com.exercisemanagement.challenge.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exercisemanagement.challenge.common.DepositEntryType;
import com.exercisemanagement.challenge.dto.response.DepositStatusResponse;
import com.exercisemanagement.challenge.dto.response.DepositStatusResponse.ChallengeDepositEntry;
import com.exercisemanagement.challenge.entity.DepositBalance;
import com.exercisemanagement.challenge.entity.DepositLedgerEntry;
import com.exercisemanagement.challenge.entity.Participation;
import com.exercisemanagement.challenge.exception.ChallengeApiException;
import com.exercisemanagement.challenge.exception.ErrorCode;
import com.exercisemanagement.challenge.repository.DepositBalanceRepository;
import com.exercisemanagement.challenge.repository.DepositLedgerRepository;
import com.exercisemanagement.challenge.repository.ParticipationRepository;
import com.exercisemanagement.challenge.support.IdGenerator;

/**
 * 예치 서비스 (F010 + 원장 규칙 8.4.3).
 * 이동은 원장에 append로만 기록하고, DEPOSIT_BALANCE는 조회용 현재 값을 유지한다.
 * 회계 규칙(변경사항 문서 4-10): 원장 amount 합 = 현재 잔액.
 */
@Service
public class DepositService {

    private final DepositBalanceRepository balanceRepository;
    private final DepositLedgerRepository ledgerRepository;
    private final ParticipationRepository participationRepository;
    private final IdGenerator idGenerator;

    public DepositService(DepositBalanceRepository balanceRepository,
                          DepositLedgerRepository ledgerRepository,
                          ParticipationRepository participationRepository,
                          IdGenerator idGenerator) {
        this.balanceRepository = balanceRepository;
        this.ledgerRepository = ledgerRepository;
        this.participationRepository = participationRepository;
        this.idGenerator = idGenerator;
    }

    /** 모의 충전. 외부 결제 연동 없이 잔액에 반영한다. */
    @Transactional
    public long charge(String participantId, long amount) {
        if (amount <= 0) {
            throw new ChallengeApiException(ErrorCode.E_DEP_INVALID_AMOUNT);
        }
        DepositBalance balance = balanceRepository.findById(participantId)
                .orElseGet(() -> new DepositBalance(participantId, 0L));
        balance.setBalance(balance.getBalance() + amount);
        balanceRepository.save(balance);

        String entryId = idGenerator.ledgerEntryId();
        appendLedger(entryId, participantId, null, DepositEntryType.CHARGE, amount, "charge:" + entryId);
        return balance.getBalance();
    }

    /** 참가 신청 완료 시 예치금 차감 (F002). 잔액 부족이면 E-APP-INSUFFICIENT-BALANCE. */
    @Transactional
    public void debitForJoin(String participantId, String challengeId, long amount, String participationId) {
        DepositBalance balance = balanceRepository.findById(participantId)
                .orElseThrow(() -> new ChallengeApiException(ErrorCode.E_APP_INSUFFICIENT_BALANCE));
        if (balance.getBalance() < amount) {
            throw new ChallengeApiException(ErrorCode.E_APP_INSUFFICIENT_BALANCE);
        }
        balance.setBalance(balance.getBalance() - amount);
        balanceRepository.save(balance);
        appendLedger(idGenerator.ledgerEntryId(), participantId, challengeId,
                DepositEntryType.JOIN_DEBIT, -amount, "join:" + participationId);
    }

    /** 잔액 복귀 이동 (환급·무산 반환). 멱등 키로 중복 기록을 막는다. */
    @Transactional
    public void credit(String participantId, String challengeId, long amount,
                       DepositEntryType type, String idempotencyKey) {
        if (ledgerRepository.existsByIdempotencyKey(idempotencyKey)) {
            return; // 같은 이동은 한 번만 (8.4.3)
        }
        DepositBalance balance = balanceRepository.findById(participantId)
                .orElseGet(() -> new DepositBalance(participantId, 0L));
        balance.setBalance(balance.getBalance() + amount);
        balanceRepository.save(balance);
        appendLedger(idGenerator.ledgerEntryId(), participantId, challengeId, type, amount, idempotencyKey);
    }

    /** 차감(몰수) 확정 기록. 금액 이동은 참가 차감에서 이미 발생했으므로 amount 0의 상태 기록이다. */
    @Transactional
    public void recordForfeit(String participantId, String challengeId, String idempotencyKey) {
        if (ledgerRepository.existsByIdempotencyKey(idempotencyKey)) {
            return;
        }
        appendLedger(idGenerator.ledgerEntryId(), participantId, challengeId,
                DepositEntryType.FORFEIT, 0L, idempotencyKey);
    }

    /** 잔액과 챌린지별 예치 상태 (F010 출력 모델). */
    @Transactional(readOnly = true)
    public DepositStatusResponse getStatus(String participantId) {
        long balance = balanceRepository.findById(participantId)
                .map(DepositBalance::getBalance).orElse(0L);

        List<ChallengeDepositEntry> entries = new ArrayList<>();
        for (Participation p : participationRepository.findByParticipantId(participantId)) {
            entries.add(new ChallengeDepositEntry(p.getChallengeId(), p.getDepositAmount(),
                    resolveState(participantId, p.getChallengeId())));
        }
        return new DepositStatusResponse(balance, entries);
    }

    /** 챌린지별 예치 상태: 원장 기록으로 판정한다 (진행 중 / 환급 / 차감 / 무산 반환). */
    private String resolveState(String participantId, String challengeId) {
        List<DepositLedgerEntry> entries = ledgerRepository
                .findByParticipantIdAndChallengeId(participantId, challengeId);
        boolean refund = entries.stream().anyMatch(e -> e.getEntryType() == DepositEntryType.REFUND);
        boolean forfeit = entries.stream().anyMatch(e -> e.getEntryType() == DepositEntryType.FORFEIT);
        boolean voidReturn = entries.stream().anyMatch(e -> e.getEntryType() == DepositEntryType.VOID_RETURN);
        if (voidReturn) return "무산 반환";
        if (refund) return "환급";
        if (forfeit) return "차감";
        return "진행 중";
    }

    private void appendLedger(String entryId, String participantId, String challengeId,
                              DepositEntryType type, long amount, String idempotencyKey) {
        ledgerRepository.save(DepositLedgerEntry.builder()
                .entryId(entryId)
                .participantId(participantId)
                .challengeId(challengeId)
                .entryType(type)
                .amount(amount)
                .idempotencyKey(idempotencyKey)
                .createdAt(LocalDateTime.now())
                .build());
    }
}
