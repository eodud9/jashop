# 04. REST API 상세 명세

**Base URL:** `/api/v1` · **기준일:** 2026-11-05  
[확정] 대화에서 확정한 경로·비즈니스 규칙 / [권장] 구현 편의를 위한 DTO·예외/멱등 상세 / [미결] 정책 결정이 필요한 API는 별도 표시한다.

> 이 문서는 **실제 구현할 계약의 기준**이다. 아직 코드에 모든 API가 존재한다는 뜻이 아니다. 대표 JSON의 ID·시각·매장명은 설명용 가상 값이다.

## 1. 공통 API 규약

### 1.1 인증·권한·시각

- Access JWT: `Authorization: Bearer {accessToken}`. 업무 API는 `User.status=ACTIVE`와 해당 `role`·리소스 매장 범위 확인.
- Refresh Token: HttpOnly, Secure(HTTPS), 적절한 SameSite 쿠키 사용 **[권장]**. 쿠키 기반 Refresh/Logout에는 CSRF·Origin 보호 적용.
- 클라이언트는 `createdBy`, `requestedBy`, `assignedTo`, `recordedAt`, `currentStoreId` 등 **서버가 책임지는 값**을 임의로 입력하지 않는다.
- 요청의 `@AuthenticationPrincipal`에서 본인을 식별하고 DB에서 계정 상태와 매장 범위를 확인한다.
- API 일시각은 offset 있는 ISO 8601. 저장은 UTC, `workDate`와 30분 경계 판단은 `Asia/Seoul`.
- JSON ID는 JavaScript의 큰 정수 정밀도 손실을 방지하기 위해 문자열 응답을 고려할 수 있으나 **MVP 제안**이다. 아래 설명 예제는 읽기 편하게 숫자를 사용하고, 프론트/백엔드 구현 전 하나로 통일한다.

### 1.2 HTTP 및 오류

| 결과 | 상태 | 정책 |
|---|---|---|
| 조회·상태 변경 | 200 OK | 응답 DTO 반환 |
| 생성 | 201 Created | DTO, 가능하면 Location 헤더 |
| 본문 없는 삭제 | 204 No Content | 구독 해제 등 |
| 입력 형식·검증 실패 | 400 Bad Request | Bean Validation/도메인 검증 |
| 로그인 필요·토큰 오류 | 401 Unauthorized | 인증 실패 |
| 권한/매장 범위 거부 | 403 Forbidden | 인증은 되었으나 동작 불가 |
| ID 없음 | 404 Not Found | 해당 리소스 없음 |
| 이미 처리됨/상태 충돌/동시성 경쟁 | 409 Conflict | 상태 전이 불가 |
| 일시적 인프라 장애 | 503 Service Unavailable | 조건에 맞는 한정적 처리 |

```json
{
  "code": "STOCK_REQUEST_ALREADY_CLAIMED",
  "message": "이미 다른 직원이 수락한 요청입니다.",
  "timestamp": "2026-11-05T09:22:00Z",
  "traceId": "example-trace-id",
  "fieldErrors": []
}
```

**Phase 1 구현(2026-11-07):** 공통 오류 DTO는 위의 5개 필드를 사용한다.
`fieldErrors` 항목은 `{ "field": "quantity", "message": "수량은 0 이상이어야 합니다." }`
형식이며 입력 원문/거부된 값은 포함하지 않는다. UTC `Clock`으로 `timestamp`를 생성하고,
서버 MDC의 `traceId`가 없으면 요청 범위 UUID를 생성한다. 클라이언트 추적 헤더는 사용하지 않는다.
처리되지 않은 예외는 `INTERNAL_SERVER_ERROR`/500과 고정 메시지를 반환하며,
로그에는 추적 ID와 예외 클래스만 남긴다. 응답은 Accept 불일치 오류에서도 JSON이다.
MVC의 잘못된 JSON·누락/타입 오류·요청 Bean Validation은 `VALIDATION_FAILED`/400,
없는 경로는 `RESOURCE_NOT_FOUND`/404, 메서드 불일치는 `METHOD_NOT_ALLOWED`/405,
응답 형식 불일치는 `NOT_ACCEPTABLE`/406, 요청 Content-Type 오류는
`UNSUPPORTED_MEDIA_TYPE`/415로 매핑한다. 기타 프레임워크 오류는 원래 HTTP 상태를 보존하면서
4xx는 `REQUEST_FAILED`, 5xx는 `INTERNAL_SERVER_ERROR`(503은 `SERVICE_UNAVAILABLE`)로 반환한다.
Store API의 Spring Security 필터 401/403은 공통 ApiError 형식으로 구현했다. 나머지 경로의 필터 응답 통합은 인증 구현 단계에서 처리한다.

- 페이지 조회는 기본 `page=0&size=20`, 최대 size=100 **[권장]**. 대량 알림/이력은 `cursor` 기반 페이지네이션을 선택할 수 있으며 실제 각 엔드포인트 계약을 구현 때 고정한다.
- [권장] 생성·완료 명령의 네트워크 재시도는 `Idempotency-Key` 헤더 또는 요청 키 테이블/도메인 유일성으로 통제한다. **키 설계가 확정되지 않았으므로 자동으로 모두 멱등하다고 주장하지 않는다.**
- JWT 만료와 사용자 비활성화 등 계정 상태 검사는 별개다. 토큰이 유효해도 권한이 철회됐으면 업무 API를 거부해야 한다.

### 1.3 역할 기호

- **STAFF+**: ACTIVE STAFF, ASSISTANT_MANAGER, MANAGER가 가진 일반 조회 기능. 명령마다 현재 근무 상태 검증은 별개.
- **AM(매장)**: 그 매장을 자신의 `homeStoreId`로 관리하는 ACTIVE ASSISTANT_MANAGER.
- **MANAGER**: ACTIVE 총괄 매니저. 일반 직원과 동일한 자기 근무 동작에 대해서는 별도 자격 검사.

---

## 2. Auth · User · Store

**구현 상태(2026-11-10):** Store/User 영속성, 공개 회원가입, 초기 관리자 생성, 인가 서비스 및 Store 조회·관리 API를 구현했다. 인증 사용자 계약은 연결했으나 로그인/JWT 제공자는 아직 없어 실제 계정의 HTTP 사용은 인증 단계 이후 가능하다. 나머지 User/Auth API와 현재 근무자 조회는 미구현이다.

