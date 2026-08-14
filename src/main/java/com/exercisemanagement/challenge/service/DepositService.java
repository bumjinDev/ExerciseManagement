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
 * <p>
 * 돈의 이동은 원장(DEPOSIT_LEDGER)에 append(추가)로만 기록하고,
 * DEPOSIT_BALANCE는 조회용 현재 값을 유지한다.
 * 회계 규칙(변경사항 문서 4-10): 원장 amount 합 = 현재 잔액.
 * </p>
 * 쉽게 말해:
 * <ul>
 *   <li>원장(ledger) = 통장 거래내역. 한 번 쓰면 수정/삭제 없이 한 줄씩 쌓기만 한다.</li>
 *   <li>잔액(balance) = 통장에 찍히는 현재 금액. 매번 거래내역을 전부 더하지 않도록 따로 보관한다.</li>
 *   <li>그래서 "거래내역 amount의 합 = 현재 잔액"이 항상 성립해야 한다(안 맞으면 버그).</li>
 * </ul>
 */
@Service
public class DepositService {

    private final DepositBalanceRepository balanceRepository;      // 잔액(현재 값) 저장소
    private final DepositLedgerRepository ledgerRepository;        // 원장(거래내역) 저장소
    private final ParticipationRepository participationRepository; // 참가 신청 조회용
    private final IdGenerator idGenerator;                         // DB 시퀀스 기반 PK 생성기 ("dep_N")

    public DepositService(DepositBalanceRepository balanceRepository,
                          DepositLedgerRepository ledgerRepository,
                          ParticipationRepository participationRepository,
                          IdGenerator idGenerator) {
        this.balanceRepository = balanceRepository;
        this.ledgerRepository = ledgerRepository;
        this.participationRepository = participationRepository;
        this.idGenerator = idGenerator;
    }

    /**
     * 모의 충전. 외부 결제(PG) 연동 없이 잔액에 바로 반영한다.
     *
     * @param participantId 충전할 참가자 ID
     * @param amount        충전 금액(원). 0 이하이면 예외
     * @return 충전 후 현재 잔액
     * @throws ChallengeApiException E_DEP_INVALID_AMOUNT — 금액이 0 이하일 때
     */
    @Transactional
    public long charge(String participantId, long amount) {
        // 1) 금액 검증: 0원 이하 충전은 허용하지 않는다
        if (amount <= 0) {
            throw new ChallengeApiException(ErrorCode.E_DEP_INVALID_AMOUNT);
        }
        // 2) 잔액 행 조회. 첫 충전이라 행이 없으면 잔액 0짜리 새 행을 만든다
        DepositBalance balance = balanceRepository.findById(participantId)
                .orElseGet(() -> new DepositBalance(participantId, 0L));
        // 3) 잔액에 충전액을 더해 저장
        balance.setBalance(balance.getBalance() + amount);
        balanceRepository.save(balance);

        // 4) 원장에 "충전 +amount" 한 줄 기록.
        //    entryId: DB 시퀀스에서 다음 번호를 받아 만든 PK (예: "dep_7") — 동시 요청에도 중복 없음.
        //    멱등 키가 "charge:dep_7"처럼 entryId 기반이므로 충전은 호출할 때마다 새 기록으로 남는다.
        String entryId = idGenerator.ledgerEntryId();
        appendLedger(entryId, participantId, null, DepositEntryType.CHARGE, amount, "charge:" + entryId);
        return balance.getBalance();
    }

    /**
     * 참가 신청 완료 시 예치금 차감 (F002).
     *
     * @param participantId   차감 대상 참가자 ID
     * @param challengeId     참가하는 챌린지 ID
     * @param amount          차감할 예치금(양수로 전달, 원장에는 -amount로 기록)
     * @param participationId 참가 신청 ID. 멱등 키 "join:{participationId}"로 쓰인다
     * @throws ChallengeApiException E_APP_INSUFFICIENT_BALANCE — 잔액 행이 없거나 잔액이 부족할 때
     */
    @Transactional
    public void debitForJoin(String participantId, String challengeId, long amount, String participationId) {
        // 1) 잔액 행이 아예 없다 = 충전 이력이 없다 = 잔액 부족과 동일하게 처리
        DepositBalance balance = balanceRepository.findById(participantId)
                .orElseThrow(() -> new ChallengeApiException(ErrorCode.E_APP_INSUFFICIENT_BALANCE));
        // 2) 잔액 부족 검사
        if (balance.getBalance() < amount) {
            throw new ChallengeApiException(ErrorCode.E_APP_INSUFFICIENT_BALANCE);
        }
        // 3) 잔액에서 차감 후 저장
        balance.setBalance(balance.getBalance() - amount);
        balanceRepository.save(balance);
        // 4) 원장에는 음수(-amount)로 기록해 "빠져나간 돈"임을 표현.
        //    멱등 키 "join:참가ID" 덕분에 어떤 참가 신청 때문에 차감됐는지 추적할 수 있다.
        appendLedger(idGenerator.ledgerEntryId(), participantId, challengeId,
                DepositEntryType.JOIN_DEBIT, -amount, "join:" + participationId);
    }

