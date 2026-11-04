import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import CommandPalette from "./CommandPalette";
import { api, type Paged, type RepositoryView } from "../api";

function repo(id: string, path: string): RepositoryView {
  return {
    id,
    gitlabProjectId: null,
    provider: "github",
    host: "github.com",
    providerRepoId: id,
    pathWithNamespace: path,
    defaultBranch: "main",
    scanBranch: null,
    effectiveBranch: "main",
    branchMissing: false,
    languages: [],
    active: true,
  } as unknown as RepositoryView;
}

function paged(content: RepositoryView[], total: number): Paged<RepositoryView> {
  return { content, page: 0, size: 6, totalElements: total, totalPages: Math.ceil(total / 6) };
}

function LocationProbe() {
  const l = useLocation();
  return <div data-testid="loc">{l.pathname + l.search}</div>;
}

function setup(repos: RepositoryView[], total: number) {
  const spy = vi.spyOn(api, "listRepositories").mockResolvedValue(paged(repos, total));
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={["/"]}>
        <CommandPalette />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return spy;
}

function openPalette() {
  fireEvent.keyDown(window, { key: "k", ctrlKey: true });
}

afterEach(() => vi.restoreAllMocks());

describe("CommandPalette (⌘K)", () => {
  it("opens on Ctrl+K as a modal dialog and focuses the search input", () => {
    setup([], 0);
    expect(screen.queryByRole("dialog")).toBeNull();
    openPalette();
    const dialog = screen.getByRole("dialog", { name: "Quick jump" });
    expect(dialog.getAttribute("aria-modal")).toBe("true");
    expect(document.activeElement).toBe(screen.getByLabelText("Quick jump search"));
  });

  it("closes on Escape and traps Tab inside the dialog", () => {
    setup([], 0);
    openPalette();
    const dialog = screen.getByRole("dialog");
    // Tab is intercepted (default prevented) so focus cannot leave the palette.
    expect(fireEvent.keyDown(dialog, { key: "Tab" })).toBe(false);
    fireEvent.keyDown(dialog, { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("uses the server-side repository search", async () => {
    const spy = setup([repo("r1", "org/one")], 3);
    openPalette();
    fireEvent.change(screen.getByLabelText("Quick jump search"), { target: { value: "one" } });
    await waitFor(() => expect(spy).toHaveBeenCalledWith(expect.objectContaining({ search: "one", page: 0 })));
    // size is bounded (first page only, never all pages).
    expect(spy.mock.calls.every((c) => ((c[0] as { size?: number }).size ?? 0) <= 6)).toBe(true);
  });

  it("moves the selection with arrows and navigates on Enter", () => {
    setup([], 0);
    openPalette();
    const dialog = screen.getByRole("dialog");
    // Items start at Dashboard; ArrowDown → Feed; Enter navigates there.
    fireEvent.keyDown(dialog, { key: "ArrowDown" });
    fireEvent.keyDown(dialog, { key: "Enter" });
    expect(screen.getByTestId("loc").textContent).toBe("/feed");
  });

  it("shows a 'View all repositories' row when there are more than the first page", async () => {
    setup([repo("r1", "org/one"), repo("r2", "org/two")], 42);
    openPalette();
    await waitFor(() => expect(screen.getByText("View all repositories")).not.toBeNull());
  });
});
