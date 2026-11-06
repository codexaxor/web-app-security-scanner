import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import HomePage from "./HomePage";
import { useAuth } from "../auth";

vi.mock("../auth", () => ({ useAuth: vi.fn() }));
vi.mock("../theme", () => ({ useTheme: () => ({ theme: "light", setTheme: () => {}, toggle: () => {} }) }));

function setSignedIn(signedIn: boolean) {
  vi.mocked(useAuth).mockReturnValue({
    checking: false,
    signedIn,
    username: signedIn ? "demoadmin" : null,
    demoMode: false,
    workspaceName: signedIn ? "Acme" : null,
    operator: false,
    signIn: () => {},
    signOut: () => {},
  });
}

const fetchSpy = vi.fn(() => new Promise(() => {}));

beforeEach(() => {
  vi.stubGlobal("fetch", fetchSpy);
  fetchSpy.mockClear();
  setSignedIn(false);
});
afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
});

function renderHome() {
  return render(
    <MemoryRouter>
      <HomePage />
    </MemoryRouter>,
  );
}

describe("HomePage", () => {
  it("makes NO API calls when signed out (S3)", () => {
    renderHome();
    expect(screen.getByTestId("homepage")).not.toBeNull();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("renders every section heading", () => {
    renderHome();
    for (const h of [
      "How a scan works",
      "What it finds",
      "Frequently asked",
      "SecScan vs pipeline-based tools",
    ]) {
      expect(screen.getByRole("heading", { name: h })).not.toBeNull();
    }
  });

  it("the first FAQ item is open by default (aria-expanded true, panel visible)", () => {
    renderHome();
    const first = screen.getByRole("button", { name: /If I connect GitHub or GitLab/ });
    expect(first.getAttribute("aria-expanded")).toBe("true");
    expect(first.getAttribute("aria-controls")).toBeTruthy();
    const panel = screen.getByRole("region", { name: /If I connect GitHub or GitLab/ });
    expect(panel.getAttribute("id")).toBe(first.getAttribute("aria-controls"));
    expect(panel.textContent).toMatch(/read-only token/i);
  });

  it("FAQ items are accordion buttons with aria-expanded/aria-controls that toggle a region", () => {
    renderHome();
    // A non-first item starts collapsed, proving each item is an independent accordion.
    const trigger = screen.getByRole("button", { name: /Do you store my data/ });
    expect(trigger.getAttribute("aria-expanded")).toBe("false");
    expect(trigger.getAttribute("aria-controls")).toBeTruthy();
    // Collapsed: the panel is hidden (absent from the accessibility tree).
    expect(screen.queryByRole("region", { name: /Do you store my data/ })).toBeNull();

    fireEvent.click(trigger);
    expect(trigger.getAttribute("aria-expanded")).toBe("true");
    const panel = screen.getByRole("region", { name: /Do you store my data/ });
    expect(panel.getAttribute("id")).toBe(trigger.getAttribute("aria-controls"));
    expect(panel.textContent).toMatch(/keyed hash/i);

    fireEvent.click(trigger);
    expect(trigger.getAttribute("aria-expanded")).toBe("false");
  });

  it("shows all 7 FAQ questions", () => {
    renderHome();
    expect(screen.getAllByRole("button", { name: /\?$/ }).length).toBe(7);
  });
});
