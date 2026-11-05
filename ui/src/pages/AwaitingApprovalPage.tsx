import Button from "../components/Button";
import { Card, PageHeader } from "../components/ui";

/**
 * M8.10: shown after a sign-in with the correct password for an account that is not usable yet — a pending
 * sign-up (403 ACCOUNT_PENDING) or a disabled account (403 ACCOUNT_INACTIVE). NEW, not yet routed (S11).
 */
export default function AwaitingApprovalPage({
  reason,
  onBack,
}: {
  reason: "pending" | "inactive";
  onBack?: () => void;
}) {
  const pending = reason === "pending";
  return (
    <section data-testid={pending ? "account-pending" : "account-inactive"}>
      <PageHeader title={pending ? "Waiting for approval" : "Account disabled"} />
      <Card>
        <p>
          {pending
            ? "Your sign-up request has not been approved yet. An operator reviews every new workspace; you can sign in as soon as it is approved."
            : "This account has been disabled by the operator. Contact them if you think this is a mistake."}
        </p>
        {onBack ? <Button onClick={onBack}>Back to sign in</Button> : null}
      </Card>
    </section>
  );
}
