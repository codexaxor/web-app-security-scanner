import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import LoginPage from "./pages/LoginPage";
import { ToastProvider } from "./toast";
import { useAuth } from "./auth";

// M8.13 routing: a public homepage at "/", a dedicated /login, standalone /signup, and an app shell that bounces
// signed-out visitors to /login?next=<path>. Auth/theme are mocked so these are pure routing assertions; data
// fetches never resolve (pages render their loading state).
vi.mock("./auth", () => ({ useAuth: vi.fn() }));
vi.mock("./theme", () => ({ useTheme: () => ({ theme: "light", setTheme: () => {}, toggle: () => {} }) }));

function setAuth(signedIn: boolean, checking = false) {
  vi.mocked(useAuth).mockReturnValue({
    checking,
    signedIn,
    username: signedIn ? "demoadmin" : null,
    demoMode: false,
    workspaceName: signedIn ? "Acme" : null,
    operator: false,
    signIn: () => {},
    signOut: () => {},
  });
}

beforeEach(() => {
  vi.stubGlobal("fetch", () => new Promise(() => {}));
  setAuth(false);
});
afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
});

function renderApp(entry: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ToastProvider>
        <MemoryRouter initialEntries={[entry]}>
          <App />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

describe("public routes", () => {
  it('"/" renders the public homepage with Sign in and Create account when signed out', () => {
    renderApp("/");
    expect(screen.getByTestId("homepage")).not.toBeNull();
    const signIn = screen.getAllByRole("link", { name: "Sign in" });
    expect(signIn.length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByRole("link", { name: "Create account" })[0].getAttribute("href")).toBe("/signup");
    expect(screen.queryByRole("link", { name: "Open dashboard" })).toBeNull();
  });

  it('"/" shows "Open dashboard" (and no Create account) when signed in', () => {
    setAuth(true);
    renderApp("/");
    expect(screen.getAllByRole("link", { name: "Open dashboard" })[0].getAttribute("href")).toBe("/dashboard");
    expect(screen.queryByRole("link", { name: "Create account" })).toBeNull();
  });

  it('"/login" renders the sign-in form', () => {
    renderApp("/login");
    expect(screen.getByRole("heading", { name: "Sign in" })).not.toBeNull();
    expect(screen.getByLabelText("Email")).not.toBeNull();
  });

  it('"/signup" renders the standalone sign-up form', () => {
    renderApp("/signup");
    expect(screen.getByTestId("signup-form")).not.toBeNull();
  });

  it('"/signup" sits in the centred public shell: a main landmark, the brand linking home, a link back home (B-2)', () => {
    renderApp("/signup");
    const main = screen.getByRole("main");
    expect(main.contains(screen.getByTestId("signup-form"))).toBe(true);
    expect(screen.getByRole("link", { name: "SecScan home" }).getAttribute("href")).toBe("/");
    expect(screen.getByRole("link", { name: /Back to home/ }).getAttribute("href")).toBe("/");
  });

  it('"/login" sits in the same shell, with the brand linking to "/"', () => {
    renderApp("/login");
    expect(screen.getByRole("main").contains(screen.getByRole("heading", { name: "Sign in" }))).toBe(true);
    expect(screen.getByRole("link", { name: "SecScan home" }).getAttribute("href")).toBe("/");
  });

  it('while the session check runs, "/login" shows a busy frame and no sign-in form (no flash)', () => {
    setAuth(false, true);
    renderApp("/login");
    expect(screen.getByRole("main").getAttribute("aria-busy")).toBe("true");
    expect(screen.queryByRole("heading", { name: "Sign in" })).toBeNull();
    expect(screen.queryByLabelText("Email")).toBeNull();
  });

  it('while the session check runs, "/" shows CTA placeholders — no Sign in / Create account / Open dashboard', () => {
    setAuth(false, true);
    renderApp("/");
    expect(screen.getByTestId("homepage")).not.toBeNull();
    expect(screen.getAllByTestId("cta-pending").length).toBe(3);
    expect(screen.queryByRole("link", { name: "Sign in" })).toBeNull();
    expect(screen.queryByRole("link", { name: "Create account" })).toBeNull();
    expect(screen.queryByRole("link", { name: "Open dashboard" })).toBeNull();
  });
});

describe("protected shell", () => {
  it("a signed-out visitor to an app route is bounced to the sign-in form", () => {
    renderApp("/feed");
    // No app nav; the sign-in form shows instead (the redirect resolved to /login).
    expect(screen.queryByRole("navigation", { name: "Primary" })).toBeNull();
    expect(screen.getByRole("heading", { name: "Sign in" })).not.toBeNull();
  });

  it("a signed-in visitor sees the app shell", () => {
    setAuth(true);
    renderApp("/dashboard");
    expect(screen.getByRole("navigation", { name: "Primary" })).not.toBeNull();
  });
});

describe("LoginPage ?next handling", () => {
  function renderLogin(entry: string, signedIn: boolean) {
    setAuth(signedIn);
    return render(
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="*" element={<DestProbe />} />
        </Routes>
      </MemoryRouter>,
    );
  }
  function DestProbe() {
    const loc = useLocation();
    return <div data-testid="dest">{loc.pathname + loc.search}</div>;
  }

  it("redirects a signed-in visitor to a safe same-origin next", () => {
    renderLogin("/login?next=%2Ffeed%3Fview%3Dall", true);
    expect(screen.getByTestId("dest").textContent).toBe("/feed?view=all");
  });

  it("falls back to /dashboard for an open-redirect next", () => {
    for (const bad of ["%2F%2Fevil.com", "https%3A%2F%2Fevil.com", "%2F%5Cevil.com"]) {
      renderLogin(`/login?next=${bad}`, true);
      expect(screen.getByTestId("dest").textContent).toBe("/dashboard");
      cleanup();
    }
  });

  it("falls back to /dashboard when next is absent", () => {
    renderLogin("/login", true);
    expect(screen.getByTestId("dest").textContent).toBe("/dashboard");
  });

  it("shows the form (no redirect) when signed out", () => {
    renderLogin("/login?next=%2Ffeed", false);
    expect(screen.getByRole("heading", { name: "Sign in" })).not.toBeNull();
    expect(screen.queryByTestId("dest")).toBeNull();
  });
});
