package com.jashop.backend.user.application;

import java.util.Objects;

import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.domain.UserRole;
import com.jashop.backend.user.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** userId는 향후 인증된 principal에서 전달한다. 요청 본문의 사용자 ID를 신뢰하지 않는다. */
@Service
@Transactional(readOnly = true)
public class UserAuthorizationService {
    private final UserRepository users;

    public UserAuthorizationService(UserRepository users) {
        this.users = users;
    }

    public User requireActive(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        User user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        switch (user.getStatus()) {
            case PENDING -> throw new BusinessException(ErrorCode.ACCOUNT_PENDING);
            case REJECTED -> throw new BusinessException(ErrorCode.ACCOUNT_REJECTED);
            case INACTIVE -> throw new BusinessException(ErrorCode.ACCOUNT_INACTIVE);
            case ACTIVE -> { return user; }
        }
        throw new IllegalStateException("지원하지 않는 계정 상태입니다.");
    }

    public User requireManager(Long userId) {
        User user = requireActive(userId);
        if (user.getRole() != UserRole.MANAGER) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return user;
    }

    /** 매장 부매니저 전용 명령. 총괄의 예외 승인 정책은 미확정이므로 우회하지 않는다. */
    public User requireStoreAssistantManager(Long userId, Long storeId) {
        User user = requireActive(userId);
        if (user.getRole() != UserRole.ASSISTANT_MANAGER || storeId == null
                || !Objects.equals(user.getHomeStoreId(), storeId)) {
            throw new BusinessException(ErrorCode.STORE_ACCESS_DENIED);
        }
        return user;
    }
}