### 2.1 엔드포인트

| Method | Endpoint | 권한 | 주요 요청/기능 |
|---|---|---|---|
| POST | `/auth/signup` | 공개 | `{name,email,password}` → PENDING 가입 |
| POST | `/auth/login` | 공개 | `{email,password}` → Access JWT 및 Refresh 세션 |
| POST | `/auth/refresh` | Refresh 쿠키 | Refresh 회전/재발급 |
| POST | `/auth/logout` | 로그인 사용자 | 현재 Refresh 세션 폐기 |
| GET | `/users/me` | ACTIVE | 내 계정/역할/기본 소속 |
| GET | `/admin/users/pending` | MANAGER | 승인 대기 목록 |
| POST | `/admin/users/{id}/approve` | MANAGER | `{role,homeStoreId}` 승인 |
| POST | `/admin/users/{id}/reject` | MANAGER | `{reason?}` 거절 |
| PATCH | `/admin/users/{id}/role` | MANAGER | `{role}` 역할 변경·범위 검증 |
| PATCH | `/admin/users/{id}/home-store` | MANAGER | `{homeStoreId}` 기본 소속 변경 |
| POST | `/admin/users/{id}/deactivate` | MANAGER | 계정 비활성화 [권장] |
| GET | `/stores` | STAFF+ | 매장 목록 |
| GET | `/stores/{id}` | STAFF+ | 매장 상세 |
| POST | `/stores` | MANAGER | `{name,code}` 매장 생성 |
| PATCH | `/stores/{id}` | MANAGER | 이름 등 수정 |
| PATCH | `/stores/{id}/deactivate` | MANAGER | 비활성화 [권장] |
| GET | `/stores/{id}/working-staff` | STAFF+ | 현재 해당 매장에서 WORKING인 직원 목록 |

### 2.2 회원가입 — POST `/auth/signup`

```json
{
  "name": "홍길동",
  "email": "staff@example.com",
  "password": "example-only-password"
}
```

**201 Created**

```json
{
  "id": 101,
  "name": "홍길동",
  "email": "staff@example.com",
  "role": "STAFF",
  "status": "PENDING",
  "homeStoreId": null
}
```

**확정:** 가입 요청에 `role`, `homeStoreId`를 받지 않는다. 계정 승인 권한은 MANAGER에게만 있다. 이메일 정규화, 중복 DB UNIQUE, 비밀번호 안전한 해시가 필요하다.

**구현(Phase 2-2B):**
- 이름은 공백만 입력할 수 없으며 최대 100자다.
- 이메일은 앞뒤 공백 제거·Locale.ROOT 소문자화 후 필수값·형식·최대 254자를 검증한다. 실제 메일 소유 확인은 구현하지 않았다.
- 비밀번호는 공백만 입력할 수 없으며 8~72자, UTF-8 인코딩 시 최대 72바이트다. 앞뒤 공백을 보존하며 문자 종류 조합은 강제하지 않는다.
- `name`, `email`, `password` 이외의 필드는 무시하지 않고 400 `VALIDATION_FAILED`로 거부한다. 잘못된 JSON·입력 검증 실패도 같은 공통 오류 형식이다. 입력 원문은 오류 응답에 포함하지 않는다.
- 사전 이메일 중복 검사와 DB `uk_users_email` UNIQUE 위반을 모두 409 `DUPLICATE_EMAIL`로 처리한다. 다른 DB 제약 오류를 이메일 중복으로 바꾸지 않는다.
- 성공은 201과 위 6개 필드의 DTO만 반환한다. 비밀번호·해시·토큰을 반환하지 않고 로그인 세션도 만들지 않는다. 조회용 사용자 API가 아직 없으므로 Location 헤더는 생략한다.
- 해시는 Spring Security DelegatingPasswordEncoder + BCrypt 비용 12를 사용해 `{bcrypt}` 접두사와 함께 저장한다. Salt는 매번 자동 생성된다.
- 공개 보안 체인은 해당 POST 경로에만 매칭되고 STATELESS이며 해당 체인에서만 CSRF 검사를 제외한다. 다른 경로의 기존 기본 인증·CSRF 정책은 유지한다. JWT·계정 승인·Refresh·로그아웃 기능은 아직 없다.

### 2.3 승인 — POST `/admin/users/{id}/approve`

```json
{
  "role": "ASSISTANT_MANAGER",
  "homeStoreId": 2
}
```

검증: 대상 상태 PENDING, 매장 ACTIVE, STAFF/ASSISTANT_MANAGER는 homeStoreId 필수, MANAGER 부여는 별도 안전한 부트스트랩/권한 부여 정책에 따름. 승인자와 시각을 서버가 기록한다. 동일 승인/거절 경쟁을 DB에서 제어한다.

### 2.4 로그인·Refresh

```json
POST /api/v1/auth/login
{
  "email": "staff@example.com",
  "password": "example-only-password"
}
```

```json
{
  "accessToken": "<signed-jwt>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "user": {"id": 101, "name": "홍길동", "role": "STAFF", "status": "ACTIVE", "homeStoreId": 2}
}
```

Refresh Token은 **HTTP-only 쿠키**에만 제공하는 [권장] 설계이며 실제 Access/Refresh TTL(예시 900초)은 확정하지 않았다. `AuthSession`에서 Refresh 해시·계열·만료·폐기를 관리하고 회전 경쟁을 테스트한다. PENDING/REJECTED/INACTIVE 상태는 업무 토큰을 발급하지 않는다.

---

## 3. WorkSchedule · Attendance · CheckOutNotice

### 3.1 엔드포인트

