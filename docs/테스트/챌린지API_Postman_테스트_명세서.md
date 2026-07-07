# 챌린지 API Postman 테스트 명세서 (단계 4)

작성일: 2026-07-07
대상: 단계 3 챌린지 도메인 API 전체 (설계 명세서 6장 + 신설 API 2종)
컬렉션 파일: `docs/테스트/ExerciseManagement_챌린지API.postman_collection.json`
에러 코드: 설계 명세서 6.8 체계와 일치. 신설 코드는 `docs/설계/명세서_대비_구현_변경사항.md` 3-3 참조.

---

## 1. 실행 전제

1. **DB**: sql 01~04 반영 완료 (현재 무제약 변형 03 + 04).
2. **서버 기동**: 환경 변수 `JWT_SECRET_KEY`·`DB_PASSWORD` 설정 후 `.\gradlew.bat bootRun`
   (가이드: `docs/가이드/윈도우_환경변수_설정_가이드.md`).
3. **준비물 — 서로 다른 이미지 6장**: 사진1~사진6으로 부른다.
   - **스크린샷 파일 권장.** EXIF 촬영 시각이 있는 사진은 수행 날짜와 ±1일 정합해야 하므로(기계 검증 3번 항목),
     오래된 사진을 쓰면 `E-SUB-META-MISMATCH`(422)가 난다. 스크린샷·그림판 저장 파일은 EXIF가 없어 통과한다.
   - Postman은 파일 선택을 컬렉션에 저장하지 않는다. **[사진N 선택]이 붙은 요청은 Body > form-data의 photo 파트에서 파일을 직접 선택**하고 실행한다.
4. **컬렉션 임포트** 후 폴더 00부터 순서대로 실행한다. Postman 기본 설정(쿠키 자동 관리, 리다이렉트 자동 추적) 사용.
5. 사용자 전환은 로그인 요청이 `Authorization` 쿠키를 갈아끼우는 방식이다. **요청 순서를 건너뛰면 다른 사용자로 실행될 수 있으니 주의.**

### 테스트 계정과 시나리오

| 계정 | 역할 | 기준 측정 | 예상 팀 |
|------|------|-----------|---------|
| chal01 | 챌린지 등록자 + 참가자, 인증 제출자 | 100kg×10 (볼륨 1000) | A팀 |
| chal02 | 참가자 | 100kg×10 (1000) | B팀 |
| chal03 | 참가자 | 50kg×10 (500) | B팀 |
| chal04 | 참가자 (잔액 부족 케이스 겸용), 확인자 | 50kg×10 (500) | A팀 |
| chal05 | 비참가자(403 케이스) + 무산 챌린지 등록자 | — | — |

챌린지 구성: 하체/스쿼트, 2팀×2명(목표 4명), 상금 1팀, 예치금 30,000원, 하루 상한 1회, 주기 주 7일, 확인 윈도우 P1D.

**예상 편성 근거**: 편성 엔진은 결정적이다(8.1.4). 실력 [1000, 1000, 500, 500] 구성에서 교대 방향 배정이
{chal01, chal04}, {chal02, chal03}을 만들고 합 편차 0·분포 편차 0이라 그대로 채택된다.
만약 팀 구성이 예상과 다르면 폴더 05의 "확인 대기 목록 조회"가 비어 있게 되므로,
그때는 chal03으로 로그인해 같은 폴더를 다시 실행하면 된다(목록에 chal01 제출이 보이는 계정이 팀원이다).

---

## 2. 실행 순서 (폴더별)

