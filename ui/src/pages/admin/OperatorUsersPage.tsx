import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Button from "../../components/Button";
import InlineError from "../../components/InlineError";
import Pager from "../../components/Pager";
import { Card, EmptyState, PageHeader } from "../../components/ui";
import { errorMessage, useToast } from "../../toast";
import { operatorApi, type AccountStatus } from "../../api/signups";

const PAGE_SIZE = 50;
export const usersKey = ["admin", "users"] as const;

/**
 * M8.10 operator view (D-094): every account with its workspace and status; disable or re-enable. The operator's
 * own row cannot be disabled (the server answers 409 CANNOT_DISABLE_OPERATOR; the button is not offered). A
 * disabled user's session ends on their next request. NEW, not yet routed (S11).
 */
export default function OperatorUsersPage() {
  const toast = useToast();
  const client = useQueryClient();
  const [status, setStatus] = useState<AccountStatus | null>(null);
  const [page, setPage] = useState(0);
  const list = useQuery({
    queryKey: [...usersKey, status ?? "all", page],
    queryFn: () => operatorApi.listUsers(status, page, PAGE_SIZE),
  });

  const change = useMutation({
    mutationFn: ({ id, enable }: { id: string; enable: boolean }) =>
      enable ? operatorApi.enableUser(id) : operatorApi.disableUser(id),
    onSuccess: async (view) => {
      toast.success(view.status === "active" ? `Re-enabled ${view.email}.` : `Disabled ${view.email}.`);
      await client.invalidateQueries({ queryKey: ["admin"] });
    },
    onError: (e) => toast.error(errorMessage(e, "Could not change the account.")),
  });

  const rows = list.data?.content ?? [];
  const filters: { label: string; value: AccountStatus | null }[] = [
    { label: "All", value: null },
    { label: "Active", value: "active" },
    { label: "Disabled", value: "disabled" },
  ];
  return (
    <section>
      <PageHeader title="Accounts" subtitle="Every account on this installation, with its workspace." />
      <div className="row" role="group" aria-label="Filter by status">
        {filters.map((f) => (
          <Button
            key={f.label}
            size="sm"
            variant={f.value === status ? "primary" : "secondary"}
            aria-pressed={f.value === status}
            onClick={() => {
              setStatus(f.value);
              setPage(0);
            }}
          >
            {f.label}
          </Button>
        ))}
      </div>
      <InlineError error={list.error} />
      <Card>
        {!list.isLoading && rows.length === 0 ? (
          <EmptyState title="No accounts" testId="users-empty" />
        ) : (
          <table className="table" data-testid="users-table">
            <thead>
              <tr>
                <th>Email</th>
                <th>Workspace</th>
                <th>Status</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {rows.map((u) => (
                <tr key={u.id} data-testid="user-row">
                  <td>
                    {u.email}
                    {u.operator ? <span className="chip chip-todo"> Operator</span> : null}
                  </td>
                  <td>{u.workspaceName}</td>
                  <td>{u.status === "active" ? "Active" : "Disabled"}</td>
                  <td>
                    {u.operator ? null : u.status === "active" ? (
                      <Button
                        size="sm"
                        variant="danger"
                        disabled={change.isPending}
                        onClick={() => change.mutate({ id: u.id, enable: false })}
                      >
                        Disable
                      </Button>
                    ) : (
                      <Button
                        size="sm"
                        disabled={change.isPending}
                        onClick={() => change.mutate({ id: u.id, enable: true })}
                      >
                        Re-enable
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
      {list.data ? (
        <Pager
          page={list.data.page}
          size={list.data.size}
          totalElements={list.data.totalElements}
          totalPages={list.data.totalPages}
          onPage={setPage}
        />
      ) : null}
    </section>
  );
}
