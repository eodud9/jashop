package com.jashop.backend.auth.api;

import com.jashop.backend.auth.api.dto.LoginResponse;
import com.jashop.backend.auth.session.application.AuthSessionService;
import com.jashop.backend.global.error.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionController {
    private final AuthSessionService sessions;
    private final RefreshCookies cookies;

    public SessionController(AuthSessionService sessions, RefreshCookies cookies) {
        this.sessions = sessions;
        this.cookies = cookies;
    }

    public record CsrfResponse(String token, String headerName) {
        @Override public String toString() { return "CsrfResponse[redacted]"; }
    }

    @GetMapping("/api/v1/auth/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken csrf) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CsrfResponse(csrf.getToken(), csrf.getHeaderName()));
    }

    @PostMapping("/api/v1/auth/refresh")
    public ResponseEntity<LoginResponse> refresh(@CookieValue(name = RefreshCookies.NAME, required = false) String refresh,
            HttpServletResponse response) {
        var tokens = sessions.refresh(refresh);
        // 서비스 트랜잭션 커밋 이후에 실패 응답을 만든다. 재사용 폐기가 롤백되지 않는다.
        if (tokens.error() != null) {
            cookies.clear(response);
            throw new BusinessException(tokens.error());
        }
        cookies.write(response, tokens);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens.response());
    }

    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = RefreshCookies.NAME, required = false) String refresh,
            HttpServletResponse response) {
        sessions.logout(refresh);
        cookies.clear(response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
