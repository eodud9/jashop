# 07. StoreFlow 개발 계획 · 테스트 전략 · Codex 작업 지침

**기준일:** 2026-11-05 · **실제 상태:** 요구사항·논리 ERD·API 설계 완료, Spring Boot/React 프로젝트 생성. MySQL/Redis 실행, 전체 비즈니스 로직, 배포가 이미 완료되었다고 가정하지 않는다.

## 1. 개발 원칙

1. **하나의 기능을 API/DB/테스트까지 수직으로 완성**한 뒤 다음 기능으로 이동한다. 17개 엔티티를 동시에 만들어 두고 테스트를 나중에 몰아서 하지 않는다.
2. README/docs의 확정 정책을 읽고 작은 작업 단위로 Codex에 지시한다. Codex는 구현·테스트를 돕고 개발자는 **설계·검증·최종 선택**을 담당한다.
3. 기술이 필요해지는 시점에 도입한다. Redis는 최근 조회, Web Push는 알림 구현 단계에서. Kafka는 현재 불필요.
4. 단위 테스트로 모든 것을 증명하려 하지 않는다. UNIQUE, InnoDB 행 잠금, 트랜잭션 경계는 **Testcontainers 실제 MySQL**로 확인한다.
5. 코드 변경 시 요구사항·ERD·API·ADR과 테스트를 함께 업데이트한다.
6. 완료되지 않은 기능을 README에서 '구현 완료'로 표시하지 않는다.

## 2. 전체 구현 로드맵

| 단계 | 핵심 작업 | 해당 문서 | 완료 조건 |
|---|---|---|---|
| 0. 개발 환경 | Docker Compose MySQL·Redis, `.env.example`, `application-local.yml`, Gradle/Java 버전 확인 | 05 | Spring Boot 실행, DB·Redis 연결 확인, 민감 정보 Git 제외 |
| 1. 공통 기반 | 패키지, 시간/Clock, ErrorCode, GlobalExceptionHandler, 요청 DTO 검증, Flyway 기본 생성 | 03/05 | 예외 응답·로그·DB 마이그레이션 재현 가능 |
| 2. Store · User | 매장/사용자 JPA·Repository, 가입 대기와 관리자 승인, 리소스 범위 서비스 | 01/02/04 | 역할·상태·소속 검증 및 권한 거부 테스트 |
| 3. AuthSession · JWT | Spring Security, 로그인/Refresh 회전/Logout, 토큰 상태 검사, 쿠키 보안 | 04/05 | 미승인 차단, 세션 폐기/회전/경쟁 테스트 |
| 4. WorkSchedule | nullable endAt, 서울 기준 workDate, 일정 생성·취소·중복 방지 | 02/03/04 | 경기일/비경기일 생성, 동시 중복 일정 방지 |
| 5. Attendance · Notice | 체크인/아웃 원본, 30분 단위 기록 승인, 다중 퇴근 안내 | 02/04 | 시간 분리·지원 중 퇴근 금지·권한 테스트 |
| 6. Product · StorageLocation | 공통 카탈로그/SKU, 내부/공용 창고 구조, 조회 DTO | 02/03/04 | 외부창고/143 단일 위치, 옵션 검색 |
| 7. StockCheck | 요청 생성·수락, 이력/현재값 원자 완료, 에스컬레이션 | 02/04 | 동시 수락 1명, 0/null, 완료 롤백 테스트 |
| 8. Redis 최근 조회 | 상세 조회 후 LIST 원자 갱신·TTL·장애 격리 | 05 | 10개/중복 제거/장애 시 검색 정상 |
| 9. Support | 지원 요청·승인·배정, 도착·복귀·현재 근무 위치 | 02/04 | 동일 직원 중복 활성 배정 불가, 정원·위치 상태 테스트 |
| 10. 알림 DB | Notification/Subscription/Delivery 저장·읽음, 수신자 계산 | 02/04/05 | 매장별 정확한 알림 생성, 중복 이벤트 대응 |
| 11. SSE · Web Push | 푸시 구독·Worker·재시도·SSE, React 알림함 | 04/05 | 외부 푸시 장애 격리, 실기기 Push/SSE 검증 |
| 12. PWA UI | 로그인, 내 일정, 상품/위치, 확인 요청, 지원, 알림 | 01/04 | 모바일 화면에서 주요 시나리오 E2E 성공 |
| 13. 배포·고도화 | CI, Docker 이미지, AWS, HTTPS, 관측·문서 정비 | 05/06 | 실제 배포 URL·테스트·설계 선택 근거 증빙 |

