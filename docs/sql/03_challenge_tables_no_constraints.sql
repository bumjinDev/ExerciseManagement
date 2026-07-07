-- =====================================================================
-- 03_challenge_tables_no_constraints.sql
-- 챌린지 도메인 테이블 10종 — 제약조건 없는 테스트용 변형
--
-- 용도: 로컬 테스트·계측(기술적 의사결정) 단계에서 사용한다.
--   03_challenge_tables.sql(원본)에서 PRIMARY KEY, FOREIGN KEY, UNIQUE, CHECK
--   제약을 전부 뺐다. 테이블·컬럼 구조와 시퀀스는 원본과 동일하다.
--   NOT NULL은 컬럼 정의로 유지했다(엔티티 매핑 전제값 — 이것도 빼려면 말해 달라).
--
-- [주의] 원본과 이 파일은 같은 테이블을 만들므로 둘 중 하나만 반영한다.
--   이 변형에서는 명세서가 저장 단계 방어로 정의한 장치들이 빠진다:
--     - 중복 신청 차단(PARTICIPATION 유니크), 해시 중복 2차 방어(SUBMISSION 유니크)
--     - 정족수 1 이중 가산 차단(CONFIRMATION 유니크), 정산 단일 실행(SETTLEMENT 유니크)
--     - 원장 멱등 키(DEPOSIT_LEDGER 유니크)
--   애플리케이션 계층 검증은 그대로 동작하지만, 동시 요청 경합의 최종 방어선은 없다.
--
-- 테스트 종료 후 원본 재적용 절차:
--   SQL> @03_reset_challenge_tables.sql     -- 챌린지 도메인 테이블·시퀀스 전체 삭제
--   SQL> @03_challenge_tables.sql           -- 제약 포함 원본 재생성
--
-- 실행 주체: EXERCISEMGMT 계정
--   sqlplus EXERCISEMGMT/<비밀번호>@localhost:1521/xe
--   SQL> @03_challenge_tables_no_constraints.sql
-- =====================================================================

-- 7.1.1 CHALLENGE : 단일 종목 인스턴스의 구성과 상태
CREATE TABLE challenge (
    challenge_id          VARCHAR2(40)       NOT NULL,
    category              VARCHAR2(50 CHAR)  NOT NULL,             -- 부위 카테고리
    exercise              VARCHAR2(100 CHAR) NOT NULL,             -- 단일 종목
    team_capacity         NUMBER(5)          NOT NULL,             -- 팀 정원
    team_count            NUMBER(5)          NOT NULL,             -- 팀 수
    prize_team_count      NUMBER(5)          NOT NULL,             -- 상금 받을 팀 수
    recruit_start         TIMESTAMP          NOT NULL,             -- 모집 시작
    recruit_end           TIMESTAMP          NOT NULL,             -- 모집 종료
    perform_start         TIMESTAMP          NOT NULL,             -- 수행 시작
    perform_end           TIMESTAMP          NOT NULL,             -- 수행 마감
    confirm_window_length NUMBER(19)         NOT NULL,             -- 확인 윈도우 길이(초 단위)
    daily_cap             NUMBER(3)          NOT NULL,             -- 하루 인증 횟수 상한
    cycle_mode            VARCHAR2(20)       NOT NULL,             -- 인증 주기 방식 (EVERY_N_DAYS / N_PER_WEEK)
    cycle_interval        NUMBER(3)          NOT NULL,             -- 인증 주기 간격
    deposit_amount        NUMBER(15)         NOT NULL,             -- 참가 예치금(신청 완료 시 차감액)
    base_prize_pool       NUMBER(15)         NOT NULL,             -- 운영 기본 상금풀
    status                VARCHAR2(20)       NOT NULL              -- 상태 (RECRUITING/STARTED/ENDED/VOID)
);

-- 7.1.2 TEAM : 챌린지에 속한 팀
CREATE TABLE team (
    team_id      VARCHAR2(40) NOT NULL,
    challenge_id VARCHAR2(40) NOT NULL
);

