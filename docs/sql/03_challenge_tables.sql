-- =====================================================================
-- 03_challenge_tables.sql
-- 챌린지 도메인 테이블 10종 + 식별자 시퀀스 + 인덱스
-- 근거: 설계 명세서 v1.4 섹션 7.1 (데이터베이스 스키마)
--
-- 실행 주체: EXERCISEMGMT 계정 (01, 02 반영 완료 상태에서)
--   sqlplus EXERCISEMGMT/<비밀번호>@localhost:1521/xe
--   SQL> @03_challenge_tables.sql
--
-- [구현 확정 사항] 명세서가 "구현 단계에서 확정"으로 미룬 값의 확정 내용:
--   - 식별자 형식: VARCHAR2(40). 애플리케이션이 시퀀스 값에 접두어를 붙여 생성
--     (chal_1, team_1, part_1, sub_1, cfm_1, dep_1, stl_1, tpz_1, mpz_1 — API 예시 형식과 동일 계열)
--   - 시각: TIMESTAMP, 날짜: DATE
--   - 열거값: 영문 상수명 저장 (JSON 한글 값과의 변환은 애플리케이션 열거형이 담당, 명세서 7.2.1)
--   - confirm_window_length: 초 단위 NUMBER (애플리케이션에서 Duration 변환)
--   - 금액: NUMBER(15) 원 단위 정수
--
-- [명세서 7.1.4 대비 추가 컬럼 1건 — 검토 요망]
--   SUBMISSION.photo_path: 인증샷 파일의 저장 경로.
--   팀원 확인(F006)은 인증샷을 보고 판정하므로 사진 파일을 다시 꺼낼 참조가 필요하다.
--   명세서 11.2가 "인증샷 파일 저장 방식"을 구현 단계 확정으로 미뤘고,
--   기준 구현은 로컬 디렉토리 저장 + 경로 보관으로 제안한다. 반대 시 이 컬럼만 빼고 반영해 달라.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 7.1.1 CHALLENGE : 단일 종목 인스턴스의 구성과 상태
-- ---------------------------------------------------------------------
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
    cycle_mode            VARCHAR2(20)       NOT NULL,             -- 인증 주기 방식
    cycle_interval        NUMBER(3)          NOT NULL,             -- 인증 주기 간격
    deposit_amount        NUMBER(15)         NOT NULL,             -- 참가 예치금(신청 완료 시 차감액)
    base_prize_pool       NUMBER(15)         NOT NULL,             -- 운영 기본 상금풀
    status                VARCHAR2(20)       NOT NULL,             -- 챌린지 상태
    CONSTRAINT pk_challenge PRIMARY KEY (challenge_id),
    CONSTRAINT ck_challenge_prize_count  CHECK (prize_team_count < team_count),      -- F001 성립 제약
    CONSTRAINT ck_challenge_daily_cap    CHECK (daily_cap >= 1),                     -- 무제한 불가
    CONSTRAINT ck_challenge_deposit      CHECK (deposit_amount > 0),                 -- 0보다 큰 값
    CONSTRAINT ck_challenge_capacity     CHECK (team_capacity >= 1 AND team_count >= 1 AND cycle_interval >= 1),
    CONSTRAINT ck_challenge_recruit_rng  CHECK (recruit_end > recruit_start),
    CONSTRAINT ck_challenge_perform_rng  CHECK (perform_end > perform_start),
    CONSTRAINT ck_challenge_cycle_mode   CHECK (cycle_mode IN ('EVERY_N_DAYS', 'N_PER_WEEK')),          -- 며칠에 한 번 / 주 며칠
    CONSTRAINT ck_challenge_status       CHECK (status IN ('RECRUITING', 'STARTED', 'ENDED', 'VOID'))   -- 모집/시작/종료/무산
);

