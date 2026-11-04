import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi, beforeEach } from "vitest";
import { ToastProvider } from "../toast";
import type { ReactElement } from "react";
import DashboardPage from "./DashboardPage";
import FeedPage from "./FeedPage";
import RepositoriesPage from "./RepositoriesPage";
import RepositoryPage from "./RepositoryPage";
import IgnoredPage from "./IgnoredPage";
import SettingsPage from "./SettingsPage";
import ConnectPage from "./ConnectPage";
import SelectPage from "./SelectPage";

// S11.2: the ONE page template is used by every routed page. Each page renders with a never-resolving fetch
// (queries stay pending → the page shows its loading state), and must still mount PageLayout. This guards
// against a page diverging from the shared template.
beforeEach(() => {
  // Queries never resolve, so pages render their loading/skeleton state (which still includes the template).
  vi.stubGlobal("fetch", () => new Promise(() => {}));
});
afterEach(() => vi.unstubAllGlobals());

function renderPage(el: ReactElement, routePath: string, entry: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ToastProvider>
        <MemoryRouter initialEntries={[entry]}>
          <Routes>
            <Route path={routePath} element={el} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

const PAGES: [string, ReactElement, string, string][] = [
  ["Dashboard", <DashboardPage />, "/dashboard", "/dashboard"],
  ["Feed", <FeedPage />, "/feed", "/feed"],
  ["Repositories", <RepositoriesPage />, "/repositories", "/repositories"],
  ["Repository", <RepositoryPage />, "/repositories/:id", "/repositories/20000000-0000-0000-0000-000000000001"],
  ["Ignored", <IgnoredPage />, "/ignored", "/ignored"],
  ["Settings", <SettingsPage />, "/settings", "/settings"],
  ["Sources", <ConnectPage />, "/connect", "/connect"],
  ["Select", <SelectPage />, "/select", "/select"],
];

describe("every routed page renders the shared PageLayout", () => {
  it.each(PAGES)("%s renders PageLayout", (_name, el, routePath, entry) => {
    renderPage(el, routePath, entry);
    expect(screen.getAllByTestId("page-layout").length).toBeGreaterThanOrEqual(1);
  });
});
