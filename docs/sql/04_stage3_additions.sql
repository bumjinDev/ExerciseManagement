-- =====================================================================
-- 04_stage3_additions.sql
-- 단계 3 구현 중 판단으로 추가된 스키마 (근거: docs/설계/명세서_대비_구현_변경사항.md)
--
-- 실행 주체: EXERCISEMGMT 계정. 03 변형(제약 유무 무관) 반영 후 실행한다.
--   03_reset 후 재적용 시 순서: 03(원하는 변형) → 04
--
-- 추가 1) CHALLENGE.creator_id : 챌린지 등록자 추적 (명세서 7.1.1에 없음)
-- 추가 2) PENDING_APPLICATION : 기준 측정 대기 중 참가 신청 보관
--   명세서 6.2.1이 "입력받은 개인 목표는 보관해 둔다"라고만 정하고 저장 위치를
--   정하지 않았다. PARTICIPATION은 편성 실력 확정 후에만 생길 수 있으므로
--   (formation_skill NOT NULL), 확정 전 신청 값을 이 표에 보관한다.
--   기준 측정 통과 시 PARTICIPATION으로 옮기고 이 행은 삭제한다.
-- =====================================================================

ALTER TABLE challenge ADD creator_id VARCHAR2(50 CHAR);   -- 등록 요청자(membertbl.id). 기존 행은 NULL 허용

CREATE TABLE pending_application (
    pending_id            VARCHAR2(40)      NOT NULL,
    challenge_id          VARCHAR2(40)      NOT NULL,
    participant_id        VARCHAR2(50 CHAR) NOT NULL,
    intensity_coefficient NUMBER(6,3)       NOT NULL,     -- 보관해 둔 강도 계수
    goal_cycle_mode       VARCHAR2(20)      NOT NULL,     -- 보관해 둔 목표 빈도 방식
    goal_cycle_interval   NUMBER(3)         NOT NULL,     -- 보관해 둔 목표 빈도 간격
    created_at            TIMESTAMP         NOT NULL
);

CREATE INDEX ix_pnd_chal_member ON pending_application (challenge_id, participant_id);

CREATE SEQUENCE seq_pending_application START WITH 1 INCREMENT BY 1 NOCACHE;

-- ---------------------------------------------------------------------
-- [선택 구간] 제약 포함 원본(03_challenge_tables.sql) 위에 적용할 때만 실행
-- 무제약 변형 위에서는 이 구간을 건너뛴다.
-- ---------------------------------------------------------------------
-- ALTER TABLE challenge ADD CONSTRAINT fk_chal_creator FOREIGN KEY (creator_id) REFERENCES membertbl (id);
-- ALTER TABLE pending_application ADD CONSTRAINT pk_pending_application PRIMARY KEY (pending_id);
-- ALTER TABLE pending_application ADD CONSTRAINT fk_pnd_challenge FOREIGN KEY (challenge_id) REFERENCES challenge (challenge_id);
-- ALTER TABLE pending_application ADD CONSTRAINT fk_pnd_member FOREIGN KEY (participant_id) REFERENCES membertbl (id);
-- ALTER TABLE pending_application ADD CONSTRAINT uq_pnd_chal_member UNIQUE (challenge_id, participant_id);
