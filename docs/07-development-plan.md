# 07. jashop 개발 계획 · 테스트 전략 · Codex 작업 지침

**설계 기준일:** 2026-11-05 · **구현 확인:** 2026-11-11.
공통 기반과 Phase 2-0/2-1(Store 영속성), Phase 2-2A(User 영속성) 및 Phase 2-2B(회원가입 API) 구현. 상세 검증 기록은 각 절을 따른다.
초기 MANAGER 생성과 계정 상태/역할/담당 매장 인가 서비스를 추가했다(2-2C). Store 조회/관리 API와 인가 principal 계약 연결을 추가했다(2-3). 관리자 가입 심사 API를 추가했다(2-4). 로그인·Access JWT·내 정보 조회를 구현했다(3-1). Refresh/AuthSession 및 나머지 업무·배포는 미구현이다. 인프라별 검증 범위는 아래 기록을 따른다.
로컬 DB 점검은 2026-11-07 기록이며 최신 테스트 결과는 각 단계의 실행 기록을 따른다. 현재 로컬 Compose DB에 후속 마이그레이션이 적용됐음을 의미하지 않는다.

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
| 0. 개발 환경 | `compose.yml` MySQL·Redis, `.env.example`, `application.yaml`, Gradle/Java 버전 확인 | 05 | 기본 구성·DB 비웹 기동 검증 완료. HTTP/인증·Redis 연결 검증은 남음 |
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

- [x] 현재 `jashop-backend/build.gradle`의 Java21/Spring Boot 4.1.2 확인.
- [x] Gradle Wrapper 9.8.2에서 Java 21 / Boot 4.1.2 빌드·테스트 실행 확인.
- [x] 루트 `.gitignore`에 `.env`, `.DS_Store`, `.idea/`, 빌드 산출물 제외. `.idea/`·`.DS_Store` Git 추적 항목 없음.
- [x] 루트 `compose.yml`에 MySQL 8.4/Redis 서비스와 MySQL 영속 볼륨 구성.
- [x] 루트 `.env.example`에 Compose 환경변수와 개발용 샘플 기록. 실제 `.env`는 Git 제외.
- [x] `jashop-backend/src/main/resources/application.yaml`에 DataSource/Redis 연결 설정. 별도 local 프로파일 파일은 없음.
- [x] 2026-11-07 기존 MySQL/Redis 컨테이너 실행 상태 확인 및 전체 테스트 29건 통과 기록.
- [x] 로컬 DB 자격 증명을 주입한 Spring Boot 비웹 기동 2회로 DB/Flyway/JPA 검증.
- [ ] 일반 웹 모드의 HTTP·인증 동작 검증.
- [ ] Redis 프로토콜 연결 및 실제 기능 통합 테스트.

미적용 개선안은 완료 항목과 구분한다. 현재 Compose 포트에는 호스트 IP 제한과 healthcheck가
없다. MySQL은 mysql_data를 연결하지만 Redis는 최상위 redis_data 선언만 있고 서비스 연결은 없다.
localhost 바인딩·MySQL healthcheck·필요 시 Redis 볼륨 연결은 후속 환경 개선 사항이다.
Compose의 .env는 백엔드에 자동 주입되지 않으므로 DB_PASSWORD 등 실행 환경변수를 별도로 설정한다.

**중요:** DataSource `url` 설정이 누락되면 MySQL Driver 의존성이 있어도 실행 실패한다. 외부 의존성을 무작정 제거하지 않고 실제 설정·프로파일·포트를 먼저 확인.

### 단계 1 — 공통 계층

- [x] `ApiError(code,message,timestamp,traceId,fieldErrors)` 공통 오류 형식 구현.
- [x] `ErrorCode` + `BusinessException` + `GlobalExceptionHandler` + Bean Validation 오류 매핑.
- [x] UTC `Clock` 주입과 오류 시각 생성. 업무 저장/Asia/Seoul 날짜 계산은 관련 기능에서 구현.
- [x] 첫 기능(Store)의 Flyway V1 적용·재실행 및 JPA validate. 나머지 테이블은 기능별로 추가.
- [x] 단순 환경/예외 관련 테스트.

