import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import Layout from "./Layout";
import { useAuth } from "../auth";

vi.mock("../auth", () => ({ useAuth: vi.fn() }));
vi.mock("../theme", () => ({ useTheme: () => ({ theme: "light", setTheme: () => {}, toggle: () => {} }) }));

function signedIn(demoMode = false) {
  vi.mocked(useAuth).mockReturnValue({
    checking: false,
    signedIn: true,
    username: "demoadmin",
    demoMode,
    signIn: () => {},
    signOut: () => {},
  });
}

function renderAt(path: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route element={<Layout />}>
            <Route path="*" element={<div>page</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => signedIn());

describe("DEMO MODE ribbon (M8.8 D-091 / SF-7)", () => {
  it("shows the red DEMO MODE ribbon when the session reports demoMode", () => {
    signedIn(true);
    renderAt("/dashboard");
    expect(screen.getByText(/DEMO MODE/)).not.toBeNull();
  });

  it("shows no ribbon when demo mode is off", () => {
    renderAt("/dashboard");
    expect(screen.queryByText(/DEMO MODE/)).toBeNull();
  });
});

describe("M8.11 shell — grouped nav", () => {
  it("renders the four sidebar groups", () => {
    renderAt("/dashboard");
    for (const label of ["Overview", "Findings", "Sources & repos", "Workspace"]) {
      expect(screen.getByRole("button", { name: new RegExp(label, "i") })).not.toBeNull();
    }
  });

  it("marks the current route's nav link active (aria-current=page)", () => {
    renderAt("/feed");
    expect(screen.getByRole("link", { name: "Feed" }).getAttribute("aria-current")).toBe("page");
    expect(screen.getByRole("link", { name: "Dashboard" }).getAttribute("aria-current")).not.toBe("page");
  });
});

describe("M8.11 shell — user menu", () => {
  it("hides Sign out until the account menu is opened, then shows the theme control", () => {
    renderAt("/dashboard");
    expect(screen.queryByRole("button", { name: "Sign out" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Account/ }));
    expect(screen.getByRole("button", { name: "Sign out" })).not.toBeNull();
    expect(screen.getByRole("group", { name: "Theme" })).not.toBeNull();
  });
});
