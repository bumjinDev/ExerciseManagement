package com.exercisemanagement.challenge.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 식별자 생성기. DB 시퀀스 값에 접두어를 붙여 문자열 ID를 만든다.
 * 형식 확정 근거: sql/03_challenge_tables.sql 헤더 (chal_1, part_1, ...)
 * <p>
 * 쉽게 말해:
 * <ul>
 *   <li>DB 시퀀스 = Oracle이 관리하는 "번호표 발급기". 뽑을 때마다 1, 2, 3... 하나씩 증가한다.</li>
 *   <li>번호 발급을 애플리케이션이 아니라 DB가 하므로, 서버가 여러 대이거나 동시에 요청이
 *       몰려도 같은 번호가 두 번 나오지 않는다(충돌 없음).</li>
 *   <li>뽑은 번호 앞에 접두어를 붙여 "chal_1", "dep_7"처럼 만든다.
 *       ID만 봐도 어떤 테이블의 데이터인지 구분되는 것이 장점.</li>
 * </ul>
 */
@Component
public class IdGenerator {

    /** Spring이 제공하는 JDBC 헬퍼. 시퀀스 조회 SQL을 직접 실행하기 위해 사용한다. */
    private final JdbcTemplate jdbcTemplate;

    public IdGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 아래 메서드들은 전부 next()에 위임한다. "어느 접두어 + 어느 시퀀스"인지만 다르다.

    /** @return 챌린지 ID (예: "chal_1") */
    public String challengeId()     { return next("chal", "seq_challenge"); }

    /** @return 팀 ID (예: "team_1") */
    public String teamId()          { return next("team", "seq_team"); }

    /** @return 참가 신청 ID (예: "part_1") */
    public String participationId() { return next("part", "seq_participation"); }

    /** @return 인증 제출 ID (예: "sub_1") */
    public String submissionId()    { return next("sub", "seq_submission"); }

    /** @return 인증 확인 ID (예: "cfm_1") */
    public String confirmationId()  { return next("cfm", "seq_confirmation"); }

    /** @return 예치금 원장 기록 ID (예: "dep_1") — DepositService에서 사용 */
    public String ledgerEntryId()   { return next("dep", "seq_deposit_ledger"); }

    /** @return 정산 ID (예: "stl_1") */
    public String settlementId()    { return next("stl", "seq_settlement"); }

    /** @return 팀 상금 ID (예: "tpz_1") */
    public String teamPrizeId()     { return next("tpz", "seq_team_prize"); }

    /** @return 팀원 상금 ID (예: "mpz_1") */
    public String memberPrizeId()   { return next("mpz", "seq_member_prize"); }

    /** @return 대기 신청 ID (예: "pnd_1") */
    public String pendingId()       { return next("pnd", "seq_pending_application"); }

    /**
     * 실제 ID 생성 로직.
     *
     * @param prefix   ID 앞에 붙일 접두어 (예: "dep")
     * @param sequence 번호를 발급할 Oracle 시퀀스 이름 (예: "seq_deposit_ledger")
     * @return "{접두어}_{시퀀스 번호}" 형식의 문자열 ID (예: "dep_7")
     */
    private String next(String prefix, String sequence) {
        // 1) "SELECT seq_xxx.NEXTVAL FROM dual" — 시퀀스에서 다음 번호를 하나 뽑는다.
        //    NEXTVAL은 호출할 때마다 증가하며, DB가 발급하므로 동시 호출에도 중복이 없다.
        //    (dual은 Oracle에서 이런 단일 값 조회에 쓰는 가상 테이블)
        Long value = jdbcTemplate.queryForObject("SELECT " + sequence + ".NEXTVAL FROM dual", Long.class);
        // 2) 접두어 + "_" + 번호를 이어붙여 최종 ID 완성 (예: "dep" + "_" + 7 → "dep_7")
        return prefix + "_" + value;
    }
}