개발 단계 7과 9는 공통 `CurrentWorkStatusService` 및 알림 저장 인터페이스에 의존한다. **이 서비스의 계약은 앞 단계에서 정의하되 실제 구현은 필요한 만큼 점진적으로 확장**한다. 인력 지원으로 현재 근무 매장이 달라지는 기능은 지원 도메인 통합 시 재고/알림에 회귀 테스트를 추가한다.

## 3. 단계별 초기 작업의 상세

### 단계 0 — 인프라 준비

- [ ] 현재 `storeflow-backend/build.gradle`의 Java21/Spring Boot 4.1.2 확인.
- [ ] `gradle-wrapper.properties`가 Spring Boot 4.1.2와 호환되는 Gradle 버전인지 확인(Gradle 8.x라면 8.14 이상).
- [ ] 루트 `.gitignore` 추가, `.DS_Store`와 `.idea/` Git 추적 해제.
- [ ] 루트 `compose.yaml`에 MySQL 8.4/Redis, localhost 포트 바인딩, 영속 볼륨.
- [ ] 루트 `.env.example`에 환경 변수 이름·안전한 샘플을 기록, `.env`는 Git 제외.
- [ ] `storeflow-backend/src/main/resources/application-local.yml`에 DataSource/Redis 연결 설정.
- [ ] `docker compose up -d`, `docker compose ps`, `cd storeflow-backend && ./gradlew test`.
- [ ] Spring Boot 실행 확인(기본 Spring Security의 인증 요구는 DB 연결 실패와 구분).

**중요:** DataSource `url` 설정이 누락되면 MySQL Driver 의존성이 있어도 실행 실패한다. 외부 의존성을 무작정 제거하지 않고 실제 설정·프로파일·포트를 먼저 확인.

### 단계 1 — 공통 계층

- [ ] `ApiError(code,message,timestamp,traceId,fieldErrors)` 합의.
- [ ] `BusinessException` + `GlobalExceptionHandler` + Bean Validation 오류 매핑.
- [ ] `Clock` 주입과 UTC 저장, Asia/Seoul 날짜 계산 분리.
- [ ] Flyway 초기 마이그레이션(17개 엔티티를 단계별로 추가해도 됨). JPA validate.
- [ ] 단순 환경/예외 관련 테스트.

### 단계 2~3 — Store/User/Auth

- [ ] 가입 시 PENDING + STAFF 초기화; 역할/소속은 클라이언트 지정 불가.
- [ ] 총괄 MANAGER 부트스트랩, 직원·부매니저 승인 시 homeStore 지정.
- [ ] 매장 범위 인가 서비스를 공통화.
- [ ] JWT Access 검증, Refresh 해시/회전/폐기, 로그아웃/비활성화.
- [ ] HTTP-only Refresh Cookie, Origin/CSRF 방어, 실패 로그인 제한 검토.
- [ ] 계정 비활성화 후 이전 JWT로 업무 API 접근 거부 확인.

### 단계 4~5 — 근무

- [ ] 경기일 endAt=null, 비경기일 09:00~19:00 샘플 API.
- [ ] 같은 직원/근무일 일정을 DB 제약+잠금으로 보호.
- [ ] 서버 실제 체크인/체크아웃 기록, 원본 변경 금지.
- [ ] 30분 단위 운영 기록 `recordedCheckOutAt` 승인(서울 시각 경계 검증).
- [ ] 퇴근 공지 배치 생성/재공지 이력/수신자 확인.
- [ ] 체크아웃과 지원 이동 상태의 충돌 규칙을 통합.

### 단계 6~8 — 상품·재고

- [ ] 공통 Product/Variant와 고유 SKU.
- [ ] INTERNAL/SHARED_EXTERNAL 위치, 공용 외부창고/143 단일 마스터.
- [ ] StockRecord의 null과 0 구분.
- [ ] StockCheckRequest 담당 매장 동적 결정과 서버 스냅샷.
- [ ] 조건부 UPDATE로 동시 수락, 요청별 결과 이력 UNIQUE, StockRecord 최초 INSERT 경쟁.
- [ ] Redis LIST 10개/7일/중복 제거/검색 장애 격리.

### 단계 9~11 — 인력 지원·알림

