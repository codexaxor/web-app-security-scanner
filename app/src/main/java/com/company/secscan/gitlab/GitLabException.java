package com.company.secscan.gitlab;

/**
 * A GitLab call failed in a way we can explain to the user. Carries a {@link GitLabError} category and a
 * safe, human-readable message. Never contains the token.
 */
public class GitLabException extends RuntimeException {

    private final GitLabError error;

    public GitLabException(GitLabError error, String message) {
        super(message);
        this.error = error;
    }

    public GitLabException(GitLabError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public GitLabError getError() {
        return error;
    }
}
