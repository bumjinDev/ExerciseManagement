# 회원 관리 기능 Postman 테스트 명세서

작성일: 2026-07-07
대상: 단계 2 회원 관리·인증 기능 (가입 / 로그인 / 로그인 성공 확인 / 회원 조회 / 정보 수정 / 로그아웃)
컬렉션 파일: `docs/테스트/ExerciseManagement_회원관리.postman_collection.json`

---

## 1. 실행 전제

1. **DB**: 단계 1 SQL 반영 완료 상태 (계정 `EXERCISEMGMT`, 테이블 `MEMBERTBL`/`USERENTITY`/`USERENTITY_ROLES`).
2. **환경 변수**: `JWT_SECRET_KEY`, `DB_PASSWORD` 등록 — `docs/가이드/윈도우_환경변수_설정_가이드.md` 참조.
3. **서버 기동**:
   ```powershell
   cd E:\devSpace\SpringBootProjects\ExerciseManagement
   .\gradlew.bat bootRun
   ```
   `Tomcat started on port 8186 (http) with context path '/exercise'` 로그 확인.
4. **Postman 임포트**: Postman → Import → 위 컬렉션 JSON 파일 선택.
   - 기본 URL은 컬렉션 변수 `baseUrl` = `http://localhost:8186/exercise` 로 설정되어 있다.
   - Postman 기본 설정(리다이렉트 자동 추적, 쿠키 자동 관리)을 그대로 사용한다.

### 인증 방식 요약

- 로그인 성공 시 서버가 `Authorization` 이름의 **HttpOnly 쿠키**로 JWT를 발급한다 (유효 기간: 테스트용 14일).
- Postman이 쿠키를 자동 저장·전송하므로, **05번(로그인) 실행 후에는 별도 헤더 설정 없이** 보호 API가 동작한다.
- 쿠키 확인: 요청 화면 우측 상단 **Cookies** 클릭 → `localhost` 도메인 → `Authorization` 항목.

### 테스트 데이터 초기화 (재실행 시)

컬렉션은 `tester01` 계정을 생성한다. 전체를 처음부터 다시 돌리려면 SQL Developer에서:

```sql
DELETE FROM userentity_roles WHERE userid = 'tester01';
DELETE FROM userentity WHERE userid = 'tester01';
DELETE FROM membertbl WHERE id = 'tester01';
COMMIT;
```

---

## 2. 실행 순서

컬렉션의 01 → 12 순서대로 실행한다(각 요청에 상태 코드 검증 스크립트 포함, Tests 탭에서 통과 여부 확인).
Collection Runner로 전체를 한 번에 실행해도 된다.

| # | 요청 | 기대 결과 |
|---|------|-----------|
| 01 | 회원 가입 (정상) | 200 |
| 02 | 회원 가입 - 아이디 중복 | 409 |
| 03 | 회원 가입 - 예약어 아이디(admin) | 422 |
| 04 | 회원 가입 - 비밀번호 형식 위반 | 400 |
| 05 | 로그인 (정상) | 302→200, Authorization 쿠키 발급 |
| 06 | 로그인 - 비밀번호 오류 | 401 |
| 07 | 로그인 성공 확인 | 200 |
| 08 | 회원 조회 | 200 (pw 미포함) |
| 09 | 정보 수정 (정상) | 200, JWT 쿠키 재발급 |
| 10 | 정보 수정 - 예약어 닉네임 | 422 |
| 11 | 로그아웃 | 200, 쿠키 제거 |
| 12 | 로그아웃 후 보호 API 접근 | 401 |

주의: 06번(로그인 실패)은 401 응답이 기존 `Authorization` 쿠키를 삭제하지 않지만, 05번을 다시 실행하면 새 쿠키로 갱신된다. 09번 실행 후에는 닉네임이 `테스터일수정`으로 바뀐 상태다.

---

## 3. 요청별 상세 명세

공통: 요청·응답 본문은 모두 JSON(UTF-8). 에러 응답 공통 포맷은 `{"code": <HTTP코드>, "status": "<사유구>", "message": "<안내문>"}` 이며, 400 유효성 실패만 `{"필드명": "메시지"}` 형태다.

### 3.1 회원 가입

| 항목 | 내용 |
|------|------|
| Method / URL | `POST {{baseUrl}}/members/join` |
| 인증 | 불필요 (공개) |
| 헤더 | `Content-Type: application/json` |

**요청 바디**

```json
{
  "id": "tester01",
  "pw": "Test1234!",
  "nickName": "테스터일",
  "tel": "010-1234-5678",
  "email": "tester01@example.com"
}
```

필드 규칙: id 4~20자 / pw 8자 이상 + 대문자·소문자·숫자·특수문자 각 1자 이상 / nickName 최대 20자 / tel 010-0000-0000 형식 / email 이메일 형식. 전 필드 필수.

**응답**

