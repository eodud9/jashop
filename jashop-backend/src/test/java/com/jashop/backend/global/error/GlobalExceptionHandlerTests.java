package com.jashop.backend.global.error;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GlobalExceptionHandlerTests.ErrorTestController.class)
@AutoConfigureMockMvc(addFilters = false) // MVC 오류 계약만 검증하며 운영 보안 설정은 변경하지 않는다.
@Import({GlobalExceptionHandlerTests.ErrorTestController.class, GlobalExceptionHandler.class,
        GlobalExceptionHandlerTests.FixedClockConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTests {

    @Autowired
    MockMvc mvc;

    @AfterEach
    void clearTraceContext() {
        MDC.remove("traceId");
    }

    @ParameterizedTest
    @CsvSource({
            "INVALID_VERIFIED_QUANTITY,400", "UNAUTHENTICATED,401", "STORE_ACCESS_DENIED,403",
            "STORE_NOT_FOUND,404", "STOCK_REQUEST_ALREADY_CLAIMED,409", "NOTIFICATION_STREAM_UNAVAILABLE,503"
    })
    void businessExceptionsFollowApiContract(ErrorCode code, int status) throws Exception {
        var result = mvc.perform(get("/__test/errors/business/{code}", code)
                        .header("X-Trace-Id", "untrusted-client-value"))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*", hasSize(5)))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.message").value(code.getMessage()))
                .andExpect(jsonPath("$.timestamp").value("2026-11-05T09:22:00Z"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).containsPattern("\"traceId\":\"[0-9a-f-]{36}\"");
        assertThat(body).doesNotContain("untrusted-client-value");
    }

    @Test
    void beanValidationIncludesFieldMessageWithoutRejectedValue() throws Exception {
        mvc.perform(post("/__test/errors/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"private-password\",\"quantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quantity"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("수량은 0 이상이어야 합니다."))
                .andExpect(content().string(not(containsString("private-password"))))
                .andExpect(content().string(not(containsString("rejectedValue"))));
    }

    @Test
    void missingBodyFieldIsValidated() throws Exception {
        mvc.perform(post("/__test/errors/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"quantity\":\"private-input\"}"})
    void malformedJsonDoesNotExposeParserDetails(String body) throws Exception {
        mvc.perform(post("/__test/errors/body").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(content().string(not(containsString("private-input"))));
    }

    @ParameterizedTest
    @CsvSource({"/__test/errors/parameter", "/__test/errors/parameter?quantity=invalid"})
    void missingAndInvalidParametersAreBadRequests(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void methodParameterValidationIsBadRequest() throws Exception {
        mvc.perform(get("/__test/errors/parameter").param("quantity", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quantity"));
    }

    @Test
    void unknownPathRemainsNotFound() throws Exception {
        mvc.perform(get("/__test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void unsupportedMethodPreservesAllowHeader() throws Exception {
        mvc.perform(get("/__test/errors/body"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedMediaTypeRemains415() throws Exception {
        mvc.perform(post("/__test/errors/body").contentType(MediaType.TEXT_PLAIN).content("private-input"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unacceptableResponseRemains406() throws Exception {
        mvc.perform(get("/__test/errors/json-only").accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    void invalidReturnValueIsServerError() throws Exception {
        mvc.perform(get("/__test/errors/invalid-return"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void unexpectedExceptionIsSanitizedAndCorrelatedWithLog(CapturedOutput output) throws Exception {
        MDC.put("traceId", "server-trace-123");
        mvc.perform(get("/__test/errors/unexpected").header("X-Trace-Id", "untrusted-client-value"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
                .andExpect(jsonPath("$.traceId").value("server-trace-123"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(content().string(not(containsString("db-password-secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
        assertThat(output.getOut()).contains("traceId=server-trace-123", "java.lang.IllegalStateException")
                .doesNotContain("db-password-secret");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-05T09:22:00Z"), ZoneOffset.UTC);
        }
    }

    // 테스트 소스에만 존재하며 배포되는 업무 API가 아니다.
    @RestController
    static class ErrorTestController {
        @GetMapping("/__test/errors/business/{code}")
        void business(@PathVariable ErrorCode code) {
            throw new BusinessException(code);
        }

        @PostMapping("/__test/errors/body")
        void body(@Valid @RequestBody TestRequest request) {
        }

        @GetMapping("/__test/errors/parameter")
        void parameter(@RequestParam @Min(0) int quantity) {
        }

        @GetMapping(value = "/__test/errors/json-only", produces = MediaType.APPLICATION_JSON_VALUE)
        TestRequest jsonOnly() {
            return new TestRequest("example", 0);
        }

        @GetMapping("/__test/errors/unexpected")
        void unexpected() {
            throw new IllegalStateException("db-password-secret");
        }

        @Min(0)
        @GetMapping("/__test/errors/invalid-return")
        int invalidReturn() {
            return -1;
        }
    }

    record TestRequest(@NotBlank String password, @Min(value = 0, message = "수량은 0 이상이어야 합니다.") int quantity) {
    }
}
