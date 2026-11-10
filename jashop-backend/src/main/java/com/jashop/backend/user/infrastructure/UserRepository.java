package com.jashop.backend.user.infrastructure;

import java.util.Optional;

import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRole(UserRole role);
}