- [ ] 지원 승인·거절 경쟁, 요청 인원 초과/직원 중복 배정 차단.
- [ ] 이동 상태와 CurrentWorkStatusService의 WORKING/MOVING/RETURNING/OFF_DUTY 계산.
- [ ] 지원 중 체크아웃과 업무 요청 수락의 경합 테스트.
- [ ] Notification 저장과 사용자별 읽음/목록.
- [ ] MySQL Delivery Worker의 claimToken/lockedUntil/재시도/만료 구독 처리.
- [ ] SSE fetch 기반 인증, 재연결 후 데이터 갱신.
- [ ] Android Chrome 및 지원 iOS 홈 화면 PWA에서 실제 푸시 수신 확인.

## 4. 테스트 계층과 무엇을 증명할지

| 테스트 계층 | 주요 대상 | 도구 후보 | 검증 범위 |
|---|---|---|---|
| 단위 | 계산·포맷·시간 경계·상태 전이 | JUnit / AssertJ / Mockito | 외부 의존성 없는 순수 규칙 |
| Spring MVC | Controller, DTO, HTTP 오류, 인증 필터 | MockMvc, Spring Security Test | 요청 검증·인증/인가 HTTP 계약 |
| Repository 통합 | FK/UNIQUE/CHECK, JPA 쿼리 | Testcontainers MySQL + Spring Boot Test | 실제 MySQL 동작 |
| 서비스 통합 | 동시 수락/배정, 트랜잭션 롤백, 세션 회전 | Testcontainers MySQL | DB 트랜잭션과 잠금 경쟁 |
| Redis 통합 | 최근 옵션 LIST, TTL, 장애 격리 | Redis 컨테이너 / Testcontainers | 원자 리스트 변경·fallback |
| 외부 서비스 모의 | 푸시 201/429/404/410/timeout, 재시도 | HTTP Mock Server | 전송 결과 분기·재시도 |
| 브라우저 E2E | PWA 설치·푸시 권한·SSE·핵심 화면 | Playwright + 실기기 확인 | 사용자 시나리오 |

Mockito 기반 단위 테스트로 `PESSIMISTIC_WRITE`나 InnoDB 조건부 UPDATE의 동시성 보장을 증명했다고 주장하지 않는다. 일부 자동화 E2E로 OS 수준 알림 전달을 완전히 보증하지도 않는다.

## 5. 도메인별 주요 테스트 매트릭스

### 5.1 인증·인가

| 테스트 | 기대값 |
|---|---|
| 이메일 중복 가입 | 409, 유저 하나만 생성 |
| 회원가입 본문에 role/homeStoreId를 추가 | 서버가 허용하지 않거나 무시, 임의 권한 상승 불가 |
| PENDING/REJECTED/INACTIVE 업무 API 요청 | 거부 |
| AM이 타 매장 근무 승인·퇴근 공지 | 403 |
| Refresh Token 회전 후 이전 토큰 재사용 | 재사용 감지/거부 및 정책에 맞는 폐기 |
| 비활성화 이후 Access JWT 사용 | 업무 API 거부 |

### 5.2 근무·안내

| 테스트 | 기대값 |
|---|---|
| 경기일 endAt=null | 정상 생성 |
| 비경기일 09:00~19:00 | 정상 생성 |
| 동일 직원/근무일 일정 두 건 동시 생성 | 하나만 성공, 하나 409 |
| 직원 본인 외 일정으로 체크인 | 403 |
| 이미 활성 출근인데 중복 체크인 | 409 |
| 체크아웃 서버 시각 18:12, 운영 기록 18:00 | 원본 18:12 유지 |
| 기록 퇴근 시각 18:15 | 400 |
| 이미 승인한 Attendance를 다른 시각으로 재승인 | 409 |
| 퇴근 안내를 같은 직원에게 2회 발송 | 서로 다른 이력 2건 |
| 지원 이동 중 직원 체크아웃 | 409 |

### 5.3 상품·재고 확인

| 테스트 | 기대값 |
|---|---|
| 공용 외부창고/143 조회 | storeId=null, 매장별 사본 없음 |
| 수량 미확인 vs 0 | 응답 각각 null/0 |
| Redis 연결 실패 | 상품 상세 200, 최근 조회만 실패/생략 |
| 재고 요청 내부창고 | 창고 소속 매장으로 라우팅 |
| 재고 요청 공용창고 | 요청자 현재 근무 매장으로 라우팅 |
| 직원 20명 같은 요청 수락 | 1명만 ACCEPTED |
| 한 요청 동시 완료 두 번 | 확인 이력 최대 한 건 |
| StockRecord 없는 위치에 동시 최초 완료 | 옵션·위치 최신값 행 하나 |
| StockRecord 갱신 중 예외 | 확인 이력/요청/알림까지 롤백 |
| 타 매장/이동 중 직원 수락 | 403 또는 409 정책대로 거부 |