| 코드 | 상황 | 본문 |
|------|------|------|
| 200 | 가입 완료 | `{"message": "회원가입이 정상적으로 완료되었습니다."}` |
| 400 | @Valid 형식 위반 | `{"pw": "비밀번호는 ...", ...}` (필드별 메시지) |
| 409 | 아이디 중복 | 공통 에러 포맷 |
| 409 | 닉네임 중복 | 공통 에러 포맷 |
| 422 | 예약어 아이디/닉네임 (admin, root, system 등 10종) | 공통 에러 포맷 |

### 3.2 로그인

| 항목 | 내용 |
|------|------|
| Method / URL | `POST {{baseUrl}}/login` |
| 인증 | 불필요 |
| 헤더 | `Content-Type: application/json` (폼 `application/x-www-form-urlencoded`도 지원) |

**요청 바디**

```json
{ "userid": "tester01", "password": "Test1234!" }
```

**응답**

| 코드 | 상황 | 내용 |
|------|------|------|
| 302 | 인증 성공 | `Set-Cookie: Authorization=<JWT>; HttpOnly` + `Location: /exercise/members/loginSuccess`. Postman 기본 설정이면 자동 추적되어 최종 200(아래 3.3 응답)을 받는다 |
| 401 | 아이디 없음 / 비밀번호 불일치 | `{"code":401, ..., "message":"아이디 또는 비밀번호가 잘못되었습니다."}` |

### 3.3 로그인 성공 확인

| 항목 | 내용 |
|------|------|
| Method / URL | `GET {{baseUrl}}/members/loginSuccess` |
| 인증 | 필요 (Authorization 쿠키) |

**응답**

| 코드 | 상황 | 본문 |
|------|------|------|
| 200 | 유효한 JWT | `{"userId": "tester01", "userName": "테스터일"}` |
| 401 | 쿠키 없음/JWT 무효 | 공통 에러 포맷, 쿠키 제거됨 |

### 3.4 회원 조회 (수정 화면용)

| 항목 | 내용 |
|------|------|
| Method / URL | `GET {{baseUrl}}/members/edit?editid=tester01` |
| 인증 | 필요 |

**응답**

| 코드 | 상황 | 본문 |
|------|------|------|
| 200 | 조회 성공 | `{"id":"tester01","pw":null,"nickName":"테스터일","tel":"...","email":"...","joinDate":"..."}` — 비밀번호 해시는 반환하지 않는다 |
| 401 | 미인증 | 공통 에러 포맷 |
| 404 | 존재하지 않는 editid | 공통 에러 포맷 |

### 3.5 회원 정보 수정

| 항목 | 내용 |
|------|------|
| Method / URL | `POST {{baseUrl}}/members/edit` |
| 인증 | 필요 |
| 헤더 | `Content-Type: application/json` |

**요청 바디**: 가입과 동일 구조(전 필드 필수). `pw`에 넣은 값으로 비밀번호가 재설정된다. 가입일은 서버가 기존 값을 유지한다.

**응답**

| 코드 | 상황 | 내용 |
|------|------|------|
| 200 | 수정 완료 | `{"message":"회원 수정이 정상적으로 완료되었습니다."}` + 닉네임 클레임이 갱신된 새 JWT 쿠키 재발급 |
| 400 | 형식 위반 | 필드별 메시지 |
| 401 | 미인증 (쿠키 누락) | 공통 에러 포맷 |
| 404 | 존재하지 않는 사용자 ID | 공통 에러 포맷 |
| 409 | 닉네임 중복(본인 제외) | 공통 에러 포맷 |
| 422 | 예약어 닉네임 | 공통 에러 포맷 |

### 3.6 로그아웃

| 항목 | 내용 |
|------|------|
| Method / URL | `POST {{baseUrl}}/logout` |
| 인증 | 쿠키가 있으면 제거 (없어도 200) |

**응답**

| 코드 | 상황 | 내용 |
|------|------|------|
| 200 | 로그아웃 완료 | 본문 없음. `Authorization` 쿠키가 Max-Age=0으로 즉시 만료된다 |

---

## 4. DB 반영 확인 (선택)

가입·수정 후 SQL Developer에서 교차 확인:

```sql
SELECT id, nickname, tel, email, joindate FROM membertbl;
SELECT userid, username FROM userentity;
SELECT userid, roles FROM userentity_roles;   -- 가입 시 ROLE_USER 1건
```

회원 테이블과 인증 테이블의 닉네임(username)이 항상 같이 갱신되는지 확인한다.

---

## 5. 비고

- 이 명세의 에러 응답 체계(HTTP 코드 + code/status/message JSON)는 이식된 회원 관리 기능의 것이다. 챌린지 도메인 API(단계 3)부터는 설계 명세서 6.8의 에러 코드 체계(`E-REG-...` 등)를 적용하며, 단계 4에서 해당 API의 Postman 명세서를 별도 작성한다.
- JWT 유효 기간은 테스트 편의를 위해 14일로 설정된 상태다(운영 전 재확정 대상, `application.yaml`의 `jwt.expiration-ms`).
