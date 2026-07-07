-- =====================================================================
-- 02_member_tables.sql
-- 회원 관리 테이블 3종: MEMBERTBL(회원), USERENTITY(인증), USERENTITY_ROLES(역할)
-- 구조 근거: docs/3_1. 구현환경.md 3.2 (Wherehouse 엔티티 매핑 기준)
--
-- 실행 주체: EXERCISEMGMT 계정 (01_create_user.sql 반영 후)
-- 실행 예시:
--   sqlplus EXERCISEMGMT/<비밀번호>@localhost:1521/XEPDB1   -- 접속 문자열은 환경에 맞게
--   SQL> @02_member_tables.sql
--
-- 컬럼명은 Wherehouse와 동일하게 Hibernate PhysicalNamingStrategyStandardImpl
-- (엔티티 필드명 그대로 매핑) 기준이다. 예: nickName -> NICKNAME, joinDate -> JOINDATE.
-- 새 프로젝트 application.yaml에도 같은 네이밍 전략을 설정한다(단계 2).
-- 문자 컬럼은 한글 입력을 감안해 CHAR 단위(길이=문자 수)로 선언한다.
-- =====================================================================

-- 회원 테이블 (엔티티: MembersEntity)
CREATE TABLE membertbl (
    id       VARCHAR2(50 CHAR)  NOT NULL,   -- 아이디 (PK)
    pw       VARCHAR2(100 CHAR) NOT NULL,   -- 비밀번호 BCrypt 해시(60자 내외)
    nickName VARCHAR2(50 CHAR)  NOT NULL,   -- 닉네임
    tel      VARCHAR2(20 CHAR),             -- 전화번호
    email    VARCHAR2(100 CHAR),            -- 이메일
    joinDate DATE               NOT NULL,   -- 가입 날짜
    CONSTRAINT pk_membertbl PRIMARY KEY (id),
    CONSTRAINT uq_membertbl_nickname UNIQUE (nickName)  -- 가입 시 닉네임 중복 검사와 일치
);

-- 인증 전용 테이블 (엔티티: AuthenticationEntity)
CREATE TABLE userentity (
    userid   VARCHAR2(50 CHAR)  NOT NULL,   -- 아이디 (PK, membertbl.id와 동일 값)
    username VARCHAR2(50 CHAR)  NOT NULL,   -- 사용자 이름(닉네임)
    password VARCHAR2(100 CHAR) NOT NULL,   -- 비밀번호 BCrypt 해시
    CONSTRAINT pk_userentity PRIMARY KEY (userid),
    CONSTRAINT uq_userentity_username UNIQUE (username)
);

-- 역할 테이블 (@ElementCollection 매핑: userentity_roles)
CREATE TABLE userentity_roles (
    userid VARCHAR2(50 CHAR) NOT NULL,      -- FK -> userentity.userid
    roles  VARCHAR2(50 CHAR) NOT NULL,      -- 역할 문자열. 가입 시 기본 ROLE_USER 1건
    CONSTRAINT fk_roles_userentity FOREIGN KEY (userid) REFERENCES userentity (userid),
    CONSTRAINT uq_roles_userid_roles UNIQUE (userid, roles)
);