| 폴더 | 내용 | 중간 개입 |
|------|------|-----------|
| 00 | 테스트 계정 5개 가입 (재실행 시 409 허용) | — |
| 01 | chal01 로그인 → 충전 → **챌린지 등록(201)** + 등록 오류 2종(400) | — |
| 02 | 공개 조회: 목록/상세(200, 비로그인도 가능), 미존재(404) | — |
| 03 | 4명 신청: 각자 로그인→충전→신청(202)→기준 측정(201). 중복 신청(409), 잔액 부족(402) 포함. **4번째 완료 시 편성 실행**(teamAssigned=true, 상세 조회 status=시작) | 사진1~4 |
| 04 | chal01 인증 제출(202) + 해시 중복(409)·미래 날짜(400)·하루 상한(429) | 사진5, 6 |
| 05 | 팀원 확인: 다른 팀(403), 정상 확인(200, 볼륨 1000 반영), 재확인(409), 본인 확인(403) | — |
| 06 | 순위·현황(200, 네 블록), 내 제출 현황(200), 비참가자(403) | — |
| 07 | **아래 4.2 SQL 실행 → 60초 대기** → 상태 종료, 예치 환급, 종료 후 순위 조회(200), 종료 후 확인(409) | SQL |
| 08 | 무산: chal05 등록→신청→기준 측정 → **4.3 SQL 실행 → 60초 대기** → 상태 무산, 무산 반환 | SQL |

스케줄러 폴링 주기가 60초이므로 SQL 실행 후 **최소 60초** 기다린 뒤 다음 요청을 실행한다.

---

## 3. 예상 정산 결과 (폴더 07 검증 기준)

- 개인 판정: chal01만 달성(강도 하한 1000 이상 확인 완료 1건 ≥ 목표 1회). chal02·03·04 미달.
- 상금풀 = 기본 500,000 + 차감분 30,000×3 = **590,000원**
- 팀 순위: A팀(볼륨 1000) 1위 → 팀 몫 590,000. B팀(0) 상금 없음.
- 팀 내 분배: chal01 590,000 (기여 100%), chal04 0, 나머지 환수 0.
- 예치: chal01 잔액 50,000(차감 30,000 + 환급 30,000), chal02~04 잔액 20,000.

DB 교차 검산 (SQL Developer):

```sql
SELECT * FROM settlement;
SELECT entry_type, amount FROM team_prize;      -- TEAM_SHARE 590000 + SYSTEM_RECLAIM 0 = 상금풀
SELECT entry_type, amount FROM member_prize;    -- MEMBER_SHARE 합 + REMAINDER_RECLAIM = 팀 몫
SELECT participant_id, entry_type, amount FROM deposit_ledger ORDER BY created_at;
SELECT participant_id, balance FROM deposit_balance;
```

---

## 4. 테스트 데이터 SQL

### 4.1 초기화 (컬렉션 재실행 전)

챌린지 도메인 데이터만 비운다(회원 계정은 유지 — 00 폴더의 가입은 409로 통과).

```sql
DELETE FROM member_prize;
DELETE FROM team_prize;
DELETE FROM settlement;
DELETE FROM confirmation;
DELETE FROM submission;
DELETE FROM deposit_ledger;
DELETE FROM deposit_balance;
DELETE FROM pending_application;
DELETE FROM participation;
DELETE FROM team;
DELETE FROM challenge;
COMMIT;
```

### 4.2 정산 트리거 — 폴더 06 완료 후 실행

수행 마감을 25시간 전으로 옮기고 확인 윈도우를 60초로 줄인다.
(윈도우 종료 = perform_end + 24h + 60초 → 이미 지난 시각이 되어 다음 스케줄 틱에 만료 처리·정산 실행)

```sql
UPDATE challenge
   SET perform_end = SYSTIMESTAMP - INTERVAL '25' HOUR,
       confirm_window_length = 60
 WHERE challenge_id = '<폴더 01에서 만든 challengeId>';   -- 예: chal_1
COMMIT;
```

### 4.3 무산 트리거 — 폴더 08의 기준 측정까지 완료 후 실행

```sql
UPDATE challenge
   SET recruit_end = SYSTIMESTAMP - INTERVAL '1' MINUTE
 WHERE challenge_id = '<폴더 08에서 만든 voidChallengeId>';   -- 예: chal_2
COMMIT;
```

challengeId 값은 Postman 컬렉션 변수(컬렉션 우클릭 → Edit → Variables) 또는 등록 응답에서 확인한다.

---

## 5. 엔드포인트별 명세

