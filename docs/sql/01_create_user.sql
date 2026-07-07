-- =====================================================================
-- 01_create_user.sql
-- 팀 대항 등수형 챌린지 서비스 전용 Oracle 계정 생성
--
-- 실행 주체: 관리자 계정 (SYS AS SYSDBA 또는 SYSTEM)
-- 실행 예시:
--   sqlplus / as sysdba
--   SQL> @01_create_user.sql
--
-- [주의 1] CDB/PDB 구성(Oracle 12c 이상 기본, XE 21c는 XEPDB1)이면
--          계정은 PDB 안에 만들어야 한다. 접속 후 먼저 실행:
--          ALTER SESSION SET CONTAINER = XEPDB1;  -- PDB 이름은 환경에 맞게 변경
--
-- [주의 2] 비밀번호 플레이스홀더(ChangeMe1234)를 실행 전에 직접 바꿔 넣는다.
--          바꾼 비밀번호는 소스 코드에 넣지 않고 이후 환경 변수/설정으로만 쓴다.
-- =====================================================================

CREATE USER EXERCISEMGMT
    IDENTIFIED BY "ChangeMe1234"
    DEFAULT TABLESPACE USERS
    TEMPORARY TABLESPACE TEMP
    QUOTA UNLIMITED ON USERS;

-- 최소 권한만 부여한다.
GRANT CREATE SESSION  TO EXERCISEMGMT;   -- DB 접속
GRANT CREATE TABLE    TO EXERCISEMGMT;   -- 테이블 생성
GRANT CREATE SEQUENCE TO EXERCISEMGMT;   -- 시퀀스 생성 (단계 3 본 기능 테이블의 식별자용)