-- ---------------------------------------------------------------------
-- 7.1.2 TEAM : 챌린지에 속한 팀
-- ---------------------------------------------------------------------
CREATE TABLE team (
    team_id      VARCHAR2(40) NOT NULL,
    challenge_id VARCHAR2(40) NOT NULL,
    CONSTRAINT pk_team PRIMARY KEY (team_id),
    CONSTRAINT fk_team_challenge FOREIGN KEY (challenge_id) REFERENCES challenge (challenge_id)
);

CREATE INDEX ix_team_challenge ON team (challenge_id);

-- ---------------------------------------------------------------------
-- 7.1.3 PARTICIPATION : 참가 신청, 확정 실력·목표, 배정 팀, 예치 상태
-- ---------------------------------------------------------------------
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
    status                VARCHAR2(20)      NOT NULL,              -- 참가 상태
    CONSTRAINT pk_participation PRIMARY KEY (participation_id),
    CONSTRAINT fk_part_challenge   FOREIGN KEY (challenge_id)   REFERENCES challenge (challenge_id),
    CONSTRAINT fk_part_member      FOREIGN KEY (participant_id) REFERENCES membertbl (id),
    CONSTRAINT fk_part_team        FOREIGN KEY (team_id)        REFERENCES team (team_id),
    CONSTRAINT uq_part_chal_member UNIQUE (challenge_id, participant_id),            -- 같은 챌린지 중복 신청 차단 (7.1.3)
    CONSTRAINT ck_part_coefficient CHECK (intensity_coefficient >= 1),
    CONSTRAINT ck_part_cycle_mode  CHECK (goal_cycle_mode IN ('EVERY_N_DAYS', 'N_PER_WEEK')),
    CONSTRAINT ck_part_status      CHECK (status IN ('ACTIVE', 'WITHDRAWN'))         -- 진행 중 / 이탈
);

CREATE INDEX ix_part_team   ON participation (team_id);
CREATE INDEX ix_part_member ON participation (participant_id);

-- ---------------------------------------------------------------------
-- 7.1.4 SUBMISSION : 인증 제출과 상태 전이 (기준 측정 행 포함, is_baseline으로 구분)
-- ---------------------------------------------------------------------
CREATE TABLE submission (
    submission_id  VARCHAR2(40)      NOT NULL,
    challenge_id   VARCHAR2(40)      NOT NULL,
    participant_id VARCHAR2(50 CHAR) NOT NULL,                     -- 제출자
    weight         NUMBER(8,2)       NOT NULL,                     -- 무게
    reps           NUMBER(5)         NOT NULL,                     -- 횟수
    volume         NUMBER(15,4)      NOT NULL,                     -- 무게 × 횟수
    photo_hash     VARCHAR2(128)     NOT NULL,                     -- 사진 해시(중복 검사 대상)
    photo_path     VARCHAR2(300 CHAR),                             -- [추가 제안] 인증샷 저장 경로(파일 헤더 주석 참조)
    registered_at  TIMESTAMP         NOT NULL,                     -- 등록 시점
    linked_date    DATE              NOT NULL,                     -- 수행 날짜(하루 상한·주기·30일 집계 기준)
    status         VARCHAR2(20)      NOT NULL,                     -- 제출 상태
    is_baseline    NUMBER(1)         NOT NULL,                     -- 1: 기준 측정 행, 0: 일반 인증 행
    CONSTRAINT pk_submission PRIMARY KEY (submission_id),
    CONSTRAINT fk_sub_challenge FOREIGN KEY (challenge_id)   REFERENCES challenge (challenge_id),
    CONSTRAINT fk_sub_member    FOREIGN KEY (participant_id) REFERENCES membertbl (id),
    CONSTRAINT uq_sub_photo_hash UNIQUE (challenge_id, photo_hash),                  -- 해시 중복 2차 방어 (8.2.1)
    CONSTRAINT ck_sub_status     CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'EXPIRED')),  -- 확인 대기/확인 완료/반려/만료
    CONSTRAINT ck_sub_baseline   CHECK (is_baseline IN (0, 1)),
    CONSTRAINT ck_sub_values     CHECK (weight > 0 AND reps >= 1)
);

