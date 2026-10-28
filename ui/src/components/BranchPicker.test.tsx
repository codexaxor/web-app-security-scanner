import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import BranchPicker from "./BranchPicker";
import { api } from "../api";

vi.mock("../api", () => ({ api: { listRepositoryBranches: vi.fn() } }));

const branchesResult = {
  content: [
    { name: "main", isDefault: true, isProtected: true, lastCommitAt: null },
    { name: "dev", isDefault: false, isProtected: false, lastCommitAt: "2026-10-01T09:00:00Z" },
  ],
  page: 0,
  size: 100,
  totalElements: 2,
  totalPages: 1,
  searchTruncated: false,
};

function renderPicker() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <BranchPicker repoId="r1" defaultBranch="main" value={null} onChange={() => {}} />
    </QueryClientProvider>,
  );
}

describe("BranchPicker last-commit time (M8.8 item 10)", () => {
  afterEach(() => vi.clearAllMocks());

  it("shows a time for a branch with a last-commit date and NONE for a null one (no dash / Invalid date)", async () => {
    (api.listRepositoryBranches as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(branchesResult);
    renderPicker();

    // The option rows are the buttons whose accessible name starts with the branch name.
    const main = (await screen.findByRole("option", { name: /^main/ })) as HTMLElement;
    const dev = (await screen.findByRole("option", { name: /^dev/ })) as HTMLElement;

    // dev has a last-commit <time>; main (null) has none, and shows no "—" / "Invalid date".
    expect(dev.querySelector("time")).not.toBeNull();
    expect(main.querySelector("time")).toBeNull();
    expect(main.textContent).not.toContain("—");
    expect(main.textContent).not.toContain("Invalid");
  });
});