2026-11-07 Phase 1 범위에서 기능별 패키지, Flyway 의존성/안전 설정 및
`db/migration` 디렉터리를 준비했다. 당시 업무 SQL과 엔티티는 없었으며,
첫 기능의 DDL 적용/재실행 및 JPA validate는 아래 Phase 2-1에서 완료했다.
공통 MVC 테스트는 보안 필터를 테스트 안에서만 제외한다. 운영 Security 설정은 유지하며,
인증 필터의 401/403 오류 계약은 후속 인증 단계에서 검증한다.
실제 DB 통합 검증은 Phase 2-1에서 Store 범위에 한해 완료했으며 나머지 도메인은 미검증이다.

Phase 1 당시 검증: `cd jashop-backend`에서 `./gradlew test --offline` 실행,
MVC 오류 계약 19건 + DB 비의존 컨텍스트 1건 = **20건 통과(실패/스킵 0)**.
400/401/403/404/409/500/503, 405/406/415의 상태 보존, Bean Validation,
잘못된 JSON, UTC 시각, 추적 ID 및 예외/입력값 비노출을 확인했다.

### 단계 2-0 — DB 도입 전 점검 (완료: 2026-11-07)

- [x] 이름 변경 전 기존 MySQL 컨테이너 실행과 호스트 3308 연결 확인(MySQL 8.4.11).
- [x] `information_schema`로 이름 변경 전 스키마의 테이블 0개, Flyway 이력 없음 확인.
- [x] 문자셋 utf8mb4 / collation utf8mb4_0900_ai_ci 확인.
- [x] V1 적용 직전에도 빈 스키마임을 재확인. baseline/repair/clean 또는 기존 데이터·볼륨 삭제 없음.
- [x] 통합 테스트는 기존 DB/볼륨과 분리된 일회성 Testcontainers MySQL 8.4로 구성.

이번 로컬 DB에는 기존 업무 행이 없었다. 데이터가 있는 DB에서의 자동 채택 거부와
마이그레이션 재실행 시 행 보존은 테스트 컨테이너에 데이터를 만들어 별도로 검증했다.
이력이 없는 비어 있지 않은 다른 DB를 연결할 경우 자동 baseline하지 않고 먼저 스키마를 검토한다.

### 단계 2-1 — Store 영속성 (완료: 2026-11-07)

- [x] `V1__create_stores.sql`: stores 1개 업무 테이블, PK/UNIQUE/NOT NULL/InnoDB/utf8mb4.
- [x] `Store`: ID·코드·이름·active·생성/수정 시각. 이름 변경과 데이터 보존형 비활성화.
- [x] `StoreRepository`: JPA 저장/조회, 코드 조회·존재 확인.
- [x] UTC DATETIME(6) 매핑과 `ddl-auto=validate`.
- [x] 실제 MySQL 통합 테스트 9건 및 기존 공통 테스트 20건 통과(실패/스킵 0).
- [x] 로컬 빈 DB V1 적용 후 비웹 모드 기동 2회 성공: 최초 적용 / 재실행 no-op / JPA validate.

통합 테스트 9건: 저장·재조회와 UTC 마이크로초, 코드 조회·존재 확인, 코드 UNIQUE,
이름 변경 dirty checking, 비활성화 후 행 보존, 커밋된 행을 둔 migrate 재실행 no-op,
스키마 속성, 이력 없는 비어 있지 않은 DB의 자동 채택 거부, 체크섬 변경 감지.
2026-11-07 로컬 적용 직후에는 `stores`와 `flyway_schema_history`만 존재했고 `stores` 행은 0개였다.
현재 DB 상태를 실시간으로 조회한 결과는 아니다.

실행 명령(백엔드 디렉터리):

```bash
./gradlew test
# 로컬 DB 자격 증명을 환경변수로 주입한 상태에서 DB/JPA 기동 점검
./gradlew bootRun --args='--spring.main.web-application-type=none --spring.data.redis.repositories.enabled=false'
```

통합 테스트의 연결 정보는 `@ServiceConnection`으로 테스트 컨테이너에서 제공한다.
기존 공통 테스트용 `test` 프로파일의 DB 제외 설정을 사용하지 않는다.
로컬 점검의 비웹 모드는 HTTP/API·Redis 기능 검증을 의미하지 않는다.
Store Controller/Service/API와 User 도메인은 이번 범위에서 추가하지 않았다.

### 단계 2-2 이후 및 단계 3 — User/Store API/Auth