### 5.4 인력 지원

| 테스트 | 기대값 |
|---|---|
| 같은 매장에 지원 요청 | 400 |
| 요청 대상 매장이 아닌 AM 승인 | 403 |
| 동시에 한쪽 승인·한쪽 거절 | 한 처리만 유효 |
| 요청 1명에 부매니저 두 명이 각각 1명 배정 | 정원 1명 유지 |
| 서로 다른 요청이 한 직원 동시 배정 | 활성 배정 하나만 존재 |
| 체크아웃과 직원 배정 경쟁 | 출근 종료와 활성 배정이 동시에 성공하는 모순 없음 |
| ASSIGNED/RETURNING 현재 근무 위치 | null, 양쪽 매장 인원 제외 |
| ARRIVED 현재 근무 위치 | 지원받는 매장 |
| RETURNED 현재 근무 위치 | 원래 근무 일정 매장 |
| 다른 직원의 arrival/return-confirm 호출 | 403 |

### 5.5 알림·PWA

| 테스트 | 기대값 |
|---|---|
| 본인 알림 목록/읽음 | 200, 최초 readAt 유지 |
| 다른 사람의 알림 읽기 | 403/404 정책대로 거부 |
| 같은 eventKey 중복 생성 | 사용자별 알림 1개 |
| 동일 endpoint 다른 사용자 등록 | 409, 소유권 자동 이전 금지 |
| Web Push 서버 일시 실패 | Notification 보존, Delivery 재시도 |
| Web Push 404/410 무효 구독 | 구독 비활성화 |
| 여러 Worker가 같은 Delivery 선점 | 한 Lease만 유효, 오래된 Worker 덮어쓰기 방지 |
| 제공자 수락 후 DB 결과 저장 전 Worker 중단 | 중복 발송 가능성을 인정하고 표시 완화 |
| SSE 연결 끊김 후 재접속 | REST 최신 상태 재조회 |
| 이동 중 직원에게 이미 큐잉된 오래된 재고 알림 | 실제 발송 전 유효성 확인 후 생략 |

## 6. 동시성 통합 테스트 실행 설계

### 재고 요청 수락 테스트

1. Testcontainers MySQL에 StockCheckRequest 1건 REQUESTED, 동일 담당 매장의 활성 직원 N명 준비.
2. `CountDownLatch` 등으로 N개 스레드에서 **각기 다른 트랜잭션**으로 accept 동시 호출.
3. 성공 횟수==1, 나머지는 이미 수락됨 충돌. DB 상태 `status=ACCEPTED`, assignedTo 1명.
4. 실제 잠금 대기/트랜잭션 롤백/시간초과 케이스도 별도 실행.

### 인력 지원 중복 배정 테스트

1. SupportRequest A·B 각각 APPROVED, 대상 직원 동일, 모두 활성 근무 중으로 준비.
2. 서로 다른 AM 요청이 동시에 같은 User를 배정.
3. 성공은 한 요청에만, StaffAssignment 활성 상태는 최대 하나.
4. 별도 테스트로 요청 정원 1명에 서로 다른 직원 동시 배정.

### 완료 롤백 테스트

1. ACCEPTED 요청 준비.
2. Verification INSERT 이후 StockRecord UPDATE 구간에서 의도적 오류 주입.
3. DB 상태를 다시 조회해 Verification/Record/요청상태/Notification이 **모두 원래 상태**임을 확인.
4. 성공 케이스는 request=COMPLETED, Verification 1건, StockRecord 최신값, Notification/Delivery 존재.

## 7. Codex 협업 규칙

**루트에 `AGENTS.md`를 두는 것을 권장한다.** 프로젝트 구조, 설계 문서, 안전성 규칙, 테스트 실행 명령을 간결하게 명시한다. Codex에 전체 구현을 한 번에 지시하지 않고 아래와 같이 작게 분해한다.

### 작업 요청 템플릿

