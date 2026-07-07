package com.exercisemanagement.challenge.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 식별자 생성기. DB 시퀀스 값에 접두어를 붙여 문자열 ID를 만든다.
 * 형식 확정 근거: sql/03_challenge_tables.sql 헤더 (chal_1, part_1, ...)
 */
@Component
public class IdGenerator {

    private final JdbcTemplate jdbcTemplate;

    public IdGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String challengeId()     { return next("chal", "seq_challenge"); }
    public String teamId()          { return next("team", "seq_team"); }
    public String participationId() { return next("part", "seq_participation"); }
    public String submissionId()    { return next("sub", "seq_submission"); }
    public String confirmationId()  { return next("cfm", "seq_confirmation"); }
    public String ledgerEntryId()   { return next("dep", "seq_deposit_ledger"); }
    public String settlementId()    { return next("stl", "seq_settlement"); }
    public String teamPrizeId()     { return next("tpz", "seq_team_prize"); }
    public String memberPrizeId()   { return next("mpz", "seq_member_prize"); }
    public String pendingId()       { return next("pnd", "seq_pending_application"); }

    private String next(String prefix, String sequence) {
        Long value = jdbcTemplate.queryForObject("SELECT " + sequence + ".NEXTVAL FROM dual", Long.class);
        return prefix + "_" + value;
    }
}
