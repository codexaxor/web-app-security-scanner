import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";
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
        <Routes>
          {/* The opener lives on "/" only, so navigating away unmounts it (like a page's top-bar button). */}
          <Route path="/" element={<button type="button">Opener</button>} />
          <Route path="*" element={null} />
        </Routes>
        <main id="main" tabIndex={-1} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return spy;
}

/** Focus the page's opener button, then open the palette from it with Ctrl+K. */
function openFromOpener() {
  const opener = screen.getByRole("button", { name: "Opener" });
  opener.focus();
  openPalette();
  return opener;
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

  it("Esc returns focus to the element that opened the palette", () => {
    setup([], 0);
    const opener = openFromOpener();
    expect(document.activeElement).toBe(screen.getByLabelText("Quick jump search"));
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.activeElement).toBe(opener);
  });

  it("a backdrop click returns focus to the opener", () => {
    setup([], 0);
    const opener = openFromOpener();
    const backdrop = screen.getByRole("dialog").parentElement!;
    fireEvent.mouseDown(backdrop);
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.activeElement).toBe(opener);
  });

  it("Ctrl+K again (toggle close) returns focus to the opener", () => {
    setup([], 0);
    const opener = openFromOpener();
    openPalette();
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.activeElement).toBe(opener);
  });

  it("after an Enter navigation that unmounts the opener, focus goes to the main landmark (not body)", () => {
    setup([], 0);
    openFromOpener();
    // Enter on Dashboard navigates away from "/", which unmounts the opener → fall back to the main landmark.
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Enter" });
    expect(screen.getByTestId("loc").textContent).toBe("/dashboard");
    expect(screen.queryByRole("button", { name: "Opener" })).toBeNull();
    expect(document.activeElement).toBe(document.getElementById("main"));
    expect(document.activeElement).not.toBe(document.body);
  });

  it("Enter with the opener still mounted returns focus to it", () => {
    setup([], 0);
    // An opener outside the routed area survives navigation.
    const outside = document.createElement("button");
    outside.textContent = "Persistent opener";
    document.body.appendChild(outside);
    try {
      outside.focus();
      openPalette();
      fireEvent.keyDown(screen.getByRole("dialog"), { key: "Enter" });
      expect(document.activeElement).toBe(outside);
    } finally {
      outside.remove();
    }
  });

  it("the input is a combobox that owns the listbox and points at the active option", () => {
    setup([], 0);
    openPalette();
    const input = screen.getByRole("combobox", { name: "Quick jump search" });
    const listbox = screen.getByRole("listbox", { name: "Results" });
    expect(input.getAttribute("aria-controls")).toBe(listbox.id);
    expect(input.getAttribute("aria-expanded")).toBe("true");
    expect(input.getAttribute("aria-autocomplete")).toBe("list");

    // Options are the listbox's DIRECT children, each with a unique id.
    const options = screen.getAllByRole("option");
    expect(options.length).toBeGreaterThan(0);
    for (const o of options) expect(o.parentElement).toBe(listbox);
    expect(new Set(options.map((o) => o.id)).size).toBe(options.length);
    expect(Array.from(listbox.children).every((c) => c.getAttribute("role") === "option")).toBe(true);

    // aria-activedescendant follows the arrow keys; exactly one option is aria-selected.
    expect(input.getAttribute("aria-activedescendant")).toBe(options[0].id);
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "ArrowDown" });
    expect(input.getAttribute("aria-activedescendant")).toBe(options[1].id);
    expect(options[1].getAttribute("aria-selected")).toBe("true");
    expect(options.filter((o) => o.getAttribute("aria-selected") === "true")).toHaveLength(1);
    // DOM focus never leaves the input.
    expect(document.activeElement).toBe(input);
  });

  it("with no matches the combobox is collapsed and has no active descendant", async () => {
    setup([], 0);
    openPalette();
    const input = screen.getByRole("combobox");
    fireEvent.change(input, { target: { value: "zzz-no-such-page" } });
    await waitFor(() => expect(screen.getByText("No matches")).not.toBeNull());
    expect(screen.queryAllByRole("option")).toHaveLength(0);
    expect(input.getAttribute("aria-expanded")).toBe("false");
    expect(input.hasAttribute("aria-activedescendant")).toBe(false);
  });
});