| Method | Endpoint | 권한 | 기능 |
|---|---|---|---|
| POST | `/work-schedules` | MANAGER | 근무 일정 생성 |
| GET | `/work-schedules/me` | 본인 | 개인 일정 |
| GET | `/work-schedules` | AM(해당 매장), MANAGER | 매장/날짜별 일정 |
| GET | `/work-schedules/{id}` | 본인, AM(일정 매장), MANAGER | 일정 상세 |
| PATCH | `/work-schedules/{id}` | MANAGER | 일정 출근/예정 퇴근/매장 변경 |
| POST | `/work-schedules/{id}/cancel` | MANAGER | 일정 취소 |
| POST | `/attendances/me/check-in` | 본인 | 일정 기반 실제 체크인 |
| POST | `/attendances/me/check-out` | 본인 | 현재 활성 출근의 실제 체크아웃 |
| GET | `/attendances/me/current` | 본인 | 내 활성 근무·현재 매장 |
| GET | `/attendances/me` | 본인 | 내 출퇴근 이력 |
| GET | `/attendances` | AM(일정 매장), MANAGER | 직원 출퇴근 조회 |
| POST | `/attendances/{id}/approve` | AM(일정 매장) | 30분 단위 운영 기록 입력 및 승인 |
| POST | `/checkout-notices` | AM(일정 매장) | 직원 여러 명에게 퇴근 안내 |
| GET | `/checkout-notices/me` | 본인 | 퇴근 안내 내역 [권장 추가] |
| POST | `/checkout-notices/{id}/acknowledge` | 대상 본인 | 퇴근 안내 확인 |

### 3.2 일정 생성 — POST `/work-schedules`

경기일 예시:

```json
{
  "userId": 101,
  "storeId": 2,
  "startAt": "2026-11-05T10:00:00+09:00",
  "endAt": null
}
```

비경기일 예시:

```json
{
  "userId": 101,
  "storeId": 2,
  "startAt": "2026-11-06T09:00:00+09:00",
  "endAt": "2026-11-06T19:00:00+09:00"
}
```

- [확정] endAt 선택 가능. 존재하면 endAt > startAt.
- [권장] 동일 직원의 서울 시간 기준 `workDate`에 SCHEDULED 일정 하나만 허용. User 행 잠금과 활성 일정 UNIQUE.
- [권장] 이미 체크인한 일정의 startAt/storeId 변경 또는 취소는 409; 실제 발생한 근무 기록 보존.

### 3.3 실제 출퇴근 — POST `/attendances/me/check-in` · `/check-out`

체크인 요청:

```json
{
  "workScheduleId": 701
}
```

체크인 성공 예시:

```json
{
  "id": 801,
  "workScheduleId": 701,
  "checkInAt": "2026-11-05T00:58:00Z",
  "checkOutAt": null,
  "recordedCheckOutAt": null,
  "approvalStatus": "PENDING"
}
```

**체크아웃 요청은 본문 없음.** 서버가 인증된 사용자의 유일한 활성 Attendance를 찾고, 현재 지원 배정 ASSIGNED/ARRIVED/RETURNING 여부를 검사한 다음 `checkOutAt`을 서버 시각으로 기록한다. 클라이언트가 실제 시각을 보내지 않는다. 여러 활성 출근이 조회되면 데이터 이상으로 처리하고 조용히 임의 종료하지 않는다.

체크아웃 응답 예시:

```json
{
  "id": 801,
  "workScheduleId": 701,
  "checkInAt": "2026-11-05T00:58:00Z",
  "checkOutAt": "2026-11-05T09:12:00Z",
  "recordedCheckOutAt": null,
  "approvalStatus": "PENDING"
}
```

- `attendanceId`를 클라이언트가 필수로 전달하는 설계는 **기존 대화의 확정 API가 아니다.** 체크아웃 재시도의 대상 식별 모호성은 별도의 API 멱등 정책으로 해결할 미결 사항이다.
- 근무 일정 소유·ACTIVE 계정·사용자당 활성 근무 하나·상태 전이를 User 단위 잠금/트랜잭션으로 보호한다.

### 3.4 운영 기록 승인 — POST `/attendances/{id}/approve`

```json
{
  "recordedCheckOutAt": "2026-11-05T18:00:00+09:00"
}
```

**200 OK**

```json
{
  "id": 801,
  "checkOutAt": "2026-11-05T09:12:00Z",
  "recordedCheckOutAt": "2026-11-05T09:00:00Z",
  "approvalStatus": "APPROVED",
  "approvedBy": 201,
  "approvedAt": "2026-11-05T09:20:00Z"
}
```

검증 순서: ① AM(원래 **WorkSchedule.storeId**) 매장 범위 확인 ② 체크아웃 완료 확인 ③ PENDING 확인 ④ 서울 시간 기준 00/30분·초 0 확인 ⑤ 원본 `checkOutAt` 보존 ⑥ 기록 시각/승인자/승인 시각 기록. 이미 승인되었으면 임의 재기록 거절(409). 실제·운영 시각의 차이 사유 기록은 [미결].

### 3.5 퇴근 안내 — POST `/checkout-notices`

```json
{
  "workScheduleIds": [701, 702],
  "plannedCheckOutAt": "2026-11-05T18:30:00+09:00"
}
```

**201 Created**

```json
{
  "notices": [
    {"id": 901, "workScheduleId": 701, "plannedCheckOutAt": "2026-11-05T09:30:00Z", "acknowledgedAt": null},
    {"id": 902, "workScheduleId": 702, "plannedCheckOutAt": "2026-11-05T09:30:00Z", "acknowledgedAt": null}
  ]
}
```

모든 대상이 해당 AM의 관리 매장에 속하는지 확인하고 한 트랜잭션에서 생성한다. 재공지 시 **새 Notice 행**을 생성한다. `POST /checkout-notices/{id}/acknowledge`는 본인 안내에 대해서만 서버 확인 시각을 기록하고 이미 확인했다면 최초 시각 유지. `WorkSchedule.endAt`과 `Attendance.checkOutAt`은 변경하지 않는다.

---

## 4. Product · ProductVariant · StorageLocation · StockRecord

### 4.1 엔드포인트

