package com.jashop.backend.global.security;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

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
import org.springframework.security.web.util.matcher.OrRequestMatcher;
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
        // JSON 가입/로그인 POST만 공개한다. 인증 쿠키/세션을 발급하지 않는다.
        var paths = PathPatternRequestMatcher.withDefaults();
        http.securityMatcher(new OrRequestMatcher(paths.matcher(HttpMethod.POST, "/api/v1/auth/signup"),
                        paths.matcher(HttpMethod.POST, "/api/v1/auth/login")))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain bearerSecurityFilterChain(HttpSecurity http, JsonMapper json, Clock clock,
            JwtDecoder decoder) throws Exception {
        var errors = new ApiSecurityErrorHandler(json, clock);
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/stores", "/api/v1/stores/**",
                        "/api/v1/admin/users", "/api/v1/admin/users/**", "/api/v1/users/me"))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(token ->
                                UsernamePasswordAuthenticationToken.authenticated(
                                        new UserPrincipal(Long.valueOf(token.getSubject())), null, List.of())))
                        .authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors));
        // 표준 Bearer 필터로 인증하고 기존 서비스에서 DB의 계정 상태/역할을 검증한다.
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(Environment environment) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.stream(environment.getProperty("jashop.security.allowed-origins",
                "http://localhost:5173").split(",")).map(String::strip).filter(value -> !value.isEmpty()).toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cors.setAllowCredentials(false);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
