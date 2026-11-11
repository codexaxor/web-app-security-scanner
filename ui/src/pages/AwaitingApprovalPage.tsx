/**
 * M8.10: shown in place of the sign-in form after a sign-in with the CORRECT password for a sign-up the operator
 * has not approved yet (403 ACCOUNT_PENDING). It only ever follows a correct password, so it reveals nothing to
 * someone without it. (A disabled account — 403 ACCOUNT_INACTIVE — gets an inline message on the form instead.)
 */
export default function AwaitingApprovalPage({ onBack }: { onBack: () => void }) {
  return (
    <div className="form signin-form" data-testid="account-pending" role="status">
      <h1>Waiting for approval</h1>
      <p className="signin-note">
        Your sign-up request has not been approved yet. The operator reviews every new workspace — you can sign in
        as soon as yours is approved.
      </p>
      <div className="signin-footer">
        <button type="button" className="btn-link" onClick={onBack}>
          Back to sign in
        </button>
      </div>
    </div>
  );
}