| Method | Endpoint | 권한 | 기능 |
|---|---|---|---|
| GET | `/products?keyword=&page=0&size=20` | STAFF+ | 상품/옵션 검색 |
| GET | `/products/{id}` | STAFF+ | 상품·옵션 목록 |
| GET | `/product-variants/{id}` | STAFF+ | 옵션 상세, Redis 최근 조회 기록 |
| GET | `/product-variants/{id}/stock-records` | STAFF+ | 위치별 마지막 확인값 |
| GET | `/products/recent` | 본인 | Redis 최근 조회한 옵션 10개 |
| POST | `/products` | MANAGER | 공통 상품 등록 |
| PATCH | `/products/{id}` | MANAGER | 공통 상품 수정 |
| POST | `/products/{id}/variants` | MANAGER | 상품 옵션 등록 |
| PATCH | `/product-variants/{id}` | MANAGER | 옵션 수정 [권장] |
| GET | `/storage-locations` | STAFF+ | 내부/공용 위치 조회 |
| GET | `/storage-locations/{id}` | STAFF+ | 위치 상세 [권장] |
| POST | `/storage-locations` | MANAGER | 창고/위치 등록 |
| PATCH | `/storage-locations/{id}` | MANAGER | 이름/활성 상태 변경 [권장] |
| GET | `/stock-verifications` | STAFF+ 범위 검사 | 마지막 확인 이력 검색 [권장] |

### 4.2 상품 검색 — GET `/products?keyword=홈유니폼&page=0&size=20`

```json
{
  "content": [
    {
      "productId": 101,
      "productName": "2026 홈 유니폼",
      "variants": [
        {"id": 1001, "size": "M", "color": "화이트", "sku": "HOME-2026-M"},
        {"id": 1002, "size": "L", "color": "화이트", "sku": "HOME-2026-L"}
      ]
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

이 JSON은 **조회 예시**다. 대규모 상품 목록에서 모든 옵션을 무제한 중첩 반환하지 않는다. 실제 검색 조건과 페이징 쿼리를 구현하면서 정한다. 공통 상품 마스터 변경은 MANAGER 권한으로 제한한다.

### 4.3 위치별 마지막 수동 확인값 — GET `/product-variants/{id}/stock-records`

```json
{
  "productVariantId": 1002,
  "locations": [
    {"storageLocationId": 1, "name": "치어샵 내부창고", "type": "INTERNAL", "storeId": 1, "lastVerifiedQuantity": 8, "lastVerifiedAt": "2026-11-03T05:20:00Z"},
    {"storageLocationId": 3, "name": "외부창고", "type": "SHARED_EXTERNAL", "storeId": null, "lastVerifiedQuantity": 25, "lastVerifiedAt": "2026-11-01T02:30:00Z"},
    {"storageLocationId": 4, "name": "143", "type": "SHARED_EXTERNAL", "storeId": null, "lastVerifiedQuantity": null, "lastVerifiedAt": null}
  ]
}
```

- `null`은 미확인, `0`은 사람이 확인한 0개.
- 공용 외부 창고를 여러 매장의 별도 수량으로 표시하거나, 서로 다른 시점에 확인한 값을 **실시간 총재고로 단정**하지 않는다.
- `StockRecord`에는 직접 수량 변경 API를 두지 않는다. `StockVerification`을 거쳐 갱신한다.
- 활성 위치에 레코드가 전혀 없는 경우도 UI가 미확인으로 볼 수 있도록 null DTO 합성 [권장].

### 4.4 최근 조회 — GET `/products/recent`

직원이 `GET /product-variants/{id}`로 **실제 상세 조회에 성공한 옵션 ID**를 Redis LIST 앞에 이동한다. 같은 ID의 기존 항목 제거 → LPUSH → LTRIM 10개 → TTL 7일. 가능한 경우 원자적으로 실행한다. Redis 오류면 상품 상세 조회는 정상 200으로 마친다. 최근 목록 조회는 자체적으로 조회 기록을 갱신하지 않는다.

---

## 5. StockCheckRequest · StockVerification

### 5.1 엔드포인트

| Method | Endpoint | 권한 | 기능 |
|---|---|---|---|
| POST | `/stock-check-requests` | 현재 WORKING 직원 | 재고 확인 요청 생성 |
| GET | `/stock-check-requests` | 요청자/현재 담당 매장 직원/매장 관리자/총괄 | 업무 목록 |
| GET | `/stock-check-requests/{id}` | 요청/담당/관계자 | 업무 상세 |
| POST | `/stock-check-requests/{id}/accept` | responsibleStore 현재 WORKING 직원 | 한 명만 수락 |
| POST | `/stock-check-requests/{id}/complete` | 수락 담당 직원 | 확인 이력·마지막 확인값·완료 원자 처리 |
| POST | `/stock-check-requests/{id}/cancel` | [미결] | 상태별 취소 정책 확정 후 |

### 5.2 요청 생성 — POST `/stock-check-requests`

```json
{
  "productVariantId": 1002,
  "storageLocationId": 4,
  "priority": "URGENT"
}
```

```json
{
  "id": 3001,
  "productVariantId": 1002,
  "storageLocationId": 4,
  "requestedBy": 101,
  "requestingStoreId": 1,
  "responsibleStoreId": 1,
  "priority": "URGENT",
  "status": "REQUESTED",
  "assignedTo": null,
  "requestedAt": "2026-11-05T07:20:00Z"
}
```

**서버 결정 규칙:**

1. `@AuthenticationPrincipal`에서 요청자 ID 확보. ACTIVE와 **실제 체크인·WORKING 상태**를 검증.
2. 옵션/위치 존재·활성 여부 검증; [권장] 위치를 말단 위치로 제한.
3. `requestingStoreId = CurrentWorkStatus.currentStoreId`를 스냅샷으로 저장.
4. INTERNAL 위치면 `responsibleStoreId = StorageLocation.storeId`, SHARED_EXTERNAL이면 `responsibleStoreId = requestingStoreId`.
5. 요청 REQUESTED를 저장하고 해당 담당 매장의 현재 WORKING 직원에게 Notification/Delivery 작업 생성.
6. DB 커밋 후 Worker가 푸시 전송. 별도 미수락 에스컬레이션 스케줄러 대상 등록.

### 5.3 동시 수락 — POST `/stock-check-requests/{id}/accept`

**Request:** 본문 없음. 로그인한 직원만 담당자로 지정한다.

```sql
UPDATE stock_check_requests
SET status = 'ACCEPTED',
    assigned_to = :userId,
    accepted_at = CURRENT_TIMESTAMP(6)
