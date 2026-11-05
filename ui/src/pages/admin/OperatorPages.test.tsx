import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "../../toast";
import OperatorSignUpsPage from "./OperatorSignUpsPage";
import OperatorUsersPage from "./OperatorUsersPage";
import { api, type SignupRequestView, type UserAdminView } from "../../api";

vi.mock("../../api", async (orig) => {
  const real = await orig<typeof import("../../api")>();
  return {
    ...real,
    api: {
      ...real.api,
      listSignUps: vi.fn(),
      approveSignUp: vi.fn(),
      rejectSignUp: vi.fn(),
      listUsers: vi.fn(),
      disableUser: vi.fn(),
      enableUser: vi.fn(),
    },
  };
});

afterEach(() => vi.clearAllMocks());

function renderAt(el: ReactElement, path: string, entry: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ToastProvider autoDismiss={false}>
        <MemoryRouter initialEntries={[entry]}>
          <Routes>
            <Route path={path} element={el} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

const paged = <T,>(content: T[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });

const pending: SignupRequestView = {
  id: "s1",
  email: "new@tenant.example",
  workspaceName: "Tenant",
  status: "pending",
  createdAt: "2026-11-05T00:00:00Z",
  decidedAt: null,
  workspaceId: null,
};

describe("OperatorSignUpsPage (M8.10, D-094)", () => {
  it("lists pending sign-ups with the default page size 20 and approves one", async () => {
    vi.mocked(api.listSignUps).mockResolvedValue(paged([pending]));
    vi.mocked(api.approveSignUp).mockResolvedValue({ ...pending, status: "approved", workspaceId: "w1" });
    renderAt(<OperatorSignUpsPage />, "/admin/sign-ups", "/admin/sign-ups");

    expect((await screen.findByTestId("signup-row")).textContent).toContain("new@tenant.example");
    expect(api.listSignUps).toHaveBeenCalledWith("pending", 0, 20);
    fireEvent.click(screen.getByRole("button", { name: "Approve new@tenant.example" }));
    await waitFor(() => expect(api.approveSignUp).toHaveBeenCalledWith("s1"));
    expect(await screen.findByText("Approved new@tenant.example.")).not.toBeNull();
  });

  it("reads the status filter from the URL and shows an empty state", async () => {
    vi.mocked(api.listSignUps).mockResolvedValue(paged([]));
    renderAt(<OperatorSignUpsPage />, "/admin/sign-ups", "/admin/sign-ups?status=rejected");
    expect((await screen.findByTestId("signups-empty")).textContent).toContain("No rejected sign-ups");
    expect(api.listSignUps).toHaveBeenCalledWith("rejected", 0, 20);
  });

  it("shows the server's 403 for a non-operator as an inline error (the API is the boundary)", async () => {
    const { ApiError } = await import("../../api");
    vi.mocked(api.listSignUps).mockRejectedValue(new ApiError("Forbidden", "FORBIDDEN"));
    renderAt(<OperatorSignUpsPage />, "/admin/sign-ups", "/admin/sign-ups");
    expect((await screen.findByRole("alert")).textContent).toContain("Forbidden");
  });
});

describe("OperatorUsersPage (M8.10, D-094)", () => {
  const users: UserAdminView[] = [
    { id: "op", email: "op@x.example", status: "active", workspaceName: "Default", operator: true, createdAt: "2026-01-01T00:00:00Z", statusChangedAt: null },
    { id: "u1", email: "user@tenant.example", status: "active", workspaceName: "Tenant", operator: false, createdAt: "2026-11-05T00:00:00Z", statusChangedAt: null },
    { id: "u2", email: "gone@tenant.example", status: "disabled", workspaceName: "Tenant", operator: false, createdAt: "2026-11-05T00:00:00Z", statusChangedAt: "2026-11-05T02:00:00Z" },
  ];

  it("offers no Disable for the operator, confirms before disabling, and offers Re-enable for disabled", async () => {
    vi.mocked(api.listUsers).mockResolvedValue(paged(users));
    vi.mocked(api.disableUser).mockResolvedValue({ ...users[1], status: "disabled" });
    renderAt(<OperatorUsersPage />, "/admin/users", "/admin/users");

    const rows = await screen.findAllByTestId("user-row");
    expect(within(rows[0]).queryByRole("button")).toBeNull();
    expect(within(rows[2]).getByRole("button", { name: "Re-enable gone@tenant.example" })).not.toBeNull();

    fireEvent.click(within(rows[1]).getByRole("button", { name: "Disable user@tenant.example" }));
    expect(api.disableUser).not.toHaveBeenCalled(); // a confirm dialog first
    fireEvent.click(await screen.findByRole("button", { name: "Disable account" }));
    await waitFor(() => expect(api.disableUser).toHaveBeenCalledWith("u1"));
    expect(api.listUsers).toHaveBeenCalledWith(null, 0, 20);
  });
});
