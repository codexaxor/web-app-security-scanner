package com.company.secscan.signup;

import com.company.secscan.auth.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/auth/signup} — public, JSON-only (415 otherwise), CSRF-exempt like login (D-093/D-098).
 * Always the same 202 body for a valid request, whether or not the email is already known.
 */
@RestController
@Profile("api")
@RequestMapping("/api/v1/auth")
public class SignupController {

    public record SignupRequestBody(String email, String password, String workspaceName) {
    }

    public record SignupAccepted(String status, String message) {
    }

    static final SignupAccepted ACCEPTED = new SignupAccepted("RECEIVED",
            "Thanks — if this email can be registered, your sign-up now awaits approval. "
                    + "Sign in with the same email and password once it is approved.");

    private final SignupService service;
    private final ClientIpResolver clientIp;

    public SignupController(SignupService service, ClientIpResolver clientIp) {
        this.service = service;
        this.clientIp = clientIp;
    }

    @PostMapping(path = "/signup", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SignupAccepted> signUp(@RequestBody SignupRequestBody body, HttpServletRequest request) {
        service.signUp(body == null ? null : body.email(), body == null ? null : body.password(),
                body == null ? null : body.workspaceName(), clientIp.resolve(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ACCEPTED);
    }
}
