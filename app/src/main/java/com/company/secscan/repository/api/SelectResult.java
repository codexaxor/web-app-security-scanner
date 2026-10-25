package com.company.secscan.repository.api;

import java.util.List;

/**
 * The outcome of selecting projects (B1): honest, server-authoritative counts of how many were newly added vs.
 * already selected, plus the resulting repository rows. Lets the UI say "N added · M already selected" (with
 * correct plurals) instead of over-counting every submitted id as "added".
 */
public record SelectResult(int added, int alreadySelected, List<RepositoryView> repositories) {
}
