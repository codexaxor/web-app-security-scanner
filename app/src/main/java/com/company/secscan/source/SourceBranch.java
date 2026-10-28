package com.company.secscan.source;

/**
 * A provider-neutral branch for the branch picker (D-076/D-079): name plus whether it is the default and whether
 * it is protected, and the last-commit timestamp as the provider's ISO string (null if unknown). Branch names
 * are external data and are rendered as text only (D-067).
 */
public record SourceBranch(String name, boolean isDefault, boolean isProtected, String lastCommitAt) {
}
