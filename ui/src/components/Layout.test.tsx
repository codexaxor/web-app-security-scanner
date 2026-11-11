import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation, useSearchParams } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import Layout from "./Layout";
import { useAuth } from "../auth";

/** Stands in for the /login route so a signed-out redirect out of the shell resolves and exposes `?next`. */
function LoginProbe() {
  const [params] = useSearchParams();
  const { search } = useLocation();
  return <div data-testid="login-probe" data-next={params.get("next") ?? ""} data-search={search} />;
}

vi.mock("../auth", () => ({ useAuth: vi.fn() }));
vi.mock("../theme", () => ({ useTheme: () => ({ theme: "light", setTheme: () => {}, toggle: () => {} }) }));

function signedIn(demoMode = false, operator = false) {
  vi.mocked(useAuth).mockReturnValue({
    checking: false,
    signedIn: true,
    username: "demoadmin",
    demoMode,
    workspaceName: "Acme Security",
    operator,
    signIn: () => {},
    signOut: () => {},
    signedOutByUser: false,
  });
}

function signedOut(byUser = false) {
  vi.mocked(useAuth).mockReturnValue({
    checking: false,
    signedIn: false,
    username: null,
    demoMode: false,
    workspaceName: null,
    operator: false,
    signIn: () => {},
    signOut: () => {},
    signedOutByUser: byUser,
  });
}

function renderAt(path: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/login" element={<LoginProbe />} />
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
  it("renders the four sidebar section labels", () => {
    renderAt("/dashboard");
    for (const label of ["Overview", "Findings", "Sources & repos", "Workspace"]) {
      expect(screen.getByText(label)).not.toBeNull();
    }
  });

  it("has a single control that collapses the whole sidebar", () => {
    renderAt("/dashboard");
    const toggle = screen.getByRole("button", { name: "Collapse sidebar" });
    expect(toggle.getAttribute("aria-pressed")).toBe("false");
    fireEvent.click(toggle);
    expect(screen.getByRole("button", { name: "Expand sidebar" }).getAttribute("aria-pressed")).toBe("true");
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

describe("M8.10 tenancy in the shell", () => {
  it("shows the Operator section (Sign-ups, Users) only when the session says operator", () => {
    renderAt("/dashboard");
    expect(screen.queryByText("Operator")).toBeNull();
    expect(screen.queryByRole("link", { name: "Sign-ups" })).toBeNull();
    cleanup();

    signedIn(false, true);
    renderAt("/dashboard");
    expect(screen.getByText("Operator")).not.toBeNull();
    expect(screen.getByRole("link", { name: "Sign-ups" }).getAttribute("href")).toBe("/admin/sign-ups");
    expect(screen.getByRole("link", { name: "Users" }).getAttribute("href")).toBe("/admin/users");
  });

  it("shows the workspace name in the user menu", () => {
    renderAt("/dashboard");
    fireEvent.click(screen.getByRole("button", { name: /Account/ }));
    expect(screen.getByTestId("user-menu-workspace").textContent).toContain("Acme Security");
  });

  it("signed out: redirects out of the shell to /login, remembering the requested route in ?next (M8.13)", () => {
    signedOut();
    renderAt("/dashboard");
    // The gate no longer renders in place; the shell bounces to the dedicated sign-in page.
    expect(screen.queryByRole("navigation", { name: "Primary" })).toBeNull();
    expect(screen.getByTestId("login-probe").getAttribute("data-next")).toBe("/dashboard");
    cleanup();

    signedOut();
    renderAt("/repositories/20000000-0000-0000-0000-000000000001?tab=issues");
    expect(screen.getByTestId("login-probe").getAttribute("data-next")).toBe(
      "/repositories/20000000-0000-0000-0000-000000000001?tab=issues",
    );
  });

  it("after an explicit Sign out: redirects to plain /login with no ?next (M8.13 review)", () => {
    signedOut(true);
    renderAt("/repositories/20000000-0000-0000-0000-000000000001?tab=issues");
    const probe = screen.getByTestId("login-probe");
    expect(probe.getAttribute("data-next")).toBe("");
    expect(probe.getAttribute("data-search")).toBe("");
  });
});