CREATE INDEX ix_sub_chal_member_date ON submission (challenge_id, participant_id, linked_date);  -- 하루 상한·주기 판정
CREATE INDEX ix_sub_chal_status      ON submission (challenge_id, status);                       -- 만료 처리(B-04)·확인 큐
CREATE INDEX ix_sub_member_date      ON submission (participant_id, linked_date);                -- 최근 30일 집계(F003)

-- ---------------------------------------------------------------------
-- 7.1.5 CONFIRMATION : 팀원 확인·반려 기록 (append 전용)
-- ---------------------------------------------------------------------
CREATE TABLE confirmation (
    confirmation_id VARCHAR2(40)      NOT NULL,
    submission_id   VARCHAR2(40)      NOT NULL,
    confirmer_id    VARCHAR2(50 CHAR) NOT NULL,                    -- 확인자(팀원)
    decision        VARCHAR2(10)      NOT NULL,                    -- 확인 / 반려
    confirmed_at    TIMESTAMP         NOT NULL,
    CONSTRAINT pk_confirmation PRIMARY KEY (confirmation_id),
    CONSTRAINT fk_cfm_submission FOREIGN KEY (submission_id) REFERENCES submission (submission_id),
    CONSTRAINT fk_cfm_member     FOREIGN KEY (confirmer_id)  REFERENCES membertbl (id),
    CONSTRAINT uq_cfm_submission UNIQUE (submission_id),           -- 정족수 1: 이중 확인·이중 가산 저장 단계 차단 (8.2.2)
    CONSTRAINT ck_cfm_decision   CHECK (decision IN ('CONFIRM', 'REJECT'))
);

-- ---------------------------------------------------------------------
-- 7.1.6 DEPOSIT_BALANCE : 예치 잔액 현재 값 (원본은 원장)
-- ---------------------------------------------------------------------
CREATE TABLE deposit_balance (
    participant_id VARCHAR2(50 CHAR) NOT NULL,
    balance        NUMBER(15)        NOT NULL,
    CONSTRAINT pk_deposit_balance PRIMARY KEY (participant_id),
    CONSTRAINT fk_bal_member FOREIGN KEY (participant_id) REFERENCES membertbl (id),
    CONSTRAINT ck_bal_non_negative CHECK (balance >= 0)            -- 잔액 부족 차감 차단(E-APP-INSUFFICIENT-BALANCE)의 최종 방어선
);

-- ---------------------------------------------------------------------
-- 7.1.7 DEPOSIT_LEDGER : 예치금 이동 원장 (append 전용, 정정은 반대 기록 추가)
-- ---------------------------------------------------------------------
CREATE TABLE deposit_ledger (
    entry_id        VARCHAR2(40)       NOT NULL,
    participant_id  VARCHAR2(50 CHAR)  NOT NULL,
    challenge_id    VARCHAR2(40),                                  -- 관련 챌린지(충전은 NULL)
    entry_type      VARCHAR2(30)       NOT NULL,                   -- 이동 유형
    amount          NUMBER(15)         NOT NULL,                   -- 이동 금액(부호 포함)
    idempotency_key VARCHAR2(100 CHAR) NOT NULL,                   -- 같은 이동의 중복 기록 차단 키
    created_at      TIMESTAMP          NOT NULL,
    CONSTRAINT pk_deposit_ledger PRIMARY KEY (entry_id),
    CONSTRAINT fk_ldg_member    FOREIGN KEY (participant_id) REFERENCES membertbl (id),
    CONSTRAINT fk_ldg_challenge FOREIGN KEY (challenge_id)   REFERENCES challenge (challenge_id),
    CONSTRAINT uq_ldg_idempotency UNIQUE (idempotency_key),        -- 정산 재실행에도 같은 이동은 한 번만 (8.4.3)
    CONSTRAINT ck_ldg_entry_type CHECK (entry_type IN
        ('CHARGE', 'JOIN_DEBIT', 'REFUND', 'FORFEIT', 'VOID_RETURN'))  -- 충전/참가 차감/환급/차감/무산 반환
);