공통: 응답은 JSON(UTF-8). 실패 응답은 `{"errorCode": "...", "message": "..."}` (명세 6.8).
인증은 로그인 시 발급되는 `Authorization` HttpOnly 쿠키로 하며 Postman이 자동 전송한다.
(신설)으로 표시된 에러 코드·API는 명세에 없어 구현 중 추가한 것이다.

### 5.1 챌린지 등록 — `POST /api/challenges` (인증 필요)

요청 바디: category, exercise(닫힌 목록), teamCapacity, teamCount, prizeTeamCount, depositAmount,
recruitPeriod{start,end}, performPeriod{start,end}, confirmWindowLength(ISO-8601, "P1D"), dailyCap, cycleMode("며칠에 한 번"|"주 며칠"), cycleInterval

| 코드 | 상황 |
|------|------|
| 201 | 등록 완료 — challengeId, targetParticipants, status="모집" |
| 400 E-REG-PRIZE-COUNT | 상금 받을 팀 수 ≥ 팀 수 |
| 400 E-REG-NO-DAILY-CAP | 하루 상한 미지정 |
| 400 E-REG-UNKNOWN-EXERCISE | 목록 밖 카테고리·종목 |
| 401 | 미인증 |

### 5.2 챌린지 목록·상세 — `GET /api/challenges[?status=모집]`, `GET /api/challenges/{id}` (공개)

| 코드 | 상황 |
|------|------|
| 200 | 목록(빈 결과는 빈 배열) / 상세(공개 구성 + 모집 현황) |
| 404 E-CHL-NOT-FOUND | 미존재 챌린지 (상세) |

### 5.3 참가 신청 — `POST /api/challenges/{id}/participations` (인증 필요)

요청 바디: intensityCoefficient(선택, ≥1), goalCycleMode, goalCycleInterval

| 코드 | 상황 |
|------|------|
| 201 | 최근 30일 기록 있음 — 즉시 완료(예치 차감), formationSkill·intensityFloor·teamAssigned 반환 |
| 202 | 기록 없음 — `{"status": "기준 측정 필요"}`, 개인 목표는 보관됨 |
| 409 E-APP-NOT-RECRUITING | 모집 기간 밖 / 모집 상태 아님 |
| 400 E-APP-COEF-BELOW-ONE | 강도 계수 1 미만 |
| 400 E-APP-FREQ-OVER-CAP | 목표 빈도가 상한 위반('주 며칠' 간격 > 7) |
| 409 E-APP-ALREADY-APPLIED (신설) | 같은 챌린지 중복 신청 |

### 5.4 기준 측정 제출 — `POST /api/challenges/{id}/participations/baseline-measurement` (인증 필요, multipart)

파트: `meta`(application/json: weight, reps) + `photo`(이미지 파일)

| 코드 | 상황 |
|------|------|
| 201 | 검증 통과 — 1회 볼륨으로 기준값 확정, 예치 차감, 신청 완료. 마지막 인원이면 편성 실행(teamAssigned=true) |
| 409 E-APP-NO-PENDING-MEASUREMENT | 기준 측정을 요청받은 상태가 아님 |
| 402 E-APP-INSUFFICIENT-BALANCE | 예치 잔액 부족 (실패 시 전체 롤백 — 같은 사진으로 재시도 가능) |
| 409 E-SUB-DUP-HASH / 422 E-SUB-META-MISMATCH | 기계 검증 실패 |

### 5.5 인증 제출 — `POST /api/challenges/{id}/submissions` (인증 필요, multipart)

파트: `meta`(weight, reps, performedDate) + `photo`. 검증 순서: 마감 → 해시 → 메타데이터 → 소급 유예 → 시간 윈도우.

