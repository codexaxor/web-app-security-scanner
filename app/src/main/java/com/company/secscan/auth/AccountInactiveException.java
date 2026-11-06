package com.company.secscan.auth;

import org.springframework.security.authentication.AccountStatusException;

/** The correct password for a DISABLED user (D-098): only revealed after the password matched. */
public class AccountInactiveException extends AccountStatusException {
    public AccountInactiveException() {
        super("Account not active");
    }
}