WHERE id = :requestId
  AND responsible_store_id = :currentStoreId
  AND status = 'REQUESTED'
  AND assigned_to IS NULL;
```

- 변경 행 수 **1**: 성공, 200 + 요청 DTO.
- 변경 행 수 **0**: 요청 없음 404, 담당 매장/자격 위반 403, 이미 다른 직원 수락 409 등 사후 안전하게 구분.
- DB는 동일 행 UPDATE 경쟁을 직렬화한다. 요청 상태를 SELECT한 뒤 단순히 JPA `save()`하는 로직만으로는 충분하지 않다.
- **현재 근무 자격 확인과 UPDATE 사이**에 체크아웃·지원 이동이 끼지 않게 **직원 User 행 잠금/공통 직렬화 규칙** 등으로 보호한다. 동시에 발생하는 일은 DB 통합 테스트로 확인.
- 같은 직원의 재시도 멱등 정책은 [권장]이고, 이미 COMPLETED된 요청을 과거 수락 성공으로 되돌려 표현하지 않는다.

### 5.4 확인 완료 — POST `/stock-check-requests/{id}/complete`

```json
{
  "verifiedQuantity": 12,
  "note": "143 창고 안쪽 선반에서 확인"
}
```

**200 OK** (요약 예시)

```json
{
  "requestId": 3001,
  "status": "COMPLETED",
  "verification": {
    "id": 4001,
    "productVariantId": 1002,
    "storageLocationId": 4,
    "verifiedQuantity": 12,
    "verifiedBy": 102,
    "verifiedAt": "2026-11-05T07:30:00Z"
  },
  "stockRecord": {
    "productVariantId": 1002,
    "storageLocationId": 4,
    "lastVerifiedQuantity": 12,
    "lastVerifiedAt": "2026-11-05T07:30:00Z"
  }
}
```

**동일 MySQL 트랜잭션에서:**

1. 요청 행 잠금 및 `ACCEPTED`, assignee==본인, 실제 currentStore==responsibleStoreId 확인.
2. 요청 중복 완료 여부/이전 StockVerification 존재 여부 검사.
3. 서버 확정 시각과 수량 `>=0`으로 StockVerification INSERT.
4. `(variantId,locationId)` UNIQUE인 StockRecord를 안전하게 UPSERT/UPDATE. 최초 동시 생성 시 UNIQUE 충돌 해결; 기존 행은 충돌 방지(행 잠금/버전/조건부 UPDATE).
5. StockCheckRequest를 COMPLETED로 변경하고 completedAt 기록.
6. 요청자에 대한 Notification과 NotificationDelivery INSERT.
7. COMMIT. Worker의 외부 푸시 호출은 **트랜잭션 밖**에서 실행.

**정합성 조건:** 확인 이력 생성만 성공하고 마지막값 갱신이 실패했다면 모두 롤백. 같은 요청으로 동시에 두 완료 요청이 들어오면 `stock_verifications.stock_check_request_id UNIQUE` 및 요청 상태 검사로 하나만 완료. 마지막 확인값의 ‘최신’은 서버가 성공 처리한 순서라는 MVP 정의가 있으며, 실제 창고 관찰 시점이 다르다면 향후 별도 `observedAt`이 필요하다.

### 5.5 미수락 에스컬레이션 [권장]

```sql
SELECT id
FROM stock_check_requests
WHERE status = 'REQUESTED'
  AND escalated_at IS NULL
  AND requested_at <= :threshold;
```

스케줄러가 대상을 잠금/조건부 UPDATE 후 한 번만 부매니저 알림을 생성한다. 미수락 경과 시간(예: 5분, 10분)은 **확정하지 않음**. 요청 완료·취소 후 늦은 알림을 보내지 않도록 전송 시점에도 검증한다.

---

## 6. SupportRequest · StaffAssignment

### 6.1 엔드포인트

| Method | Endpoint | 권한 | 기능 |
|---|---|---|---|
| POST | `/support-requests` | 지원받는 매장 AM | 지원 요청 생성 |
| GET | `/support-requests` | 양측 매장 AM·관련 직원·MANAGER | 관계 범위 내 목록 |
| GET | `/support-requests/{id}` | 양측 관계자 | 요청/배정 상세 |
| POST | `/support-requests/{id}/approve` | 직원 보내는 매장 AM | 요청 승인 |
| POST | `/support-requests/{id}/reject` | 직원 보내는 매장 AM | 요청 거절 |
| POST | `/support-requests/{id}/assignments` | 직원 보내는 매장 AM | 여러 직원 배정 |
| POST | `/support-requests/{id}/cancel` | [미결] | 미진행 요청 취소 |
| POST | `/staff-assignments/{id}/arrival` | 배정 직원 본인 | 지원 매장 도착 확인 |
| POST | `/staff-assignments/{id}/return-start` | 지원받는 매장 AM | 지원 종료·복귀 지시 |
| POST | `/staff-assignments/{id}/return-confirm` | 배정 직원 본인 | 원 매장 복귀 확인 |
| POST | `/staff-assignments/{id}/cancel` | [미결] | 도착 전 배정 취소 |
| GET | `/staff-assignments/me` | 본인 | 본인 지원 이력/상태 |

### 6.2 지원 요청 — POST `/support-requests`

```json
{
  "targetStoreId": 2,
  "requestedCount": 2,
  "reason": "경기 시작 전 고객 증가로 추가 인력 필요"
}
```

```json
{
  "id": 5001,
  "requestingStoreId": 1,
  "targetStoreId": 2,
  "requestedCount": 2,
  "status": "REQUESTED",
  "requestedAt": "2026-11-05T07:30:00Z"
}
```

`requestingStoreId`와 `requestedBy`는 현재 호출자(AM)의 기본 관리 매장을 서버가 결정한다. 이 요청을 받은 매장의 여러 부매니저에게 알림을 생성한다.

### 6.3 승인·거절 경쟁

```sql
UPDATE support_requests
SET status = 'APPROVED',
    responded_by = :managerId,
    responded_at = CURRENT_TIMESTAMP(6)
WHERE id = :requestId
  AND status = 'REQUESTED';
