package com.company.secscan.source;

/**
 * A token-credentialed connection whose stored ciphertext exists but cannot be decrypted — the encryption key was
 * rotated/replaced, or the stored value is corrupt (M8.10 S12). Like {@link MissingCredentialException} this is a
 * broken connection that needs its token re-entered, not a server fault: the API maps it to a typed 409
 * {@code CONNECTION_TOKEN_UNREADABLE} and the worker fails the scan permanently without reconciling anything.
 * Carries no token, ciphertext or key material, and deliberately drops the cipher's cause.
 */
public class UnreadableCredentialException extends RuntimeException {

    public static final String CODE = "CONNECTION_TOKEN_UNREADABLE";
    public static final String MESSAGE =
            "The stored token for this source can't be read (the encryption key changed or the value is damaged). "
                    + "Re-enter the token for this source.";

    public UnreadableCredentialException() {
        super(MESSAGE);
    }
}
