package com.company.secscan.auth;

import org.springframework.security.authentication.AccountStatusException;

/** The correct password for a still-PENDING sign-up request (D-098): "awaiting approval", never before the password. */
public class AccountPendingException extends AccountStatusException {
    public AccountPendingException() {
        super("Account awaiting approval");
    }
}