-- 7.1.3 PARTICIPATION : 참가 신청, 확정 실력·목표, 배정 팀, 예치 상태
CREATE TABLE participation (
    participation_id      VARCHAR2(40)      NOT NULL,
    challenge_id          VARCHAR2(40)      NOT NULL,
    participant_id        VARCHAR2(50 CHAR) NOT NULL,              -- 회원 아이디 (membertbl.id)
    formation_skill       NUMBER(15,4)      NOT NULL,              -- 편성 실력
    goal_baseline         NUMBER(15,4)      NOT NULL,              -- 개인 목표 기준값(편성 실력과 동일 값)
    intensity_coefficient NUMBER(6,3)       NOT NULL,              -- 강도 계수(1 이상)
    goal_cycle_mode       VARCHAR2(20)      NOT NULL,              -- 목표 빈도 방식
    goal_cycle_interval   NUMBER(3)         NOT NULL,              -- 목표 빈도 간격
    deposit_amount        NUMBER(15)        NOT NULL,              -- 이 참가에 걸린 예치금
    team_id               VARCHAR2(40),                            -- 배정 팀(편성 전 NULL)
    status                VARCHAR2(20)      NOT NULL               -- 상태 (ACTIVE/WITHDRAWN)
);

-- 7.1.4 SUBMISSION : 인증 제출과 상태 전이 (기준 측정 행 포함, is_baseline으로 구분)
CREATE TABLE submission (
    submission_id  VARCHAR2(40)      NOT NULL,
    challenge_id   VARCHAR2(40)      NOT NULL,
    participant_id VARCHAR2(50 CHAR) NOT NULL,                     -- 제출자
    weight         NUMBER(8,2)       NOT NULL,                     -- 무게
    reps           NUMBER(5)         NOT NULL,                     -- 횟수
    volume         NUMBER(15,4)      NOT NULL,                     -- 무게 × 횟수
    photo_hash     VARCHAR2(128)     NOT NULL,                     -- 사진 해시(중복 검사 대상)
    photo_path     VARCHAR2(300 CHAR),                             -- 인증샷 저장 경로(원본 파일의 추가 제안 컬럼)
    registered_at  TIMESTAMP         NOT NULL,                     -- 등록 시점
    linked_date    DATE              NOT NULL,                     -- 수행 날짜(하루 상한·주기·30일 집계 기준)
    status         VARCHAR2(20)      NOT NULL,                     -- 상태 (PENDING/CONFIRMED/REJECTED/EXPIRED)
    is_baseline    NUMBER(1)         NOT NULL                      -- 1: 기준 측정 행, 0: 일반 인증 행
);

-- 7.1.5 CONFIRMATION : 팀원 확인·반려 기록 (append 전용)
CREATE TABLE confirmation (
    confirmation_id VARCHAR2(40)      NOT NULL,
    submission_id   VARCHAR2(40)      NOT NULL,
    confirmer_id    VARCHAR2(50 CHAR) NOT NULL,                    -- 확인자(팀원)
    decision        VARCHAR2(10)      NOT NULL,                    -- CONFIRM / REJECT
    confirmed_at    TIMESTAMP         NOT NULL
);

-- 7.1.6 DEPOSIT_BALANCE : 예치 잔액 현재 값 (원본은 원장)
CREATE TABLE deposit_balance (
    participant_id VARCHAR2(50 CHAR) NOT NULL,
    balance        NUMBER(15)        NOT NULL
);

-- 7.1.7 DEPOSIT_LEDGER : 예치금 이동 원장 (append 전용)
CREATE TABLE deposit_ledger (
    entry_id        VARCHAR2(40)       NOT NULL,
    participant_id  VARCHAR2(50 CHAR)  NOT NULL,
    challenge_id    VARCHAR2(40),                                  -- 관련 챌린지(충전은 NULL)
    entry_type      VARCHAR2(30)       NOT NULL,                   -- CHARGE/JOIN_DEBIT/REFUND/FORFEIT/VOID_RETURN
    amount          NUMBER(15)         NOT NULL,                   -- 이동 금액(부호 포함)
    idempotency_key VARCHAR2(100 CHAR) NOT NULL,                   -- 같은 이동의 중복 기록 차단 키
    created_at      TIMESTAMP          NOT NULL
);

-- 7.1.8 SETTLEMENT : 정산 실행 기록
CREATE TABLE settlement (
    settlement_id VARCHAR2(40) NOT NULL,
    challenge_id  VARCHAR2(40) NOT NULL,
    executed_at   TIMESTAMP    NOT NULL
);

