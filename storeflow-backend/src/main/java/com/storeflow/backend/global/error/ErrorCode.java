package com.storeflow.backend.global.error;

import org.springframework.http.HttpStatus;

/** API 명세의 오류 코드와 HTTP 상태를 관리한다. 업무별 검증은 각 기능에서 구현한다. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    REQUEST_FAILED(HttpStatus.BAD_REQUEST, "요청을 처리할 수 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "요청한 응답 형식을 지원하지 않습니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 서비스를 사용할 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "인증 토큰이 만료되었습니다."),
    ACCOUNT_PENDING(HttpStatus.FORBIDDEN, "승인 대기 중인 계정입니다."),
    ACCOUNT_REJECTED(HttpStatus.FORBIDDEN, "승인이 거절된 계정입니다."),
    ACCOUNT_INACTIVE(HttpStatus.FORBIDDEN, "비활성 계정입니다."),
    STORE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 매장에 접근할 권한이 없습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "매장을 찾을 수 없습니다."),
    WORK_SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "근무 일정을 찾을 수 없습니다."),
    WORK_SCHEDULE_CONFLICT(HttpStatus.CONFLICT, "근무 일정이 충돌합니다."),
    ALREADY_CHECKED_IN(HttpStatus.CONFLICT, "이미 체크인했습니다."),
    ALREADY_CHECKED_OUT(HttpStatus.CONFLICT, "이미 체크아웃했습니다."),
    ATTENDANCE_ACTIVE_SUPPORT(HttpStatus.CONFLICT, "지원 중에는 체크아웃할 수 없습니다."),
    ATTENDANCE_NOT_CHECKED_OUT(HttpStatus.CONFLICT, "체크아웃 후 승인할 수 있습니다."),
    ATTENDANCE_ALREADY_APPROVED(HttpStatus.CONFLICT, "이미 승인된 출퇴근 기록입니다."),
    INVALID_CHECKOUT_TIME_UNIT(HttpStatus.BAD_REQUEST, "운영 퇴근 시각은 30분 단위여야 합니다."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    PRODUCT_VARIANT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품 옵션을 찾을 수 없습니다."),
    STORAGE_LOCATION_NOT_FOUND(HttpStatus.NOT_FOUND, "보관 위치를 찾을 수 없습니다."),
    STOCK_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "재고 확인 요청을 찾을 수 없습니다."),
    STOCK_REQUEST_ALREADY_CLAIMED(HttpStatus.CONFLICT, "이미 다른 직원이 수락한 요청입니다."),
    STOCK_REQUEST_INVALID_STATUS(HttpStatus.CONFLICT, "현재 재고 확인 요청 상태에서는 처리할 수 없습니다."),
    STOCK_REQUEST_STORE_MISMATCH(HttpStatus.FORBIDDEN, "담당 매장에서 근무 중인 직원만 처리할 수 있습니다."),
    INVALID_VERIFIED_QUANTITY(HttpStatus.BAD_REQUEST, "확인 수량은 0 이상이어야 합니다."),
    STOCK_VERIFICATION_CONFLICT(HttpStatus.CONFLICT, "재고 확인 결과가 충돌합니다."),
    SUPPORT_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "지원 요청을 찾을 수 없습니다."),
    SUPPORT_REQUEST_ALREADY_RESPONDED(HttpStatus.CONFLICT, "이미 응답한 지원 요청입니다."),
    SUPPORT_REQUEST_CAPACITY_EXCEEDED(HttpStatus.CONFLICT, "지원 요청 인원을 초과했습니다."),
    SUPPORT_ASSIGNMENT_DUPLICATED(HttpStatus.CONFLICT, "이미 활성 지원 배정이 있습니다."),
    SUPPORT_ASSIGNMENT_INVALID_STATUS(HttpStatus.CONFLICT, "현재 지원 배정 상태에서는 처리할 수 없습니다."),
    SUPPORT_ASSIGNMENT_NOT_OWNED(HttpStatus.FORBIDDEN, "본인의 지원 배정만 처리할 수 있습니다."),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),
    NOTIFICATION_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 알림에 접근할 권한이 없습니다."),
    PUSH_SUBSCRIPTION_CONFLICT(HttpStatus.CONFLICT, "다른 계정의 브라우저 구독입니다."),
    NOTIFICATION_STREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "알림 스트림을 사용할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
