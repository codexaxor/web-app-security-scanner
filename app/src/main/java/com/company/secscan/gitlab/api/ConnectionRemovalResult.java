package com.company.secscan.gitlab.api;

/**
 * Result of removing a connection: how many of its repositories were reassigned to another connection that still
 * exposes them, and how many were deactivated because none could (D-020). Repository rows are never deleted.
 */
public record ConnectionRemovalResult(int reassigned, int deactivated) {
}