```

승인/거절이 동시에 실행되어도 **한 명만 상태 변경 성공**. 응답자 `homeStoreId==targetStoreId` 검증이 필수. 승인 200, 이미 응답한 요청은 409. 거절은 `{reason}`을 받고 `REJECTED`로 상태를 변경한다.

### 6.4 다중 직원 배정 — POST `/support-requests/{id}/assignments`

```json
{
  "userIds": [103, 104]
}
```

```json
{
  "supportRequestId": 5001,
  "requestStatus": "IN_PROGRESS",
  "assignments": [
    {"id": 7001, "userId": 103, "fromStoreId": 2, "toStoreId": 1, "status": "ASSIGNED"},
    {"id": 7002, "userId": 104, "fromStoreId": 2, "toStoreId": 1, "status": "ASSIGNED"}
  ]
}
```

검증: ① 요청 APPROVED/IN_PROGRESS ② 호출자 AM(targetStoreId) ③ userIds 중복 없음 ④ 요청 인원 초과 없음 ⑤ 모든 대상 직원 ACTIVE, targetStore WORKING, 활성 Attendance 존재 ⑥ 동일 직원 활성 다른 StaffAssignment 없음 ⑦ 전원 검증 성공 시 일괄 INSERT.

**동시성:** SupportRequest 행 잠금으로 배정 정원 보호 + User 행을 ID 오름차순으로 잠금해 직원별 중복 활성 배정 방지. `UNIQUE(support_request_id,user_id)`만으로 서로 다른 요청에 같은 직원 배정되는 문제를 해결할 수 없다. 직원 체크아웃·재고 요청 수락·지원 이동과 동일한 잠금/자격 갱신 규칙을 공유한다.

### 6.5 도착·지원 종료·복귀

| API | 상태 전이 | 처리 주체 | 변경 결과 |
|---|---|---|---|
| `POST /staff-assignments/{id}/arrival` | ASSIGNED→ARRIVED | 배정 직원 | arrivedAt=서버 시각, 현재 매장=toStore |
| `POST /staff-assignments/{id}/return-start` | ARRIVED→RETURNING | 지원받는 매장 AM | returnStartedAt/by 기록, 현재 매장=null |
| `POST /staff-assignments/{id}/return-confirm` | RETURNING→RETURNED | 배정 직원 | returnedAt 기록, 현재 매장=fromStore |

직원이 도착·복귀 확인을 누른 기록이며 GPS로 실제 도착을 검증하는 것은 아니다. REQUEST의 COMPLETED 전이는 필요한 배정 전원이 정상 복귀한 후 수행. **일부 충원 상태의 조기 종료는 [미결]**.

### 6.6 직원의 현재 근무 매장

`GET /stores/{id}/working-staff`의 조회는 `CurrentWorkStatusService` 공통 계산을 사용한다. `ASSIGNED`와 `RETURNING` 중에는 양쪽 매장에서 제외. 같은 정책으로 재고 확인 알림 수신자도 결정한다.

---

## 7. Notification · PushSubscription · SSE

### 7.1 엔드포인트

| Method | Endpoint | 권한 | 기능 |
|---|---|---|---|
| GET | `/notifications` | 본인 | 알림 목록(커서/페이지) |
| GET | `/notifications/unread-count` | 본인 | 미읽음 개수 |
| PATCH | `/notifications/{id}/read` | 수신자 | 개별 읽음 |
| PATCH | `/notifications/read-all` | 본인 | 기존 알림 전체 읽음 |
| GET | `/notifications/stream` | 본인 | SSE 실시간 변경 통지 |
| GET | `/push-subscriptions/vapid-public-key` | ACTIVE | 클라이언트용 VAPID 공개키 [권장 추가] |
| POST | `/push-subscriptions` | ACTIVE | 브라우저 푸시 구독 등록 |
| DELETE | `/push-subscriptions/{id}` | 본인 | 구독 해제/비활성화 |

### 7.2 알림 목록

```json
{
  "content": [
    {
      "id": 9001,
      "type": "STOCK_CHECK_REQUESTED",
      "title": "새 재고 확인 요청",
      "body": "143 창고의 홈 유니폼 L을 확인해 주세요.",
      "targetType": "STOCK_CHECK_REQUEST",
      "targetId": 3001,
      "readAt": null,
      "createdAt": "2026-11-05T07:30:00Z"
    }
  ],
  "nextCursor": null,
  "hasNext": false
}
```

`GET /notifications/unread-count`는 `{ "unreadCount": 4 }`를 반환한다. 알림 읽음은 업무 수락·승인/완료와 별개. 개인 읽음 처리 SQL은 `WHERE id=:id AND recipient_id=:me AND read_at IS NULL`로 최초 시각을 보존하고, 0행이면 존재·소유 여부를 확인해 반환한다.

### 7.3 Push 구독 등록

```json
{
  "endpoint": "https://push.example.com/sub/example",
  "keys": {"p256dh": "EXAMPLE_PUBLIC_KEY", "auth": "EXAMPLE_AUTH_KEY"}
}
```

- `userId`는 요청 본문에서 받지 않는다. 인증 사용자에게 연결한다.
- 동일 사용자·동일 endpoint 재등록 시 기존 구독 갱신 [권장]; 다른 사용자 소유 endpoint는 자동 이전 금지.
- 서버는 endpoint/키 원문을 로그·일반 응답에 반환하지 않는다.
- 로그아웃 시 해당 기기 구독을 어떻게 해제할지는 초기 보안 정책과 함께 일관되게 정한다.
- DELETE는 본인 구독만 해제하며 성공 시 204.

### 7.4 SSE — GET `/notifications/stream`

```text
id: 9001
event: notification-created
data: {"notificationId":9001,"type":"STOCK_CHECK_REQUESTED"}

