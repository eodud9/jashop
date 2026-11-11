package com.jashop.backend.user.api;

import com.jashop.backend.auth.api.dto.AuthenticatedUserResponse;
import com.jashop.backend.global.security.UserPrincipal;
import com.jashop.backend.user.application.UserAuthorizationService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CurrentUserController {
    private final UserAuthorizationService authorization;

    public CurrentUserController(UserAuthorizationService authorization) {
        this.authorization = authorization;
    }

    @GetMapping(value = "/api/v1/users/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthenticatedUserResponse> me(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AuthenticatedUserResponse.from(
                authorization.requireActive(UserPrincipal.requireUserId(authentication))));
    }
}