-- 7.1.9 TEAM_PRIZE : 상금풀의 팀 순위 분배 결과 (환수 행은 team_id NULL)
CREATE TABLE team_prize (
    team_prize_id VARCHAR2(40) NOT NULL,
    settlement_id VARCHAR2(40) NOT NULL,
    team_id       VARCHAR2(40),                                    -- 시스템 환수 행은 NULL
    entry_type    VARCHAR2(20) NOT NULL,                           -- TEAM_SHARE / SYSTEM_RECLAIM
    amount        NUMBER(15)   NOT NULL
);

-- 7.1.10 MEMBER_PRIZE : 팀 몫의 팀원 기여도 분배 결과 (환수 행은 participant_id NULL)
CREATE TABLE member_prize (
    member_prize_id VARCHAR2(40)      NOT NULL,
    team_prize_id   VARCHAR2(40)      NOT NULL,
    participant_id  VARCHAR2(50 CHAR),                             -- 나머지 환수 행은 NULL
    entry_type      VARCHAR2(20)      NOT NULL,                    -- MEMBER_SHARE / REMAINDER_RECLAIM
    amount          NUMBER(15)        NOT NULL
);

-- ---------------------------------------------------------------------
-- 조회용 일반 인덱스 (원본과 동일)
-- ---------------------------------------------------------------------
CREATE INDEX ix_team_challenge       ON team (challenge_id);
CREATE INDEX ix_part_team            ON participation (team_id);
CREATE INDEX ix_part_member          ON participation (participant_id);
CREATE INDEX ix_sub_chal_member_date ON submission (challenge_id, participant_id, linked_date);
CREATE INDEX ix_sub_chal_status      ON submission (challenge_id, status);
CREATE INDEX ix_sub_member_date      ON submission (participant_id, linked_date);
CREATE INDEX ix_ldg_member           ON deposit_ledger (participant_id, created_at);
CREATE INDEX ix_ldg_challenge        ON deposit_ledger (challenge_id);

-- ---------------------------------------------------------------------
-- PK·UNIQUE 대체 일반 인덱스 (선택 실행 구간)
-- 원본에서는 PK·UNIQUE 제약이 인덱스를 함께 만든다. 이 변형에는 제약이 없으므로
-- 같은 컬럼에 일반 인덱스를 만들어 조회 성능을 원본과 대등하게 맞춘다.
-- "인덱스 없는 순수 상태"를 계측하려면 이 구간을 건너뛰어라.
-- ---------------------------------------------------------------------
CREATE INDEX ix_challenge_pk      ON challenge (challenge_id);
CREATE INDEX ix_team_pk           ON team (team_id);
CREATE INDEX ix_part_pk           ON participation (participation_id);
CREATE INDEX ix_part_chal_member  ON participation (challenge_id, participant_id);
CREATE INDEX ix_sub_pk            ON submission (submission_id);
CREATE INDEX ix_sub_photo_hash    ON submission (challenge_id, photo_hash);
CREATE INDEX ix_cfm_pk            ON confirmation (confirmation_id);
CREATE INDEX ix_cfm_submission    ON confirmation (submission_id);
CREATE INDEX ix_bal_pk            ON deposit_balance (participant_id);
CREATE INDEX ix_ldg_pk            ON deposit_ledger (entry_id);
CREATE INDEX ix_ldg_idempotency   ON deposit_ledger (idempotency_key);
CREATE INDEX ix_stl_pk            ON settlement (settlement_id);
CREATE INDEX ix_stl_challenge     ON settlement (challenge_id);
CREATE INDEX ix_tpz_pk            ON team_prize (team_prize_id);
CREATE INDEX ix_tpz_settle_team   ON team_prize (settlement_id, team_id);
CREATE INDEX ix_mpz_pk            ON member_prize (member_prize_id);
CREATE INDEX ix_mpz_prize_member  ON member_prize (team_prize_id, participant_id);

-- ---------------------------------------------------------------------
-- 식별자 시퀀스 (원본과 동일 — 애플리케이션이 접두어를 붙여 ID 생성)
-- ---------------------------------------------------------------------
CREATE SEQUENCE seq_challenge      START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE seq_team           START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE seq_participation  START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE seq_submission     START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE seq_confirmation   START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE seq_deposit_ledger START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE seq_settlement     START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE seq_team_prize     START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE seq_member_prize   START WITH 1 INCREMENT BY 1 NOCACHE;
