import { useSearchParams } from "react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, type SignupStatus } from "../../api";
import { qk } from "../../queryClient";
import Button from "../../components/Button";
import InlineError from "../../components/InlineError";
import PageLayout from "../../components/PageLayout";
import Pager, { useUrlPaging } from "../../components/Pager";
import RelativeTime from "../../components/RelativeTime";
import { TableSkeleton } from "../../components/Skeleton";
import { EmptyState } from "../../components/ui";
import { errorMessage, useToast } from "../../toast";

const STATUSES: { value: SignupStatus; label: string }[] = [
  { value: "pending", label: "Pending" },
  { value: "approved", label: "Approved" },
  { value: "rejected", label: "Rejected" },
];

function statusOf(raw: string | null): SignupStatus {
  return STATUSES.some((s) => s.value === raw) ? (raw as SignupStatus) : "pending";
}

/**
 * M8.10 operator: sign-up requests (D-094 — account metadata only; the operator never sees a tenant's
 * business data). Approving creates the workspace and its account in one transaction; rejecting frees the email
 * for a later sign-up. The route exists for everyone, but /api/v1/admin/** answers 403 to non-operators.
 */
export default function OperatorSignUpsPage() {
  const toast = useToast();
  const client = useQueryClient();
  const [sp, setSp] = useSearchParams();
  const status = statusOf(sp.get("status"));
  const { page, size, setPage, setSize } = useUrlPaging("page", "size");

  const list = useQuery({
    queryKey: qk.adminSignUps(status, page, size),
    queryFn: () => api.listSignUps(status, page, size),
  });

  const decide = useMutation({
    mutationFn: ({ id, approve }: { id: string; approve: boolean }) =>
      approve ? api.approveSignUp(id) : api.rejectSignUp(id),
    onSuccess: async (view) => {
      toast.success(view.status === "approved" ? `Approved ${view.email}.` : `Rejected ${view.email}.`);
      await client.invalidateQueries({ queryKey: qk.adminAll });
    },
    onError: (e) => toast.error(errorMessage(e, "Could not record the decision.")),
  });

  const setStatus = (next: string) => {
    const q = new URLSearchParams(sp);
    q.set("status", next);
    q.delete("page");
    setSp(q, { replace: true });
  };

  const rows = list.data?.content ?? [];
  return (
    <PageLayout
      title="Sign-ups"
      subtitle="Approve a request to create its workspace and account."
      toolbar={
        <select aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
          {STATUSES.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
      }
    >
      {list.error ? <InlineError error={list.error} /> : null}
      {list.data && rows.length === 0 ? (
        <EmptyState title={`No ${status} sign-ups`} testId="signups-empty">
          {status === "pending" ? "New requests appear here for approval." : "Nothing here yet."}
        </EmptyState>
      ) : (
        <div className="table-wrap">
          <table className="table" data-testid="signups-table">
            <thead>
              <tr>
                <th>Email</th>
                <th>Workspace</th>
                <th>Requested</th>
                <th>{status === "pending" ? <span className="visually-hidden">Actions</span> : "Decided"}</th>
              </tr>
            </thead>
            {list.isLoading ? (
              <TableSkeleton columns={4} />
            ) : (
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id} data-testid="signup-row">
                    <td>
                      <span className="truncate op-ident" title={r.email}>
                        {r.email}
                      </span>
                    </td>
                    <td>
                      <span className="truncate op-ident" title={r.workspaceName}>
                        {r.workspaceName}
                      </span>
                    </td>
                    <td className="nowrap">
                      <RelativeTime iso={r.createdAt} />
                    </td>
                    <td className="cell-actions">
                      {r.status === "pending" ? (
                        <div className="row">
                          <Button
                            size="sm"
                            variant="primary"
                            busy={decide.isPending && decide.variables?.id === r.id && decide.variables.approve}
                            disabled={decide.isPending}
                            onClick={() => decide.mutate({ id: r.id, approve: true })}
                            aria-label={`Approve ${r.email}`}
                          >
                            Approve
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            disabled={decide.isPending}
                            onClick={() => decide.mutate({ id: r.id, approve: false })}
                            aria-label={`Reject ${r.email}`}
                          >
                            Reject
                          </Button>
                        </div>
                      ) : r.decidedAt ? (
                        <RelativeTime iso={r.decidedAt} />
                      ) : null}
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
    </PageLayout>
  );
}
