package com.company.secscan.signup;

import com.company.secscan.api.ApiErrorException;
import com.company.secscan.auth.RateLimits;
import com.company.secscan.user.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Public self sign-up (D-093/D-098). The caller learns NOTHING about whether the email is already known: a new
 * email, an existing account and an already-pending request all get the same 202 body, and the password is
 * bcrypt-hashed in every case (equal timing). Only a new, unknown email produces a pending {@code signup_request};
 * no workspace and no {@code app_user} are created here (that happens at approval). Per-IP rate limited.
 */
@Service
@Profile("api")
public class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    static final int MIN_PASSWORD_CHARS = 12;
    static final int MAX_PASSWORD_BYTES = 72;            // bcrypt ignores bytes beyond 72
    static final int MAX_WORKSPACE_NAME = 80;
    static final int MAX_EMAIL = 254;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    /** The seeded Default workspace's name (D-011): reserved, so a tenant cannot look like Default (review N-6). */
    static final String RESERVED_WORKSPACE_NAME = "Default";

    private final SignupRequestRepository requests;
    private final AppUserRepository users;
    private final EmailLock emailLock;
    private final PasswordEncoder encoder;
    private final RateLimits limits;
    private final TransactionTemplate tx;

    public SignupService(SignupRequestRepository requests, AppUserRepository users, EmailLock emailLock,
                         PasswordEncoder encoder, RateLimits limits, TransactionTemplate tx) {
        this.requests = requests;
        this.users = users;
        this.emailLock = emailLock;
        this.encoder = encoder;
        this.limits = limits;
        this.tx = tx;
    }

    public void signUp(String rawEmail, String password, String rawWorkspaceName, String clientIp) {
        long wait = limits.signupAttempt(clientIp);
        if (wait > 0) {
            throw new ApiErrorException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many sign-up attempts. Try again later.", wait);
        }
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        String workspaceName = rawWorkspaceName == null ? "" : rawWorkspaceName.trim();
        validate(email, password, workspaceName);

        String hash = encoder.encode(password);   // always — same cost for known and unknown emails
        try {
            tx.executeWithoutResult(status -> {
                emailLock.lock(email);
                boolean taken = users.findByEmail(email).isPresent()
                        || requests.findByEmailAndStatus(email, SignupRequest.STATUS_PENDING).isPresent();
                if (!taken) {
                    requests.saveAndFlush(SignupRequest.pending(email, hash, workspaceName));
                }
            });
        } catch (DataIntegrityViolationException race) {
            // The pending-email unique index caught a concurrent duplicate — same outcome as "already pending".
            log.debug("Concurrent duplicate sign-up absorbed");
        }
    }

    private static void validate(String email, String password, String workspaceName) {
        if (email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) {
            throw invalid("email: must be a valid email address");
        }
        // Review N-3: printable ASCII only, so Java's and Postgres's lower() always agree (ck_signup_request_email).
        if (!email.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
            throw invalid("email: must use ASCII characters only");
        }
        if (password == null || password.codePointCount(0, password.length()) < MIN_PASSWORD_CHARS) {
            throw invalid("password: must be at least " + MIN_PASSWORD_CHARS + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw invalid("password: must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }
        if (workspaceName.isEmpty() || workspaceName.length() > MAX_WORKSPACE_NAME
                || workspaceName.chars().anyMatch(Character::isISOControl)) {
            throw invalid("workspaceName: 1 to " + MAX_WORKSPACE_NAME + " characters, no control characters");
        }
        if (workspaceName.equalsIgnoreCase(RESERVED_WORKSPACE_NAME)) {
            throw invalid("workspaceName: \"" + RESERVED_WORKSPACE_NAME + "\" is reserved; choose another name");
        }
    }

    private static ApiErrorException invalid(String message) {
        return new ApiErrorException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }
}
