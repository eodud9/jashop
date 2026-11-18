package com.jashop.backend.workforce.infrastructure;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CheckoutRequestRepository {
    private final JdbcTemplate jdbc;
    public CheckoutRequestRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record CompletedRequest(Long attendanceId, String responseJson) {}

    /** User 행 잠금 후 호출. 잠금 대기 전의 스냅샷이 아닌 커밋된 최신 결과를 읽는다. */
    public Optional<CompletedRequest> findForUpdate(Long userId, String key) {
        return jdbc.query("SELECT attendance_id,response_json FROM attendance_checkout_requests WHERE user_id=? AND request_key=? FOR UPDATE",
                (row, index) -> new CompletedRequest(row.getLong("attendance_id"), row.getString("response_json")), userId, key)
                .stream().findFirst();
    }

    public void save(Long userId, String key, Long attendanceId, String json, Instant now) {
        jdbc.update("INSERT INTO attendance_checkout_requests(user_id,request_key,attendance_id,response_json,created_at) VALUES (?,?,?,?,?)",
                userId, key, attendanceId, json, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    }
}