| 코드 | 상황 |
|------|------|
| 202 | 확인 대기 진입 — submissionId, linkedDate, registeredAt |
| 409 E-SUB-AFTER-DEADLINE | 제출 완전 마감(수행 마감+24h) 이후 |
| 409 E-SUB-BEFORE-START (신설) | 수행 시작 전 |
| 409 E-SUB-DUP-HASH | 같은 챌린지 안 같은 사진 재사용 |
| 422 E-SUB-META-MISMATCH | 이미지 아님 / EXIF 촬영시각이 수행 날짜와 부정합 |
| 400 E-SUB-BACKDATE-EXCEEDED | 미래 날짜 또는 어제 이전 날짜 |
| 400 E-SUB-DATE-OUT-OF-PERIOD (신설) | 수행 기간 밖 날짜 |
| 429 E-SUB-FREQ-VIOLATION | 하루 상한 초과 또는 주기 위반 |
| 403 E-SUB-NOT-PARTICIPANT | 참가자 아님 |

### 5.6 팀원 확인 — `POST /api/submissions/{submissionId}/confirmations` (인증 필요)

요청 바디: `{"decision": "확인"}` 또는 `{"decision": "반려"}`

| 코드 | 상황 |
|------|------|
| 200 | 처리 완료 — resultStatus("확인 완료"/"반려"), volumeApplied, appliedVolume |
| 403 E-CFM-NOT-TEAMMATE | 같은 팀 아님 |
| 403 E-CFM-SELF-CONFIRM | 본인 제출 |
| 409 E-CFM-ALREADY-TERMINAL | 이미 종착 상태(확인 완료·반려·만료) |
| 409 E-CFM-WINDOW-CLOSED (신설) | 확인 윈도우 종료 후 |
| 404 E-SUB-NOT-FOUND (신설) | 미존재 제출 |

### 5.7 확인 대기 목록 — `GET /api/challenges/{id}/submissions/pending` (인증 필요, 신설)

요청자 팀의 확인 대기 제출(본인 제외) 반환. 편성 전이면 빈 목록. 참가자 아니면 403 E-SUB-NOT-PARTICIPANT.

### 5.8 내 제출 현황 — `GET /api/challenges/{id}/submissions/me[?date=YYYY-MM-DD]` (인증 필요)

수행 날짜 내림차순 그룹(`submissionsByDate`), 기준 측정 행 제외. 참가자 아니면 403 E-SUB-NOT-PARTICIPANT.

### 5.9 순위·현황 — `GET /api/challenges/{id}/rankings` (인증 필요)

네 블록 반환: teamRankings / myTeam(inPrizeBoundary) / teamContributions(공동 순위) / myGoal(targetCount·achievedCount·achieved).
시작·종료 상태에서만 허용.

| 코드 | 상황 |
|------|------|
| 403 E-RNK-NOT-PARTICIPANT | 참가자 아님 |
| 409 E-RNK-NOT-STARTED | 편성 전(모집) 또는 무산 |

### 5.10 예치 — `POST /api/deposits`, `GET /api/deposits/me` (인증 필요, 신설)

- 충전: `{"amount": 50000}` → 200 `{"balance": ...}`. 0 이하는 400 E-DEP-INVALID-AMOUNT.
- 현황: 200 `{"balance": ..., "challengeDeposits": [{challengeId, amount, state}]}` — state: 진행 중/환급/차감/무산 반환.

---

## 6. 유의사항

- **무제약 스키마 전제**: 현재 DB는 테스트용 무제약 변형이라 저장 단계 유니크 방어가 없다. 컬렉션의 중복·이중 처리 케이스는 애플리케이션 검증을 확인하는 것이다.
- 거부된 제출(400/409/422/429)은 DB에 저장되지 않으므로 그 사진은 다른 요청에 재사용할 수 있다(컬렉션의 사진6 재사용이 이 원리).
- 같은 사진이라도 **다른 챌린지**에는 제출 가능하다(해시 유니크는 챌린지 단위).
- 정산·무산은 스케줄러(60초 주기)가 실행하므로 SQL 후 대기가 필요하다. 서버 콘솔 로그에서 "정산 완료", "무산 처리" 메시지로도 확인할 수 있다.
- 전체 재실행: 4.1 초기화 SQL → 폴더 00부터. (00의 가입은 409로 통과된다.)
