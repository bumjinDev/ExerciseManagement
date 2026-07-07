-- =====================================================================
-- 03_reset_challenge_tables.sql
-- 챌린지 도메인 테이블·시퀀스 전체 삭제 (초기화)
--
-- 용도: 무제약 변형(03_challenge_tables_no_constraints.sql)으로 테스트를 마친 뒤,
--   제약 포함 원본(03_challenge_tables.sql)을 처음부터 재적용하기 전에 실행한다.
--   두 변형 중 어느 쪽이 반영돼 있어도 동작한다(CASCADE CONSTRAINTS).
--
-- [주의] 챌린지 도메인 데이터가 전부 삭제된다(PURGE — 휴지통 미경유).
--   회원 테이블(MEMBERTBL, USERENTITY, USERENTITY_ROLES)은 건드리지 않는다.
--
-- 실행 주체: EXERCISEMGMT 계정
--   sqlplus EXERCISEMGMT/<비밀번호>@localhost:1521/xe
--   SQL> @03_reset_challenge_tables.sql
-- =====================================================================

-- 자식 테이블부터 삭제 (FK가 있는 원본 변형에서도 순서 문제 없음)
DROP TABLE member_prize    CASCADE CONSTRAINTS PURGE;
DROP TABLE team_prize      CASCADE CONSTRAINTS PURGE;
DROP TABLE settlement      CASCADE CONSTRAINTS PURGE;
DROP TABLE confirmation    CASCADE CONSTRAINTS PURGE;
DROP TABLE submission      CASCADE CONSTRAINTS PURGE;
DROP TABLE deposit_ledger  CASCADE CONSTRAINTS PURGE;
DROP TABLE deposit_balance CASCADE CONSTRAINTS PURGE;
DROP TABLE participation   CASCADE CONSTRAINTS PURGE;
DROP TABLE team            CASCADE CONSTRAINTS PURGE;
DROP TABLE challenge       CASCADE CONSTRAINTS PURGE;

DROP SEQUENCE seq_challenge;
DROP SEQUENCE seq_team;
DROP SEQUENCE seq_participation;
DROP SEQUENCE seq_submission;
DROP SEQUENCE seq_confirmation;
DROP SEQUENCE seq_deposit_ledger;
DROP SEQUENCE seq_settlement;
DROP SEQUENCE seq_team_prize;
DROP SEQUENCE seq_member_prize;
