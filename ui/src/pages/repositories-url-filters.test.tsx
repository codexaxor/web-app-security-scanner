import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "../toast";
import DashboardPage from "./DashboardPage";
import RepositoriesPage from "./RepositoriesPage";
import CommandPalette from "../components/CommandPalette";
import { api, type DashboardSummaryView, type Paged, type RepositoryView, type ScanView } from "../api";

// M8.11 review SF-2 / SF-3: the Repositories page reads `search` and `lastScanFailed` from the URL, so the
// Dashboard "Need attention" card and the ⌘K palette "View all repositories" row land on a FILTERED list.

function repo(id: string, path: string): RepositoryView {
  return {
    id,
    provider: "gitlab",
    host: "gitlab.example.com",
    providerRepoId: id,
    pathWithNamespace: path,
    effectiveBranch: "main",
    branchMissing: false,
    languages: [],
    active: true,
    openBySeverity: { critical: 0, high: 0, medium: 0, low: 0 },
    ignoredCount: 0,
    warningCount: 0,
    lastScan: null,
  } as unknown as RepositoryView;
}

function paged<T>(content: T[], total = content.length): Paged<T> {
  return { content, page: 0, size: 20, totalElements: total, totalPages: Math.max(1, Math.ceil(total / 20)) };
}

const SUMMARY: DashboardSummaryView = {
  groupBands: { critical: 0, high: 0, medium: 0, low: 0 },
  totalGroups: 0,
  findingBands: { critical: 0, high: 0, medium: 0, low: 0 },
  totalFindings: 0,
  byType: [],
  scanHealth: { unhealthyRepoCount: 2, failedPartialCount: 2, withWarningsCount: 0, repos: [] },
  inactive: { inactiveRepoCount: 0, openFindingsInInactiveRepos: 0 },
};

function LocationProbe() {
  const l = useLocation();
  return <div data-testid="loc">{l.pathname + l.search}</div>;
}

function renderAt(entry: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ToastProvider>
        <MemoryRouter initialEntries={[entry]}>
          <CommandPalette />
          <Routes>
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/repositories" element={<RepositoriesPage />} />
          </Routes>
          <LocationProbe />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

let listRepos: { mock: { calls: unknown[][] } };
beforeEach(() => {
  listRepos = vi.spyOn(api, "listRepositories").mockResolvedValue(paged([repo("r1", "acme/core")], 9));
  vi.spyOn(api, "dashboardSummary").mockResolvedValue(SUMMARY);
  vi.spyOn(api, "listScansAll").mockResolvedValue(paged<ScanView>([]));
  vi.spyOn(api, "listConnections").mockResolvedValue(paged([]));
});
afterEach(() => vi.restoreAllMocks());

/** The params of every listRepositories call made by the Repositories page's main list (it sends `sort`). */
function listCalls() {
  return listRepos.mock.calls
    .map((c: unknown[]) => c[0] as Record<string, unknown> | undefined)
    .filter((p): p is Record<string, unknown> => !!p && "sort" in p);
}

function lastCall() {
  const calls = listCalls();
  return calls[calls.length - 1];
}

describe("Repositories URL filters (SF-2 / SF-3)", () => {
  it("?lastScanFailed=true checks the filter and sends lastScanFailed=true", async () => {
    renderAt("/repositories?lastScanFailed=true");
    await waitFor(() => expect(listCalls().length).toBeGreaterThan(0));
    expect(lastCall()).toMatchObject({ lastScanFailed: true });
    expect((screen.getByLabelText("Last scan failed") as HTMLInputElement).checked).toBe(true);
  });

  it("?search=core fills the search box and sends search=core", async () => {
    renderAt("/repositories?search=core");
    await waitFor(() => expect(listCalls().length).toBeGreaterThan(0));
    expect(lastCall()).toMatchObject({ search: "core" });
    expect((screen.getByLabelText("Search repositories") as HTMLInputElement).value).toBe("core");
  });

  it("toggling the checkbox and typing a search write the URL and reset the page", async () => {
    renderAt("/repositories?page=2");
    fireEvent.click(await screen.findByLabelText("Last scan failed"));
    await waitFor(() => expect(screen.getByTestId("loc").textContent).toBe("/repositories?lastScanFailed=true"));
    fireEvent.change(screen.getByLabelText("Search repositories"), { target: { value: "web" } });
    await waitFor(() =>
      expect(screen.getByTestId("loc").textContent).toBe("/repositories?lastScanFailed=true&search=web"),
    );
    await waitFor(() => expect(lastCall()).toMatchObject({ lastScanFailed: true, search: "web", page: 0 }));
  });

  it("Dashboard 'Need attention' card → Repositories filtered to last-scan-failed", async () => {
    renderAt("/dashboard");
    fireEvent.click(await screen.findByRole("link", { name: /Need attention/ }));
    await waitFor(() => expect(screen.getByTestId("loc").textContent).toBe("/repositories?lastScanFailed=true"));
    await waitFor(() => expect(lastCall()).toMatchObject({ lastScanFailed: true }));
    expect((screen.getByLabelText("Last scan failed") as HTMLInputElement).checked).toBe(true);
  });

  it("palette 'View all repositories' → Repositories filtered by the typed search", async () => {
    renderAt("/dashboard");
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    fireEvent.change(screen.getByLabelText("Quick jump search"), { target: { value: "core" } });
    fireEvent.click(await screen.findByText("View all repositories", {}, { timeout: 2000 }));
    await waitFor(() => expect(screen.getByTestId("loc").textContent).toBe("/repositories?search=core"));
    await waitFor(() => expect(lastCall()).toMatchObject({ search: "core" }));
    expect((screen.getByLabelText("Search repositories") as HTMLInputElement).value).toBe("core");
  });
});