CREATE INDEX ix_ldg_member ON deposit_ledger (participant_id, created_at);
CREATE INDEX ix_ldg_challenge ON deposit_ledger (challenge_id);

-- ---------------------------------------------------------------------
-- 7.1.8 SETTLEMENT : 정산 실행 기록 (챌린지당 1건 — 단일 실행 보장)
-- ---------------------------------------------------------------------
CREATE TABLE settlement (
    settlement_id VARCHAR2(40) NOT NULL,
    challenge_id  VARCHAR2(40) NOT NULL,
    executed_at   TIMESTAMP    NOT NULL,
    CONSTRAINT pk_settlement PRIMARY KEY (settlement_id),
    CONSTRAINT fk_stl_challenge FOREIGN KEY (challenge_id) REFERENCES challenge (challenge_id),
    CONSTRAINT uq_stl_challenge UNIQUE (challenge_id)              -- 정산 중복 실행 저장 단계 차단 (8.4.2)
);

-- ---------------------------------------------------------------------
-- 7.1.9 TEAM_PRIZE : 상금풀의 팀 순위 분배 결과 (환수 행은 team_id NULL)
-- ---------------------------------------------------------------------
CREATE TABLE team_prize (
    team_prize_id VARCHAR2(40) NOT NULL,
    settlement_id VARCHAR2(40) NOT NULL,
    team_id       VARCHAR2(40),                                    -- 시스템 환수 행은 NULL
    entry_type    VARCHAR2(20) NOT NULL,                           -- 팀 몫 / 시스템 환수
    amount        NUMBER(15)   NOT NULL,
    CONSTRAINT pk_team_prize PRIMARY KEY (team_prize_id),
    CONSTRAINT fk_tpz_settlement FOREIGN KEY (settlement_id) REFERENCES settlement (settlement_id),
    CONSTRAINT fk_tpz_team       FOREIGN KEY (team_id)       REFERENCES team (team_id),
    CONSTRAINT uq_tpz_settlement_team UNIQUE (settlement_id, team_id),   -- 같은 정산·같은 팀 이중 분배 차단 (7.1.9)
    CONSTRAINT ck_tpz_entry_type CHECK (entry_type IN ('TEAM_SHARE', 'SYSTEM_RECLAIM'))
);

-- ---------------------------------------------------------------------
-- 7.1.10 MEMBER_PRIZE : 팀 몫의 팀원 기여도 분배 결과 (환수 행은 participant_id NULL)
-- ---------------------------------------------------------------------
CREATE TABLE member_prize (
    member_prize_id VARCHAR2(40)      NOT NULL,
    team_prize_id   VARCHAR2(40)      NOT NULL,
    participant_id  VARCHAR2(50 CHAR),                             -- 나머지 환수 행은 NULL
    entry_type      VARCHAR2(20)      NOT NULL,                    -- 팀원 분배 / 나머지 환수
    amount          NUMBER(15)        NOT NULL,
    CONSTRAINT pk_member_prize PRIMARY KEY (member_prize_id),
    CONSTRAINT fk_mpz_team_prize FOREIGN KEY (team_prize_id)  REFERENCES team_prize (team_prize_id),
    CONSTRAINT fk_mpz_member     FOREIGN KEY (participant_id) REFERENCES membertbl (id),
    CONSTRAINT uq_mpz_prize_member UNIQUE (team_prize_id, participant_id),  -- 같은 팀 몫 이중 수령 차단 (7.1.10)
    CONSTRAINT ck_mpz_entry_type CHECK (entry_type IN ('MEMBER_SHARE', 'REMAINDER_RECLAIM'))
);

-- ---------------------------------------------------------------------
-- 식별자 시퀀스 (애플리케이션이 접두어를 붙여 ID 생성)
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
