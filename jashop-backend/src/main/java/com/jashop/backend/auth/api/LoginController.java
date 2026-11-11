package com.jashop.backend.auth.api;

import com.jashop.backend.auth.api.dto.LoginRequest;
import com.jashop.backend.auth.api.dto.LoginResponse;
import com.jashop.backend.auth.application.LoginService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LoginController {
    private final LoginService login;
    private final RefreshCookies cookies;

    public LoginController(LoginService login, RefreshCookies cookies) {
        this.login = login;
        this.cookies = cookies;
    }

    @PostMapping(value = "/api/v1/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        var tokens = login.login(request);
        cookies.write(response, tokens);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
                .body(tokens.response());
    }
}