User 영속성(2-2A)과 회원가입(2-2B)을 구현했다. 초기 MANAGER/인가 기반(2-2C)을 구현했다. Store API/인가 계약 연결(2-3)을 구현했다. 관리자 가입 심사(2-4)를 구현했다. 로그인·Access JWT(3-1)를 구현했다. 다음 인증 작업은 Refresh/AuthSession·로그아웃이다. 이후 체크리스트는 별도 완료 표시가 없으면 미구현이다.

| 작업 | 구현 범위 | 완료 기준 |
|---|---|---|
| 2-2A User 영속성 (구현) | 새 V2 SQL, User/역할/상태, Repository. 기존 V1은 유지 | MySQL 저장·조회, 이메일 UNIQUE, Store/승인자 FK, enum/소속 CHECK, JPA validate |
| 2-2B 회원가입 (구현) | DTO, 비밀번호 해시, 가입 Service/Controller | STAFF/PENDING·소속 없음, 201/중복 409, 입력 검증·권한 상승 방지 |

이메일 정규화는 `strip()` + `Locale.ROOT` 소문자화로 구현했다. Repository 조회 전에도 `User.normalizeEmail()`을 사용한다. 회원가입에서 이메일 형식, 비밀번호 8~72자/UTF-8 최대 72바이트를 검증하고 미허용 필드는 400으로 거부한다. 해시는 BCrypt 비용 12를 사용한다. 초기 MANAGER/인가 기반은 2-2C로 구현했다. Store API는 2-3으로 구현했다. 승인·거절은 2-4로 구현했다. 로그인·Access JWT는 3-1로 구현했다. Refresh/AuthSession은 후속 단계다.

- [x] 가입 시 PENDING + STAFF 초기화; 역할/소속은 클라이언트 지정 불가.
- [x] 총괄 MANAGER 일회성 비웹 부트스트랩 및 DB 동시 실행 보호.
- [x] 직원·부매니저 승인 시 ACTIVE homeStore 지정, 승인 대기 목록 및 거절/사유 저장.
- [x] ACTIVE 계정/총괄 권한/담당 매장 부매니저 인가 서비스. Store API principal 계약은 연결했으며 실제 principal 발급은 3-1의 Access JWT와 연결했다.
- [x] 로그인 및 JWT Access 발급·검증, 기존 UserPrincipal/DB 인가 연결.
- [ ] Refresh 해시/회전/폐기, 로그아웃/비활성화 API.
- [ ] HTTP-only Refresh Cookie, Origin/CSRF 방어, 실패 로그인 제한 검토.
- [x] 계정 비활성화 후 이전 JWT로 업무 API 접근 거부 확인.



**Phase 2-2A 검증(2026-11-09):** 백엔드에서 `./gradlew test --offline` 실행, 전체 43건 통과(실패/스킵 0): 기존 공통 20 + Store 9 + User 단위 3 + User MySQL 통합 11.
User 기본값·정규화·필드 경계, UTC 마이크로초 저장, 이메일 조회/UNIQUE, 매장·승인자 FK 및 삭제 제한, enum/ACTIVE 소속 CHECK, V1→V2 업그레이드와 커밋된 행의 재실행 보존을 확인했다.
첫 실행에서 CHECK 위반 4건의 예외 타입 기대값이 실제 MySQL JDBC 반환과 달라 실패했다. 테스트를 오류 코드 3819·제약 이름 검증으로 수정한 뒤 전체 재실행에 성공했다.
기존 V1 SQL은 변경하지 않았고 로컬 Compose DB/볼륨에 V2를 적용하지 않았다.



**Phase 2-2B 검증(2026-11-09):** `./gradlew test --offline` 전체 79건 통과(실패/오류/스킵 0). 기존 43건 + 신규 Service 단위 4건 + 실제 MySQL/운영 보안 필터를 사용하는 회원가입 통합 32건이다.
가입 성공·정규화·해시 검증/오답 거부·비밀값 비노출, Salt 차이, 입력 오류 13건, 미허용 필드 5건, 잘못된 본문 3건, 비밀번호 경계 4건, 중복 409·동시 가입 단일 저장, 다른 경로 인증/CSRF 유지 등을 검증했다.
최초 실행에서 기존 DB 비의존 컨텍스트 테스트가 새 Service의 Repository 의존성으로 실패했다. 해당 테스트에만 Repository 대역을 제공한 뒤 전체 재실행에 성공했다. 실제 가입 통합 테스트에서는 Repository를 대체하지 않는다.
기존 V1/V2 SQL 원문이 HEAD와 일치함을 확인했고, 기존 Compose DB·볼륨은 변경하지 않았다.

