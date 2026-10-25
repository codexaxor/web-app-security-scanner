package com.company.secscan.repository.api;

import java.util.List;

/**
 * The outcome of selecting projects (B1/SF-3): honest, server-authoritative counts — newly added, reactivated
 * (an inactive/detached repo re-selected through a connection that reaches it, D-020), and already selected
 * (present and active) — plus the resulting repository rows. Lets the UI say "N added · R reactivated · M
 * already selected" (with correct plurals) instead of over-counting every submitted id as "added".
 */
public record SelectResult(int added, int reactivated, int alreadySelected, List<RepositoryView> repositories) {
}
