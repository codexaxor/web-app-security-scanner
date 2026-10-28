import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it, vi } from "vitest";
import Layout from "./Layout";
import { useAuth } from "../auth";

vi.mock("../auth", () => ({ useAuth: vi.fn() }));
vi.mock("../theme", () => ({ useTheme: () => ({ theme: "system", setTheme: () => {} }) }));

function renderLayout(demoMode: boolean) {
  vi.mocked(useAuth).mockReturnValue({
    checking: false,
    signedIn: true,
    username: "demoadmin",
    demoMode,
    signIn: () => {},
    signOut: () => {},
  });
  return render(
    <MemoryRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<div>home</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe("DEMO MODE ribbon (M8.8 D-091 / SF-7)", () => {
  it("shows the red DEMO MODE ribbon when the session reports demoMode", () => {
    renderLayout(true);
    expect(screen.getByText(/DEMO MODE/)).not.toBeNull();
  });

  it("shows no ribbon when demo mode is off", () => {
    renderLayout(false);
    expect(screen.queryByText(/DEMO MODE/)).toBeNull();
  });
});