```

- 서버는 **변경 사실과 ID만** 보내고, 브라우저는 REST로 실제 알림/업무 최신 상태 재조회.
- `EventSource` 기본 API는 Authorization 요청 헤더 추가가 어려우므로, Access JWT 헤더를 사용하는 **fetch 기반 SSE 클라이언트** 또는 검증된 별도 인증 설계를 선택.
- URL 쿼리 파라미터에 Access Token을 넣지 않는다.
- 재연결 후 알림함/대기 업무를 반드시 새로 조회한다. SSE는 영구 큐가 아니다.
- PWA Web Push는 Service Worker 구독/권한이 필요하고 iOS 일부 환경에서는 홈 화면 설치 등의 지원 제약이 있으므로 푸시가 없어도 업무는 동작해야 한다.

### 7.5 Delivery Worker (내부 서비스, 공개 API 없음)

1. 업무 커밋 안에서 Notification + 활성 PushSubscription별 NotificationDelivery(PENDING) 생성.
2. 별도 스케줄러가 `status=PENDING`과 `nextAttemptAt<=now`를 대상으로 짧은 DB 트랜잭션에서 PROCESSING 선점, `lockedUntil`·`claimToken` 기록.
3. 잠금 해제 후 외부 Web Push 서비스 호출. 성공 시 `SENT`(제공자 수락)를 기록.
4. 타임아웃·5xx 등 일시 오류는 재시도 예약, 404/410 무효 endpoint는 구독 비활성화 [권장].
5. 재시도 기준안: 실패 후 10초/30초/120초, 총 4회. 실제 운영 상황에 따라 조정.
6. `claimToken`이 더 이상 일치하지 않는 오래된 Worker는 새 Worker의 처리 결과를 덮어쓰지 못한다. 전송 중단/복구 후 **중복 발송 가능성은 남는다**.

---

## 8. 공통 주요 오류 코드

| 코드 | HTTP | 예시 |
|---|---|---|
| `VALIDATION_FAILED` | 400 | 잘못된 시각·이메일·수량 |
| `DUPLICATE_EMAIL` | 409 | 중복 가입 이메일 |
| `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 401 | 유효하지 않은 Access 토큰 |
| `ACCOUNT_PENDING` / `ACCOUNT_REJECTED` / `ACCOUNT_INACTIVE` | 403 | 계정 상태 제한 |
| `STORE_ACCESS_DENIED` | 403 | 소속 매장이 아닌 부매니저의 승인 시도 |
| `USER_NOT_FOUND` / `STORE_NOT_FOUND` | 404 | 사용자·매장 없음 |
| `WORK_SCHEDULE_NOT_FOUND` / `WORK_SCHEDULE_CONFLICT` | 404 / 409 | 일정 없음 / 중복 날짜 |
| `ALREADY_CHECKED_IN` / `ALREADY_CHECKED_OUT` | 409 | 출퇴근 상태 충돌 |
| `ATTENDANCE_ACTIVE_SUPPORT` | 409 | 지원 중 체크아웃 |
| `ATTENDANCE_NOT_CHECKED_OUT` | 409 | 체크아웃 이전 승인 |
| `ATTENDANCE_ALREADY_APPROVED` | 409 | 승인 후 임의 변경 |
| `INVALID_CHECKOUT_TIME_UNIT` | 400 | 18:15 등 30분 단위 아님 |
| `PRODUCT_NOT_FOUND` / `PRODUCT_VARIANT_NOT_FOUND` | 404 | 상품/옵션 없음 |
| `STORAGE_LOCATION_NOT_FOUND` | 404 | 위치 없음 |
| `STOCK_REQUEST_NOT_FOUND` | 404 | 재고 확인 요청 없음 |
| `STOCK_REQUEST_ALREADY_CLAIMED` | 409 | 다른 직원이 선점 |
| `STOCK_REQUEST_INVALID_STATUS` | 409 | 현재 상태에서 전이 불가 |
| `STOCK_REQUEST_STORE_MISMATCH` | 403 | 담당 매장 근무 직원이 아님 |
| `INVALID_VERIFIED_QUANTITY` | 400 | 수량 음수 |
| `STOCK_VERIFICATION_CONFLICT` | 409 | 완료 결과 중복·최신값 갱신 충돌 |
| `SUPPORT_REQUEST_NOT_FOUND` | 404 | 지원 요청 없음 |
| `SUPPORT_REQUEST_ALREADY_RESPONDED` | 409 | 승인/거절 이미 처리됨 |
| `SUPPORT_REQUEST_CAPACITY_EXCEEDED` | 409 | 배정 정원 초과 |
| `SUPPORT_ASSIGNMENT_DUPLICATED` | 409 | 직원 활성 중복 배정 |
| `SUPPORT_ASSIGNMENT_INVALID_STATUS` | 409 | 잘못된 배정 상태 전이 |
| `SUPPORT_ASSIGNMENT_NOT_OWNED` | 403 | 직원 본인이 아닌 이동 확인 |
| `NOTIFICATION_NOT_FOUND` | 404 | 알림 없음 |
| `NOTIFICATION_ACCESS_DENIED` | 403 | 타인의 알림 |
| `PUSH_SUBSCRIPTION_CONFLICT` | 409 | 다른 계정의 브라우저 구독 |
| `NOTIFICATION_STREAM_UNAVAILABLE` | 503 | SSE 제공 불가 |

오류 코드 이름은 코드 구현 전에 `ErrorCode` enum과 1:1로 통일한다. **403/404 정보 노출 정책**도 API 전반에서 일관되게 적용한다.

## 9. API 통합 테스트의 핵심 시나리오

| 시나리오 | 테스트 기대값 |
|---|---|
| 가입 요청에 role/homeStoreId 전송 | 무시/거절, 사용자 임의 권한 상승 불가 |
| 경기일 `endAt=null`, 비경기일 `endAt=19:00` | 양쪽 정상 생성 |
| 같은 직원 같은 근무일 동시 일정 생성 | 하나만 성공, 하나 409 |
| 직원 실제 18:12 체크아웃/운영 18:00 승인 | 원본과 운영 시각 모두 유지 |
| 이미 지원 매장으로 이동 중인 직원 체크아웃 | 409 |
| 공용 143 창고 요청 | 요청자 현재 근무 매장을 responsibleStoreId로 저장 |
| 같은 재고 요청을 직원 20명이 동시에 수락 | 정확히 1명 성공 |
| 서로 다른 지원 요청에 같은 직원 동시 배정 | 활성 배정 1개만 남음 |
| 요청 정원 1명인데 부매니저 둘이 동시 배정 | 정원 1명 준수 |
| 확인 완료 중 StockRecord 갱신 예외 | 전체 트랜잭션 롤백 |
| Redis 다운 중 상품 조회 | 상품 조회 200, 최근 조회는 유예 |
| 푸시 서버 다운 중 재고 완료 | 업무 커밋 완료, Delivery 재시도 |
| 알림 타인 접근/매장 범위 우회 | 403/404 정책대로 거부 |

