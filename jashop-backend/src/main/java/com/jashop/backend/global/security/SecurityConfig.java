package com.jashop.backend.global.security;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.net.URI;

import com.jashop.backend.auth.session.application.AuthSessionService;

import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    @Order(1)
    public SecurityFilterChain publicAuthSecurityFilterChain(HttpSecurity http) throws Exception {
        // 가입은 인증 쿠키를 발급하지 않는다.
        var paths = PathPatternRequestMatcher.withDefaults();
        http.securityMatcher(paths.matcher(HttpMethod.POST, "/api/v1/auth/signup"))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain cookieAuthSecurityFilterChain(HttpSecurity http, JsonMapper json, Clock clock,
            Environment environment) throws Exception {
        var errors = new ApiSecurityErrorHandler(json, clock);
        var csrf = new CookieCsrfTokenRepository();
        csrf.setCookieCustomizer(cookie -> cookie.path("/api/v1/auth").secure(true).httpOnly(true).sameSite("Strict"));
        http.securityMatcher("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/csrf")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults()).requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(config -> config.csrfTokenRepository(csrf))
                .addFilterBefore(new AuthOriginFilter(allowedOrigins(environment), errors), CsrfFilter.class)
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors));
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain bearerSecurityFilterChain(HttpSecurity http, JsonMapper json, Clock clock,
            JwtDecoder decoder, AuthSessionService sessions) throws Exception {
        var errors = new ApiSecurityErrorHandler(json, clock);
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/stores", "/api/v1/stores/**",
                        "/api/v1/admin/users", "/api/v1/admin/users/**", "/api/v1/users/me"))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(token -> {
                            Long userId = Long.valueOf(token.getSubject());
                            sessions.requireActiveFamily(userId, token.getClaimAsString("sid"));
                            return UsernamePasswordAuthenticationToken.authenticated(new UserPrincipal(userId), null, List.of());
                        }))
                        .authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors));
        // 표준 Bearer 필터로 인증하고 기존 서비스에서 DB의 계정 상태/역할을 검증한다.
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(Environment environment) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins(environment));
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN"));
        cors.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    private List<String> allowedOrigins(Environment environment) {
        List<String> origins = Arrays.stream(environment.getProperty("jashop.security.allowed-origins",
                "http://localhost:5173").split(",")).map(String::strip).filter(value -> !value.isEmpty()).toList();
        if (origins.isEmpty()) throw new IllegalStateException("허용 Origin을 명시해야 합니다.");
        for (String origin : origins) {
            URI uri = URI.create(origin);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                throw new IllegalStateException("허용 Origin은 경로 없는 명시적 HTTP(S) 출처여야 합니다.");
            }
        }
        return origins;
    }
}
