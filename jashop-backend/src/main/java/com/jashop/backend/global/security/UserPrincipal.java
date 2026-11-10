package com.jashop.backend.global.security;

import java.security.Principal;

import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import org.springframework.security.core.Authentication;

/** 검증된 인증 제공자가 생성할 사용자 식별 정보. 역할·상태는 서비스에서 DB로 검증한다. */
public record UserPrincipal(Long userId) implements Principal {
    public UserPrincipal {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("사용자 ID는 양수여야 합니다.");
        }
    }

    @Override
    public String getName() {
        return userId.toString();
    }

    public static Long requireUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return principal.userId();
    }
}
