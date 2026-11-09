package com.jashop.backend.user.domain;

import java.time.Instant;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class UserTests {

    private static final Instant NOW = Instant.parse("2026-11-09T01:02:03.123456Z");

    @Test
    void creationCannotAssignRoleStoreOrApproval() {
        User user = User.createPending(" Staff@Example.com ", "encoded-password", "직원", NOW);

        assertThat(user.getId()).isNull();
        assertThat(user.getEmail()).isEqualTo("staff@example.com");
        assertThat(user.getRole()).isEqualTo(UserRole.STAFF);
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(user.getHomeStoreId()).isNull();
        assertThat(user.getApprovedBy()).isNull();
        assertThat(user.getApprovedAt()).isNull();
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void emailNormalizationDoesNotDependOnDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(User.normalizeEmail(" I@EXAMPLE.COM ")).isEqualTo("i@example.com");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void rejectsMissingOrOversizedFieldsWithoutIncludingValuesInErrors() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                User.createPending(" ", "hash", "직원", NOW));
        assertThatIllegalArgumentException().isThrownBy(() ->
                User.createPending("x".repeat(255), "hash", "직원", NOW));
        assertThatIllegalArgumentException().isThrownBy(() ->
                User.createPending("a@example.com", " ", "직원", NOW));
        assertThatIllegalArgumentException().isThrownBy(() ->
                User.createPending("a@example.com", "secret".repeat(43), "직원", NOW))
                .withMessageNotContaining("secret");
        assertThatIllegalArgumentException().isThrownBy(() ->
                User.createPending("a@example.com", "hash", " ", NOW));
        assertThatIllegalArgumentException().isThrownBy(() ->
                User.createPending("a@example.com", "hash", "이".repeat(101), NOW));
        assertThatNullPointerException().isThrownBy(() ->
                User.createPending("a@example.com", "hash", "직원", null));
        assertThatNullPointerException().isThrownBy(() -> User.normalizeEmail(null));
    }
}