### 단계 2-2C — 초기 MANAGER 및 인가 기반 (완료: 2026-11-10)

- [x] 기본 비활성 운영자 전용 비웹 Runner, 환경변수 자격 증명과 입력 검증, BCrypt 해시.
- [x] V3 manager_bootstrap 단일 행 잠금 및 완료 기록. 서로 다른 이메일 동시 실행과 비활성화 후 재생성을 차단한다.
- [x] 기존 이메일 자동 승격 금지, 사용자·완료 기록 원자 커밋/롤백.
- [x] UserAuthorizationService: ACTIVE 계정, 총괄 권한, 담당 매장 부매니저 권한 확인. MANAGER의 부매니저 전용 명령 우회 없음.
- [x] README 운영 절차와 도메인/ERD/API/아키텍처/ADR 업데이트.

검증: `./gradlew test --offline` 전체 **99건 통과**(실패/오류/스킵 0). 기존 79건 + 신규 20건(인가 단위 11, Runner 단위 2, 실제 MySQL 통합 7)이다. 동시 생성 1명, 비활성화 이후 재생성 거부, 기존 가입자 불변, 입력 검증 비밀값 비노출, 완료 기록 실패 시 사용자 롤백, DB 역할/상태 변경 반영을 확인했다.
초기 테스트 컴파일의 AssertJ 메서드 인자 오류와 롤백용 트리거 생성 권한 오류는 수정 후 전체 재실행으로 해결했다. 트리거 권한 설정은 분리된 테스트 컨테이너에만 적용했다.
V1/V2는 변경하지 않았으며 기존 Compose DB/볼륨에 V3을 적용하거나 실제 관리자 계정을 생성하지 않았다. JWT 및 업무 API에서 principal 연결/매장 활성/업무 자격 검증은 후속 단계다.

### 단계 2-3 — Store API와 인가 연결 (완료: 2026-11-10)

- [x] 목록/상세/생성/이름 변경/비활성화 Controller, 요청·응답 DTO와 StoreService.
- [x] page=0/size=20/active=true 기본값, size 최대 100, ID ASC 고정 정렬 및 명시적 페이지 응답 DTO.
- [x] 서비스 트랜잭션 내 ACTIVE/총괄 인가, UserPrincipal 계약 및 Store 전용 보안 체인/공통 오류 응답.
- [x] 동일 매장 변경은 PESSIMISTIC_WRITE 행 잠금으로 직렬화. 코드 UNIQUE 경쟁을 409로 변환.
- [x] 기존 엔티티·DB 스키마·V1~V3 보존, API/설계/ADR/README 업데이트.

검증: `./gradlew test --offline` 전체 **142건 통과**(실패/오류/스킵 0). 기존 99건 + Store API/실제 MySQL 통합 39건 + Service 단위 4건이다. 실제 운영 보안 필터와 테스트 인증 객체로 인증/역할/계정 상태, 위조 헤더·미허용 필드, CSRF, 페이지·필터·정렬·오류·CRUD를 검증했다. 실제 MySQL에서 동시 코드 중복 201/409, 이름 변경+비활성화 결과 보존, 같은 매장 잠금 대기와 다른 매장 변경 진행을 확인했다. 단위 테스트는 중복 사전 검사 이후 UNIQUE 예외 변환 및 다른 제약 오류 비오인, 서비스 직접 호출 인가/입력 검증을 확인한다.
최초 전체 실행에서 미인증 Authentication 객체의 거부가 401 대신 403으로 응답해 1건 실패했다. Store 필터에서 인증 실패를 401로 구분하고 CSRF 실패는 403을 유지하도록 수정한 뒤 전체 재실행에 성공했다.
실제 principal을 발급하는 로그인/JWT는 아직 없어 실제 계정의 HTTP 사용은 후속 단계다. 잠금 대기는 MySQL timeout 설정을 따르며 사용자 권한 변경까지 직렬화하지 않는다. 기존 Compose DB/볼륨은 변경하지 않았다.

### 단계 2-4 — 관리자 가입 승인·거절 (완료: 2026-11-10)

