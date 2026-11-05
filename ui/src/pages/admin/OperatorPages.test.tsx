import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "../../toast";
import OperatorSignUpsPage from "./OperatorSignUpsPage";
import OperatorUsersPage from "./OperatorUsersPage";
import { operatorApi } from "../../api/signups";

vi.mock("../../api/signups", async (orig) => {
  const real = await orig<typeof import("../../api/signups")>();
  return {
    ...real,
    operatorApi: {
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

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider autoDismiss={false}>{node}</ToastProvider>
    </QueryClientProvider>,
  );
}

const paged = <T,>(content: T[]) => ({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 });

describe("OperatorSignUpsPage (M8.10, D-094)", () => {
  it("lists pending requests and approves one", async () => {
    vi.mocked(operatorApi.listSignUps).mockResolvedValue(
      paged([
        {
          id: "s1",
          email: "new@tenant.example",
          workspaceName: "Tenant",
          status: "pending" as const,
          createdAt: "2026-11-05T00:00:00Z",
          decidedAt: null,
          workspaceId: null,
        },
      ]),
    );
    vi.mocked(operatorApi.approveSignUp).mockResolvedValue({
      id: "s1",
      email: "new@tenant.example",
      workspaceName: "Tenant",
      status: "approved",
      createdAt: "2026-11-05T00:00:00Z",
      decidedAt: "2026-11-05T01:00:00Z",
      workspaceId: "w1",
    });
    wrap(<OperatorSignUpsPage />);

    expect((await screen.findByTestId("signup-row")).textContent).toContain("new@tenant.example");
    expect(operatorApi.listSignUps).toHaveBeenCalledWith("pending", 0, 50);
    fireEvent.click(screen.getByText("Approve"));
    await waitFor(() => expect(operatorApi.approveSignUp).toHaveBeenCalledWith("s1"));
    expect(await screen.findByText("Approved new@tenant.example.")).toBeTruthy();
  });

  it("shows an empty state when nothing is pending", async () => {
    vi.mocked(operatorApi.listSignUps).mockResolvedValue(paged([]));
    wrap(<OperatorSignUpsPage />);
    expect(await screen.findByTestId("signups-empty")).toBeTruthy();
  });
});

describe("OperatorUsersPage (M8.10, D-094)", () => {
  const users = [
    {
      id: "op",
      email: "op@x.example",
      status: "active" as const,
      workspaceName: "Default",
      operator: true,
      createdAt: "2026-01-01T00:00:00Z",
      statusChangedAt: null,
    },
    {
      id: "u1",
      email: "user@tenant.example",
      status: "active" as const,
      workspaceName: "Tenant",
      operator: false,
      createdAt: "2026-11-05T00:00:00Z",
      statusChangedAt: null,
    },
    {
      id: "u2",
      email: "gone@tenant.example",
      status: "disabled" as const,
      workspaceName: "Tenant",
      operator: false,
      createdAt: "2026-11-05T00:00:00Z",
      statusChangedAt: "2026-11-05T02:00:00Z",
    },
  ];

  it("offers no disable for the operator, disable for active users and re-enable for disabled ones", async () => {
    vi.mocked(operatorApi.listUsers).mockResolvedValue(paged(users));
    vi.mocked(operatorApi.disableUser).mockResolvedValue({ ...users[1], status: "disabled" });
    wrap(<OperatorUsersPage />);

    const rows = await screen.findAllByTestId("user-row");
    expect(rows[0].querySelector("button")).toBeNull();
    expect(rows[1].textContent).toContain("Disable");
    expect(rows[2].textContent).toContain("Re-enable");

    fireEvent.click(screen.getByText("Disable"));
    await waitFor(() => expect(operatorApi.disableUser).toHaveBeenCalledWith("u1"));
    expect(operatorApi.listUsers).toHaveBeenCalledWith(null, 0, 50);
  });
});
