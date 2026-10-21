package com.company.secscan.triage;

/**
 * The reason a finding is {@code ignored} (CLAUDE.md hard rule 5; SPEC "Auto-triage reason codes"). The code is
 * stored verbatim as {@code sub_issue.ignore_reason} and written on the {@code triage_event}. Values are declared
 * in PRECEDENCE order (highest first): when more than one reason could apply to the same finding in one reconcile
 * pass, the earliest-declared wins and is the one recorded. A manual/`.secscan.yml` {@link #IGNORE_RULE} always
 * outranks a cause-based auto-ignore.
 *
 * <p>Pure (no Spring). The code string is the enum {@link #name()}.
 */
public enum ReasonCode {

    /** A manual ignore rule or a `.secscan.yml` CVE ignore (D-057/D-058). Highest precedence. */
    IGNORE_RULE,

    /** A default-excluded or `.secscan.yml`-excluded path, or a minified file (D-059). */
    EXCLUDED_PATH,

    /** A secret in a placeholder file/value (changeme / example / *.example) (D-059). */
    SECRET_PLACEHOLDER,

    /** The losing rule of a curated duplicate-SAST-rule pair (D-061). */
    DUPLICATE_RULE,

    /** A dependency declared in a dev configuration (npm devDependencies, Gradle test configs) (D-059). */
    DEV_DEPENDENCY,

    /** A dependency declared in Maven {@code test} scope (D-059). */
    TEST_SCOPE,

    /** EPSS below 1% and not on KEV, with the EPSS toggle on (D-060). Lowest precedence. */
    LOW_EPSS;

    /** The string stored on {@code sub_issue.ignore_reason} and {@code triage_event.reason_code}. */
    public String code() {
        return name();
    }

    /** True when this reason outranks {@code other} (earlier declaration = higher precedence). */
    public boolean outranks(ReasonCode other) {
        return this.ordinal() < other.ordinal();
    }
}
