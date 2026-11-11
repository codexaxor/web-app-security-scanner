package com.company.secscan.source;

/**
 * A token-credentialed connection ({@code credential_kind='token'}) whose stored ciphertext is blank or missing.
 * This is a broken connection that needs a new token — never an anonymous one — so {@link SourceCredentials}
 * raises this instead of silently returning a null (anonymous) token, and the API maps it to a clear
 * "Connection needs a new token" error (M8.8 item 1, change 2). Carries no token or ciphertext.
 */
public class MissingCredentialException extends RuntimeException {

    public MissingCredentialException() {
        super("Connection needs a new token.");
    }
}
