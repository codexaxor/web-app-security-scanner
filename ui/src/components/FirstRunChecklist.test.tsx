import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import FirstRunChecklist from "./FirstRunChecklist";
import { api, type Paged } from "../api";
import { invalidateFor, queryClient } from "../queryClient";

// M8.11 review N-1: the checklist's queries sit under families the mutation map invalidates, so adding a source,
// selecting repositories or finishing a scan updates it immediately (not after the 10 s staleTime).
function count(n: number): Paged<never> {
  return { content: [], page: 0, size: 1, totalElements: n, totalPages: n ? n : 0 };
}

afterEach(() => {
  vi.restoreAllMocks();
  queryClient.clear();
});

function setup(sources: number, repos: number, scans: number) {
  const c = vi.spyOn(api, "listConnections").mockResolvedValue(count(sources));
  const r = vi.spyOn(api, "listRepositories").mockResolvedValue(count(repos));
  const s = vi.spyOn(api, "listScansAll").mockResolvedValue(count(scans));
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <FirstRunChecklist />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { c, r, s };
}

describe("FirstRunChecklist invalidation (N-1)", () => {
  it("a 'sources' mutation refetches the source and repository counts", async () => {
    const { c, r } = setup(0, 0, 0);
    await screen.findByTestId("first-run-checklist");
    expect(c).toHaveBeenCalledTimes(1);
    expect(r).toHaveBeenCalledTimes(1);

    c.mockResolvedValue(count(1));
    r.mockResolvedValue(count(2));
    await invalidateFor("sources");
    expect(c).toHaveBeenCalledTimes(2);
    expect(r).toHaveBeenCalledTimes(2);
    // Step 1 and 2 now show Done.
    await waitFor(() => expect(screen.getAllByText("Done")).toHaveLength(2));
  });

  it("a 'scan' mutation refetches the scan count; all three done hides the checklist", async () => {
    const { s } = setup(1, 1, 0);
    await screen.findByTestId("first-run-checklist");
    expect(s).toHaveBeenCalledTimes(1);

    s.mockResolvedValue(count(1));
    await invalidateFor("scan");
    expect(s).toHaveBeenCalledTimes(2);
    await waitFor(() => expect(screen.queryByTestId("first-run-checklist")).toBeNull());
  });
});
