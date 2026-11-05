import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Button from "../../components/Button";
import InlineError from "../../components/InlineError";
import Pager from "../../components/Pager";
import { Card, EmptyState, PageHeader } from "../../components/ui";
import { errorMessage, useToast } from "../../toast";
import { operatorApi, type SignupStatus } from "../../api/signups";

const PAGE_SIZE = 50;
/** Local query-key family (queryClient.ts is not touched before S11). */
export const signUpsKey = ["admin", "sign-ups"] as const;

/**
 * M8.10 operator view (D-094): pending / decided sign-up requests, approve or reject. Account metadata only —
 * the operator never sees a tenant's business data. NEW, not yet routed (S11).
 */
export default function OperatorSignUpsPage() {
  const toast = useToast();
  const client = useQueryClient();
  const [status, setStatus] = useState<SignupStatus>("pending");
  const [page, setPage] = useState(0);
  const list = useQuery({
    queryKey: [...signUpsKey, status, page],
    queryFn: () => operatorApi.listSignUps(status, page, PAGE_SIZE),
  });

  const decide = useMutation({
    mutationFn: ({ id, approve }: { id: string; approve: boolean }) =>
      approve ? operatorApi.approveSignUp(id) : operatorApi.rejectSignUp(id),
    onSuccess: async (view) => {
      toast.success(view.status === "approved" ? `Approved ${view.email}.` : `Rejected ${view.email}.`);
      await client.invalidateQueries({ queryKey: ["admin"] });
    },
    onError: (e) => toast.error(errorMessage(e, "Could not record the decision.")),
  });

  const rows = list.data?.content ?? [];
  return (
    <section>
      <PageHeader title="Sign-up requests" subtitle="Approve a request to create its workspace and account." />
      <div className="row" role="group" aria-label="Filter by status">
        {(["pending", "approved", "rejected"] as const).map((s) => (
          <Button
            key={s}
            size="sm"
            variant={s === status ? "primary" : "secondary"}
            aria-pressed={s === status}
            onClick={() => {
              setStatus(s);
              setPage(0);
            }}
          >
            {s.charAt(0).toUpperCase() + s.slice(1)}
          </Button>
        ))}
      </div>
      <InlineError error={list.error} />
      <Card>
        {!list.isLoading && rows.length === 0 ? (
          <EmptyState title={`No ${status} requests`} testId="signups-empty" />
        ) : (
          <table className="table" data-testid="signups-table">
            <thead>
              <tr>
                <th>Email</th>
                <th>Workspace</th>
                <th>Requested</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} data-testid="signup-row">
                  <td>{r.email}</td>
                  <td>{r.workspaceName}</td>
                  <td>{new Date(r.createdAt).toLocaleString()}</td>
                  <td>
                    {r.status === "pending" ? (
                      <>
                        <Button
                          size="sm"
                          variant="primary"
                          busy={decide.isPending && decide.variables?.id === r.id && decide.variables.approve}
                          disabled={decide.isPending}
                          onClick={() => decide.mutate({ id: r.id, approve: true })}
                        >
                          Approve
                        </Button>
                        <Button
                          size="sm"
                          variant="danger"
                          disabled={decide.isPending}
                          onClick={() => decide.mutate({ id: r.id, approve: false })}
                        >
                          Reject
                        </Button>
                      </>
                    ) : (
                      <span className="muted">{r.status}</span>
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
