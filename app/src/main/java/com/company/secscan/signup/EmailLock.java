package com.company.secscan.signup;

import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serialises every transaction that claims an email (sign-up, approval) on a transaction-scoped Postgres advisory
 * lock keyed by the lowercased email (D-098), so "unique across app_user ∪ pending signup_request" holds without a
 * cross-table constraint. Released automatically at commit/rollback.
 */
@Component
public class EmailLock {

    private final JdbcTemplate jdbc;

    public EmailLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String email) {
        String key = "signup:" + email.trim().toLowerCase(Locale.ROOT);
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", rs -> { }, key);
    }
}
