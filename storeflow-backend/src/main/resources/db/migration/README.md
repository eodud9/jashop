# Flyway migrations

Phase 1은 마이그레이션 경로만 준비한다. 실행할 SQL과 업무 테이블은 아직 없다.
첫 실제 DDL은 Store/User 단계에서 `V1__create_stores_and_users.sql`처럼 추가한다.
이후 ERD의 FK 의존 순서에 따라 기능 단위로 버전을 올린다.
적용된 마이그레이션은 수정하지 않고 새 버전으로 변경한다.

- Boot 관리 버전의 Flyway Starter + `flyway-mysql`을 사용한다.
- 기본 경로: `classpath:db/migration`.
- `baseline-on-migrate=false`: 이력이 없는 기존 비어 있지 않은 DB는 자동 채택하지 않는다.
  기존 스키마/데이터와 이력을 검토한 후 도입 절차를 별도로 결정한다.
- `clean-disabled=true`: Flyway clean을 허용하지 않는다.
- `validate-on-migrate=true`: 적용된 SQL의 체크섬 등을 검증한다.
- 현재 JPA `ddl-auto=none`을 유지한다. 첫 실제 스키마와 엔티티를 도입할 때
  `validate`로 변경한다. `create`, `create-drop`, `update`는 사용하지 않는다.

공통 예외 테스트는 MySQL/Redis에 접속하지 않는다. 실제 MySQL에서의 마이그레이션
적용/재실행 검증은 첫 DDL 도입 시 별도의 테스트 DB에서 수행한다.
기존 데이터나 Docker Volume을 삭제해 초기화하지 않는다.