```text
StoreFlow의 <한 기능>을 구현해줘.

반드시 먼저 README.md와 아래 문서를 읽고 현재 코드를 검사해:
- docs/01-requirements.md
- docs/02-domain-model.md
- docs/03-erd.md
- docs/04-api-spec.md
- docs/06-decisions.md

요청 범위:
- 구현할 정확한 API 및 비즈니스 규칙: <정의>
- 필요한 Entity/Repository/Service/Controller/DTO만 작성
- 권한/동시성/예외/트랜잭션 테스트 작성

제약:
- 확정 정책을 임의 변경하지 말 것
- 범위 밖 엔티티·라이브러리를 불필요하게 추가하지 말 것
- DB 실시간 재고처럼 오해할 모델/응답을 만들지 말 것
- 변경이 필요하면 먼저 이유와 대안을 제시할 것

완료 시:
1. 수정한 파일
2. 사용한 기술과 선택 이유
3. 실행한 명령·실제 테스트 결과
4. 해결하지 못한 위험/미결 정책
5. 문서 변경이 필요한 항목
을 알려줘.
```

### 코드 리뷰 프롬프트

```text
이번 변경을 코드 수정 없이 먼저 리뷰해줘.
중점: 매장 범위 인가, 상태 전이, 동시성, 트랜잭션,
원본 기록 보존, 예외 매핑, 통합 테스트 누락.
심각도 순으로 파일/위치, 재현 조건, 영향, 수정안을 알려줘.
```

### 학습·면접 준비 기록

기능을 구현하고 난 뒤 `docs/06-decisions.md`에 다음 질문의 답을 남긴다.

- 어떤 실제 운영 문제가 있었는가?
- 왜 이 설계/기술을 적용했는가? 일반적인 `save()`/낙관적 잠금/비관적 잠금 등 대안은?
- 어떻게 동작하는가? 트랜잭션 경계와 DB SQL은?
- 실패하면 어떻게 되는가? 사용자는 무엇을 보게 되는가?
- 어떤 테스트로 이 주장을 검증했는가?

**코드를 AI가 작성했다는 사실 자체보다, 사용자 요구→설계→검증까지 설명하는 능력**을 목표로 한다.

## 8. 완료 정의(Definition of Done)

기능 하나가 '완료'되었다고 말하려면:

- [ ] 요구사항 ID·도메인 규칙·API 명세에 대응한다.
- [ ] 사용자/매장 권한과 상태 전이를 검증한다.
- [ ] 정상 흐름뿐 아니라 음수/NULL/중복/잘못된 상태/동시 호출 예외를 테스트한다.
- [ ] 주요 DB 제약/트랜잭션은 Testcontainers MySQL에서 확인한다.
- [ ] 비밀값이 Git/로그에 없다.
- [ ] 도메인별 로깅·오류 코드가 일관된다.
- [ ] README/docs의 현재 상태와 실제 코드가 일치한다.
- [ ] 기술 선택 이유/한계/남은 미결을 ADR에 기록한다.
- [ ] 브랜치/커밋 단위로 변경을 되돌릴 수 있다.

## 9. MVP 릴리스 기준

1. ACTIVE 직원이 모바일에서 출퇴근, 상품 검색, 창고 재고 확인 요청/수락/완료를 수행한다.
2. AM이 지원 요청 처리, 직원 배정·복귀, 퇴근 안내, 실제/운영 퇴근 시각 승인을 수행한다.
3. 여러 매장의 업무·권한 구분이 이루어지고 지원 이동 시 현재 근무 매장 계산이 정확하다.
4. 17개 테이블과 코드/DDL/Flyway/ERD가 일치한다.
5. 재고 수락/인력 배정 동시성 테스트, 완료 트랜잭션 롤백 테스트가 통과한다.
6. 알림함·SSE·Web Push가 연동되며 푸시 제공자 장애가 업무 완료를 취소하지 않는다.
7. 인증/기기/근무/재고의 주요 보안·프라이버시 위험을 검토하고 실기기 테스트한다.
8. 배포 후 운영 로그·메트릭·백업·환경변수 관리가 가능하다.
9. README에 실제 실행 방법, 테스트 결과, 배포 주소, 주요 기술 선택 이유를 업데이트한다.
10. 측정하지 않은 TPS, 사용자 수, 푸시 도달률을 근거 없이 기재하지 않는다.

후속 후보: AI 음성/자연어 검색, 불량·유실 처리, POS 통합, 인력 지원 자동 추천, 고급 재고 분석. **MVP의 정합성과 현장 UX가 검증된 뒤** 검토한다.