    /**
     * 잔액 복귀 이동 (환급 REFUND, 무산 반환 VOID_RETURN 등).
     * 멱등 키로 중복 기록을 막는다 — 같은 요청이 두 번 와도 돈은 한 번만 들어온다.
     *
     * @param participantId  돈을 돌려받을 참가자 ID
     * @param challengeId    관련 챌린지 ID
     * @param amount         복귀 금액(양수)
     * @param type           원장 기록 유형 (REFUND / VOID_RETURN 등)
     * @param idempotencyKey 멱등 키. 같은 키가 이미 원장에 있으면 아무것도 하지 않는다
     */
    @Transactional
    public void credit(String participantId, String challengeId, long amount,
                       DepositEntryType type, String idempotencyKey) {
        // 1) 멱등성 검사: 같은 키로 이미 기록했다면 이중 입금 방지를 위해 조용히 종료 (8.4.3)
        if (ledgerRepository.existsByIdempotencyKey(idempotencyKey)) {
            return; // 같은 이동은 한 번만
        }
        // 2) 잔액 행 조회(없으면 0으로 생성) 후 금액을 더해 저장
        DepositBalance balance = balanceRepository.findById(participantId)
                .orElseGet(() -> new DepositBalance(participantId, 0L));
        balance.setBalance(balance.getBalance() + amount);
        balanceRepository.save(balance);
        // 3) 원장에 +amount 기록. 멱등 키도 함께 저장해 다음 중복 호출을 걸러낸다
        appendLedger(idGenerator.ledgerEntryId(), participantId, challengeId, type, amount, idempotencyKey);
    }

    /**
     * 차감(몰수) 확정 기록. 실제 돈은 참가 신청 때 이미 빠져나갔으므로(JOIN_DEBIT),
     * 잔액 변화 없이 amount 0짜리 "몰수 확정" 상태 기록만 원장에 남긴다.
     *
     * @param participantId  대상 참가자 ID
     * @param challengeId    챌린지 ID
     * @param idempotencyKey 멱등 키. 같은 키가 이미 원장에 있으면 건너뛴다
     */
    @Transactional
    public void recordForfeit(String participantId, String challengeId, String idempotencyKey) {
        // 1) 멱등성 검사: 이미 몰수 기록이 있으면 중복 기록하지 않는다
        if (ledgerRepository.existsByIdempotencyKey(idempotencyKey)) {
            return;
        }
        // 2) 잔액은 건드리지 않고, 원장에 FORFEIT / 금액 0 상태 기록만 추가
        appendLedger(idGenerator.ledgerEntryId(), participantId, challengeId,
                DepositEntryType.FORFEIT, 0L, idempotencyKey);
    }

    /**
     * 잔액과 챌린지별 예치 상태 조회 (F010 출력 모델).
     *
     * @param participantId 조회할 참가자 ID
     * @return 현재 잔액 + 참가한 챌린지별 (챌린지 ID, 예치금, 상태) 목록
     */
    @Transactional(readOnly = true)
    public DepositStatusResponse getStatus(String participantId) {
        // 1) 현재 잔액. 잔액 행이 없으면(충전 이력 없음) 0으로 본다
        long balance = balanceRepository.findById(participantId)
                .map(DepositBalance::getBalance).orElse(0L);

        // 2) 내가 참가한 모든 챌린지를 돌며 예치금과 상태("진행 중" 등)를 모은다
        List<ChallengeDepositEntry> entries = new ArrayList<>();
        for (Participation p : participationRepository.findByParticipantId(participantId)) {
            entries.add(new ChallengeDepositEntry(p.getChallengeId(), p.getDepositAmount(),
                    resolveState(participantId, p.getChallengeId())));
        }
        return new DepositStatusResponse(balance, entries);
    }

    /**
     * 챌린지별 예치 상태 판정. 별도의 상태 컬럼 없이 원장에 남은 기록의 "유형"만 보고 판단한다.
     *
     * @param participantId 참가자 ID
     * @param challengeId   챌린지 ID
     * @return "무산 반환" / "환급" / "차감" / "진행 중" 중 하나
     */
    private String resolveState(String participantId, String challengeId) {
        // 이 참가자 × 이 챌린지의 원장 기록을 모두 가져와, 종결 유형이 존재하는지 확인
        List<DepositLedgerEntry> entries = ledgerRepository
                .findByParticipantIdAndChallengeId(participantId, challengeId);
        boolean refund = entries.stream().anyMatch(e -> e.getEntryType() == DepositEntryType.REFUND);
        boolean forfeit = entries.stream().anyMatch(e -> e.getEntryType() == DepositEntryType.FORFEIT);
        boolean voidReturn = entries.stream().anyMatch(e -> e.getEntryType() == DepositEntryType.VOID_RETURN);
        // 우선순위: 무산 반환 > 환급 > 차감. 종결 기록이 하나도 없으면 아직 "진행 중"
        if (voidReturn) return "무산 반환";
        if (refund) return "환급";
        if (forfeit) return "차감";
        return "진행 중";
    }

    /**
     * 원장에 한 줄 추가(append). 이 서비스의 모든 원장 기록은 이 메서드를 통해서만 저장된다.
     *
     * @param entryId        원장 PK (IdGenerator가 DB 시퀀스로 만든 "dep_N" 형식)
     * @param participantId  참가자 ID
     * @param challengeId    챌린지 ID (충전처럼 챌린지와 무관한 기록이면 null)
     * @param type           기록 유형 (CHARGE / JOIN_DEBIT / REFUND / FORFEIT / VOID_RETURN)
     * @param amount         금액. 들어온 돈은 +, 나간 돈은 -, 상태 기록만이면 0
     * @param idempotencyKey 멱등 키 (같은 이동의 중복 기록 방지용)
     */
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