**구현 후 업데이트할 항목:** 실제 요청 DTO 필드와 Bean Validation, 생성 API의 재시도 키 정책, 목록 페이지/커서 기준, 취소·예외 업무 API, Springdoc(OpenAPI) 경로 검증 결과. 세부 미결은 [06-decisions.md](06-decisions.md)에 유지한다.


## 초기 관리자 생성 및 인가 구현 메모 (2026-11-10)

초기 MANAGER 생성은 HTTP API가 아니다. README의 환경변수 기반 비웹 절차에서 한 번만 수행하며 기존 가입자를 승격하지 않는다. V3 잠금/완료 기록과 사용자 생성을 한 트랜잭션으로 처리한다. 이후 관리자 추가·승인은 별도 정책/후속 작업이다.
UserAuthorizationService는 ACTIVE 확인, 총괄 전용 명령, 담당 매장 부매니저 전용 명령을 각각 검증한다. null/없는 인증 ID는 UNAUTHENTICATED, 비활성 등은 ACCOUNT_* 오류, 총괄 역할 부족은 ACCESS_DENIED, 담당 매장/역할 불일치는 STORE_ACCESS_DENIED다. MANAGER는 부매니저 전용 명령을 자동 우회하지 않는다.
이 서비스의 입력 ID는 향후 검증된 principal에서 전달해야 하며 요청 본문의 userId로 인증을 대체하지 않는다. 아직 로그인/JWT·업무 API와 연결한 상태는 아니다. 부트스트랩 거부 BOOTSTRAP_NOT_ALLOWED는 내부 실행 오류이며 새로운 공개 엔드포인트는 추가하지 않았다.


## Store 관리 API 구현 계약 (2026-11-10)

### 인증과 권한

- Spring Security Authentication이 인증 완료 상태이고 principal이 UserPrincipal이어야 한다. userId는 이 principal에서만 추출하며 요청 본문/쿼리/임의 헤더로 요청자를 선택하지 않는다.
- 서비스에서 DB의 ACTIVE 상태를 확인한다. 조회는 모든 ACTIVE 역할, 생성·수정·비활성화는 ACTIVE MANAGER만 허용한다. 부매니저의 소속 매장도 공통 마스터 변경 권한을 부여하지 않는다.
- Store 전용 STATELESS 보안 체인은 Basic/Form 로그인을 제공하지 않으며 기존 기본 로그인 사용자·검증되지 않은 Bearer 문자열을 사용자로 변환하지 않는다. 로그인/JWT 제공자는 후속 구현이다. 테스트에서는 서버 측 테스트 인증 객체를 주입하고 실제 필터·DB·서비스를 통과한다.
- CSRF 검사를 유지한다. 변경 요청에 CSRF 토큰이 없거나 잘못되면 ACCESS_DENIED/403이다. 그 외 인증 정보가 없거나 유효한 사용자 식별 계약이 아니면 UNAUTHENTICATED/401이며, DB의 비활성 상태는 ACCOUNT_* /403이다. 필터와 MVC 오류 모두 ApiError를 사용한다.

### 목록 — GET `/api/v1/stores`

| 파라미터 | 기본값 | 규칙 |
|---|---|---|
| page | 0 | 0 이상인 정수, 0부터 시작 |
| size | 20 | 1~100 |
| active | true | true는 활성, false는 비활성 매장 |

정렬은 항상 id ASC다. sort 파라미터로 변경할 수 없다. JPA offset 한계 때문에 page × size가 Integer.MAX_VALUE를 초과하면 400이다. 범위를 벗어난 페이지는 content가 빈 200 응답이며 totalElements는 필터 조건의 전체 개수다. 활성·비활성 전체 동시 조회 옵션은 이번 범위에 없다.

```json
{
  "content": [
    {"id": 1, "code": "SHOP-A", "name": "A매장", "active": true,
     "createdAt": "2026-11-10T01:02:03.123456Z", "updatedAt": "2026-11-10T01:02:03.123456Z"}
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### 상세·생성·수정·비활성화

- GET `/stores/{id}`: 200 StoreResponse. 비활성 매장도 조회할 수 있다.
- POST `/stores`: `{code,name}`. code는 공백만 아닌 최대 40자, name은 공백만 아닌 최대 120자다. 201 StoreResponse 및 Location `/api/v1/stores/{id}`. code/name의 앞뒤 공백을 자동 제거하거나 대소문자를 변경하지 않는다. 코드 중복 비교는 기존 MySQL collation을 따른다.
- PATCH `/stores/{id}`: `{name}`만 허용, 200 StoreResponse. code·active·역할·사용자 ID 등 미허용 JSON 필드는 400이다. 비활성 매장의 이름도 변경할 수 있으며 재활성화하지 않는다.
- PATCH `/stores/{id}/deactivate`: 요청 본문이 필요 없으며 active=false로 변경하고 200 StoreResponse를 반환한다. 행을 삭제하지 않는다. 이미 비활성이면 updatedAt을 보존한다.
- StoreResponse는 id/code/name/active/createdAt/updatedAt의 6개 필드다. 양수가 아닌 ID·입력 오류는 VALIDATION_FAILED/400, 없는 매장은 STORE_NOT_FOUND/404, 중복 코드는 DUPLICATE_STORE_CODE/409다.
- 생성 시 중복 사전 검사와 DB UNIQUE를 모두 사용해 동시 생성 충돌도 409로 처리한다. 기존 V1~V3 및 DB 스키마는 변경하지 않는다.
- 이름 변경·비활성화는 서비스 트랜잭션에서 대상 Store를 PESSIMISTIC_WRITE(SELECT FOR UPDATE)로 읽고 변경한다. 같은 매장의 변경은 잠금 해제까지 기다리며 다른 매장 변경과 일반 조회는 계속 가능하다. 자동 재시도나 무기한 대기를 보장하지 않으며 잠금 대기 시간은 MySQL 설정을 따른다.
