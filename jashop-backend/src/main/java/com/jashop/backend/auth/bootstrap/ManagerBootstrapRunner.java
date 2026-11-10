package com.jashop.backend.auth.bootstrap;

import com.jashop.backend.auth.api.dto.SignupRequest;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 운영자가 환경변수로 명시적으로 활성화하는 비웹 일회성 명령. */
@Component
@ConditionalOnProperty(name = "jashop.bootstrap.enabled", havingValue = "true")
public class ManagerBootstrapRunner implements ApplicationRunner {
    private final ManagerBootstrapService service;
    private final Environment environment;

    public ManagerBootstrapRunner(ManagerBootstrapService service, Environment environment) {
        this.service = service;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type"))) {
            throw new IllegalStateException("초기 관리자 생성은 비웹 모드에서만 실행할 수 있습니다.");
        }
        service.create(new SignupRequest(environment.getProperty("jashop.bootstrap.name"),
                environment.getProperty("jashop.bootstrap.email"),
                environment.getProperty("jashop.bootstrap.password")));
    }
}
