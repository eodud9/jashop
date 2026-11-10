package com.jashop.backend.auth.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagerBootstrapRunnerTests {
    @Test
    void refusesWebModeBeforeCreatingManager() {
        var service = mock(ManagerBootstrapService.class);
        var runner = new ManagerBootstrapRunner(service, new MockEnvironment());
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(service);
    }

    @Test
    void readsExplicitNonWebCredentialsWithoutPrintingThem() {
        var service = mock(ManagerBootstrapService.class);
        var environment = new MockEnvironment().withProperty("spring.main.web-application-type", "none")
                .withProperty("jashop.bootstrap.name", "운영자")
                .withProperty("jashop.bootstrap.email", " MANAGER@example.com ")
                .withProperty("jashop.bootstrap.password", "test-only-password");
        new ManagerBootstrapRunner(service, environment).run(new DefaultApplicationArguments());
        var request = org.mockito.ArgumentCaptor.forClass(com.jashop.backend.auth.api.dto.SignupRequest.class);
        verify(service).create(request.capture());
        assertThat(request.getValue().email()).isEqualTo("manager@example.com");
        assertThat(request.getValue().password()).isEqualTo("test-only-password");
        assertThat(request.getValue().toString()).doesNotContain("test-only-password", "manager@example.com");
    }
}