- [x] ACTIVE MANAGER 전용 승인 대기 목록·승인·거절 Controller/DTO/Service.
- [x] PENDING만 심사, STAFF/ASSISTANT_MANAGER 및 ACTIVE homeStore 승인, 승인자/시각 서버 기록.
- [x] 선택적 거절 사유 최대 500자 저장과 V4 nullable 컬럼 추가. 기존 V1~V3 유지.
- [x] User → Store 잠금 순서로 동시 심사 및 매장 비활성화 경쟁 보호.
- [x] 기존 principal/인가 재사용, CSRF 유지, 공통 필터 오류 처리기 재사용, 비밀번호/해시 비노출.

검증: 백엔드 `./gradlew test --offline` 전체 **190건 통과**(실패/오류/스킵 0). 기존 142건 + 신규 API/실제 MySQL 통합 42건 + 도메인 단위 6건이다. 권한/계정 상태/CSRF/위조 식별자, 대기 목록 페이지·정렬, 승인 역할·매장 검증, 승인자/시각·거절 사유 저장, 반복 심사 거부, 승인/승인 및 승인/거절 경쟁 단일 성공을 검증했다. Store 비활성화 행 잠금에 대한 승인 대기·실패 후 롤백 및 비활성 커밋 후 승인 거부도 확인했다. V3→V4 업그레이드·재실행에서 기존 사용자/비밀번호 해시/초기 관리자 기록 보존을 검증했다.
테스트 실패 없이 전체 실행에 성공했다. 실행 권한이 필요한 Gradle 캐시/Docker 접근은 승인된 권한으로 실행했으며 기존 Compose DB/볼륨·실사용자는 변경하지 않았다. 로그인/JWT·사용자 역할 변경·비활성화·재심사/알림은 이번 범위에 없다.

### 단계 3-1 — 로그인·Access JWT·내 정보 (완료: 2026-11-11)

- [x] JSON 로그인, 이메일 정규화·기존 BCrypt 검증 후 ACTIVE 계정만 Access 발급.
- [x] Spring Security resource-server/jose 및 Nimbus HS256 발급/검증, 외부 키 필수 및 기동 시 형식/길이 검증.
- [x] Clock 기반 필수 claim/issuer/audience/시간/사용 목적 검증, 만료 및 인증 오류 공통 응답.
- [x] 검증된 UserPrincipal로 기존 Store/가입 심사와 GET /users/me 연결, DB 상태/권한 재검사.
- [x] Basic/Form/기본 logout 제거, Bearer 업무 API의 CSRF 요구 조정과 명시적 CORS. 쿠키/세션 인증 없음.
- [x] 설정·실행·API·ADR·README 갱신, 기존 V1~V4 및 DB 스키마 유지.

검증: `./gradlew test --offline` 전체 **237건 통과**(실패/오류/스킵 0). 기존 190건 + 실제 MySQL/운영 JWT 필터 로그인 통합 40건 + 설정 검증 7건이다. 실제 로그인 토큰으로 내 정보·Store 생성·가입 승인/거절과 승인 계정 로그인을 검증했다. 비밀번호/계정 상태, 서명/필수 claim/만료 경계/잘못된 시간/위조 역할, 쿠키·query·Basic·기존 세션 거부, 상태/권한 변경 반영, CORS, 비밀값 비노출, 외부 키 누락/형식/길이·TTL 검증을 확인했다. 테스트 키는 프로세스별 무작위 생성이다.
첫 실행의 기존 CSRF 기대값 실패 2건은 Bearer 전용 업무 경로 계약에 맞춰 변경했다. 신규 테스트 중 exp가 iat보다 빠른 토큰을 Spring 발급기로 생성하려다 라이브러리 사전 검증에 막힌 1건은 테스트 토큰을 하위 JOSE API로 생성해 검증하도록 수정했다. 이후 전체 재실행에 성공했다. 첫 의존성 다운로드는 온라인 테스트 실행으로 수행했고 최종 검증은 offline으로 통과했다.
Refresh/AuthSession/로그아웃·로그인 실패 제한·프론트 UI는 추가하지 않았다. 웹 기동 시 외부 JWT 키가 필요하며 비웹 부트스트랩은 키 없이 실행한다. 기존 Compose DB/볼륨·실사용자 및 적용된 마이그레이션은 변경하지 않았다.

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
jashop의 <한 기능>을 구현해줘.

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
