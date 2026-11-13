# Flyway migrations

`V1__create_stores.sql`은 stores, `V2__create_users.sql`은 users를 생성한다. V3은 초기 관리자 생성 잠금/완료 기록용 manager_bootstrap 운영 테이블을 생성한다. V4는 users.rejection_reason VARCHAR(500) NULL을 추가한다. V5는 Refresh 회전/로그아웃 및 Access 즉시 차단에 사용하는 auth_sessions를 생성한다. V6는 work_schedules와 체크인 가드/후속 출퇴근 구현용 attendances를 생성한다. 나머지 업무 테이블은 아직 없다.
V1은 로컬 MySQL과 별도 Testcontainers MySQL에서 적용/재실행 검증을 완료했다.
이후 ERD의 FK 의존 순서에 따라 기능 단위로 버전을 올린다.
이미 적용된 V1~V5는 수정하지 않고 V6 이후 새 버전으로 변경한다.

- Boot 관리 버전의 Flyway Starter + `flyway-mysql`을 사용한다.
- 기본 경로: `classpath:db/migration`.
- `baseline-on-migrate=false`: 이력이 없는 기존 비어 있지 않은 DB는 자동 채택하지 않는다.
  기존 스키마/데이터와 이력을 검토한 후 도입 절차를 별도로 결정한다.
- `clean-disabled=true`: Flyway clean을 허용하지 않는다.
- `validate-on-migrate=true`: 적용된 SQL의 체크섬 등을 검증한다.
- JPA `ddl-auto=validate`와 `hibernate.jdbc.time_zone=UTC`를 사용한다.
  `create`, `create-drop`, `update`는 사용하지 않는다.

공통 예외 테스트는 MySQL/Redis에 접속하지 않는다. StorePersistenceTests는 기존 DB/볼륨과
분리된 Testcontainers MySQL 8.4에서 마이그레이션/Repository를 검증한다. UserPersistenceTests도 별도 MySQL에서 User 제약과 V1→V2 업그레이드 및 재실행 시 데이터 보존을 검증한다.
재실행 시 커밋된 행 보존, 기존 비어 있지 않은 DB의 자동 채택 거부, 체크섬 변경 감지도 확인한다.
테스트 전용 변조 SQL은 `src/test/resources/db/tampered-migration`에 있으며 validate에만 사용한다.
기존 데이터나 Docker Volume을 삭제해 초기화하지 않는다.
