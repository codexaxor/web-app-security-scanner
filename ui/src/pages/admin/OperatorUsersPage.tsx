import { useState } from "react";
import { useSearchParams } from "react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, type AccountStatus, type UserAdminView } from "../../api";
import { qk } from "../../queryClient";
import Button from "../../components/Button";
import ConfirmDialog from "../../components/ConfirmDialog";
import InlineError from "../../components/InlineError";
import PageLayout from "../../components/PageLayout";
import Pager, { useUrlPaging } from "../../components/Pager";
import RelativeTime from "../../components/RelativeTime";
import { TableSkeleton } from "../../components/Skeleton";
import { EmptyState } from "../../components/ui";
import { errorMessage, useToast } from "../../toast";

function statusOf(raw: string | null): AccountStatus | null {
  return raw === "active" || raw === "disabled" ? raw : null;
}

/**
 * M8.10 operator: every account with its workspace and status; disable or re-enable (D-094 — account metadata
 * only). The operator's own row offers no Disable (the server also refuses: 409 CANNOT_DISABLE_OPERATOR). A
 * disabled user's session ends on their next request (ActiveAccountFilter).
 */
export default function OperatorUsersPage() {
  const toast = useToast();
  const client = useQueryClient();
  const [sp, setSp] = useSearchParams();
  const status = statusOf(sp.get("status"));
  const { page, size, setPage, setSize } = useUrlPaging("page", "size");
  const [confirm, setConfirm] = useState<UserAdminView | null>(null);

  const list = useQuery({
    queryKey: qk.adminUsers(status ?? "all", page, size),
    queryFn: () => api.listUsers(status, page, size),
  });

  const change = useMutation({
    mutationFn: ({ id, enable }: { id: string; enable: boolean }) =>
      enable ? api.enableUser(id) : api.disableUser(id),
    onSuccess: async (view) => {
      setConfirm(null);
      toast.success(view.status === "active" ? `Re-enabled ${view.email}.` : `Disabled ${view.email}.`);
      await client.invalidateQueries({ queryKey: qk.adminAll });
    },
    onError: (e) => toast.error(errorMessage(e, "Could not change the account.")),
  });

  const setStatus = (next: string) => {
    const q = new URLSearchParams(sp);
    if (next) q.set("status", next);
    else q.delete("status");
    q.delete("page");
    setSp(q, { replace: true });
  };

  const rows = list.data?.content ?? [];
  return (
    <PageLayout
      title="Users"
      subtitle="Every account on this installation, with its workspace."
      toolbar={
        <select aria-label="Status" value={status ?? ""} onChange={(e) => setStatus(e.target.value)}>
          <option value="">All accounts</option>
          <option value="active">Active</option>
          <option value="disabled">Disabled</option>
        </select>
      }
    >
      {list.error ? <InlineError error={list.error} /> : null}
      {list.data && rows.length === 0 ? (
        <EmptyState title="No accounts" testId="users-empty">
          {status ? "No account has this status." : "Approved sign-ups appear here."}
        </EmptyState>
      ) : (
        <div className="table-wrap">
          <table className="table" data-testid="users-table">
            <thead>
              <tr>
                <th>Email</th>
                <th>Workspace</th>
                <th>Status</th>
                <th>Created</th>
                <th>
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            {list.isLoading ? (
              <TableSkeleton columns={5} />
            ) : (
              <tbody>
                {rows.map((u) => (
                  <tr key={u.id} data-testid="user-row">
                    <td>
                      <div className="row">
                        <span className="truncate op-ident" title={u.email}>
                          {u.email}
                        </span>
                        {u.operator ? <span className="chip chip-new">Operator</span> : null}
                      </div>
                    </td>
                    <td>
                      <span className="truncate op-ident" title={u.workspaceName}>
                        {u.workspaceName}
                      </span>
                    </td>
                    <td>
                      {u.status === "active" ? (
                        <span className="chip chip-solved">Active</span>
                      ) : (
                        <span className="chip chip-ignored">Disabled</span>
                      )}
                    </td>
                    <td className="nowrap">
                      <RelativeTime iso={u.createdAt} />
                    </td>
                    <td className="cell-actions">
                      {u.operator ? null : u.status === "active" ? (
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={change.isPending}
                          onClick={() => setConfirm(u)}
                          aria-label={`Disable ${u.email}`}
                        >
                          Disable
                        </Button>
                      ) : (
                        <Button
                          size="sm"
                          variant="secondary"
                          busy={change.isPending && change.variables?.id === u.id}
                          disabled={change.isPending}
                          onClick={() => change.mutate({ id: u.id, enable: true })}
                          aria-label={`Re-enable ${u.email}`}
                        >
                          Re-enable
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            )}
          </table>
          <Pager
            page={page}
            size={size}
            totalElements={list.data?.totalElements ?? 0}
            totalPages={list.data?.totalPages ?? 1}
            onPage={setPage}
            onSize={setSize}
          />
        </div>
      )}
      {confirm ? (
        <ConfirmDialog
          title={`Disable ${confirm.email}?`}
          confirmLabel="Disable account"
          busy={change.isPending}
          onConfirm={() => change.mutate({ id: confirm.id, enable: false })}
          onCancel={() => setConfirm(null)}
        >
          <p>
            They are signed out on their next request and cannot sign in until you re-enable the account. Their
            workspace and its data are kept.
          </p>
        </ConfirmDialog>
      ) : null}
    </PageLayout>
  );
}
