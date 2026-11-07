package com.storeflow.backend.global.error;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TRACE_ID_ATTRIBUTE = GlobalExceptionHandler.class.getName() + ".traceId";

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(BusinessException exception, WebRequest request) {
        ErrorCode code = exception.getErrorCode();
        return response(code, code.getStatus(), new HttpHeaders(), List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception exception, WebRequest request) {
        // 예외 메시지에는 SQL, 토큰, 비밀번호 등이 포함될 수 있어 타입과 추적 ID만 기록한다.
        log.error("Unhandled exception: traceId={}, type={}", traceId(request), exception.getClass().getName());
        return response(ErrorCode.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_SERVER_ERROR.getStatus(),
                new HttpHeaders(), List.of(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        List<ApiError.FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return response(ErrorCode.VALIDATION_FAILED, status, headers, errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (exception.isForReturnValue()) {
            return handleExceptionInternal(exception, null, headers, status, request);
        }
        List<ApiError.FieldError> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream().map(error -> {
                    String name = result.getMethodParameter().getParameterName();
                    return new ApiError.FieldError(name != null ? name : "arg" +
                            result.getMethodParameter().getParameterIndex(), error.getDefaultMessage());
                }))
                .toList();
        return response(ErrorCode.VALIDATION_FAILED, status, headers, errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ErrorCode code = switch (status.value()) {
            case 400 -> ErrorCode.VALIDATION_FAILED;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_SERVER_ERROR : ErrorCode.REQUEST_FAILED;
        };
        if (status.is5xxServerError()) {
            log.error("MVC exception: traceId={}, type={}", traceId(request), exception.getClass().getName());
        }
        // Spring이 결정한 HTTP 상태 및 Allow/Accept 등의 헤더는 보존한다.
        return response(code, status, headers, List.of(), request);
    }

    private ResponseEntity<Object> response(ErrorCode code, HttpStatusCode status, HttpHeaders headers,
            List<ApiError.FieldError> fieldErrors, WebRequest request) {
        ApiError body = new ApiError(code.name(), code.getMessage(), Instant.now(clock), traceId(request), fieldErrors);
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.addAll(headers);
        // Accept 불일치(406)에서도 공통 오류 계약을 JSON으로 반환한다.
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body, responseHeaders, status);
    }

    private String traceId(WebRequest request) {
        String existing = (String) request.getAttribute(TRACE_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (existing != null) {
            return existing;
        }
        String tracingId = MDC.get("traceId");
        String id = tracingId != null && !tracingId.isBlank() ? tracingId : UUID.randomUUID().toString();
        request.setAttribute(TRACE_ID_ATTRIBUTE, id, RequestAttributes.SCOPE_REQUEST);
        return id;
    }
}
