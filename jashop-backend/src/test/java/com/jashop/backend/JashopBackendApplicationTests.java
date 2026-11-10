package com.jashop.backend;

import com.jashop.backend.user.infrastructure.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class JashopBackendApplicationTests {

	// 이 컨텍스트 테스트는 DB 자동 설정을 제외하므로 영속성 의존성만 대체한다.
	@MockitoBean
	UserRepository users;

	@MockitoBean
	JdbcTemplate jdbc;

	@Test
	void contextLoads() {
	}

}
