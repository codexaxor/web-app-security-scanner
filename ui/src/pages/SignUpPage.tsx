import { useState, type FormEvent } from "react";
import Button from "../components/Button";
import InlineError from "../components/InlineError";
import { Card, PageHeader } from "../components/ui";
import { RateLimitedError, signUp, validateSignUp, type SignUpRequest } from "../api/signups";

/**
 * M8.10 public sign-up (D-093/D-098). NEW, not yet routed (S11). Whatever the email, a well-formed request gets
 * the same confirmation — the page never says whether an account already exists. The password is held only in
 * component state for the submit and cleared afterwards; nothing goes to web storage.
 */
export default function SignUpPage({ onSignIn }: { onSignIn?: () => void }) {
  const [form, setForm] = useState<SignUpRequest>({ email: "", password: "", workspaceName: "" });
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<keyof SignUpRequest, string>>>({});
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string | null>(null);

  const set = (k: keyof SignUpRequest) => (e: { target: { value: string } }) =>
    setForm((f) => ({ ...f, [k]: e.target.value }));

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const errs = validateSignUp(form);
    setFieldErrors(errs);
    if (Object.keys(errs).length > 0) {
      return;
    }
    setBusy(true);
    try {
      const res = await signUp(form);
      setDone(res.message);
      setForm({ email: "", password: "", workspaceName: "" });
    } catch (err) {
      setError(
        err instanceof RateLimitedError
          ? new Error(
              err.retryAfterSeconds
                ? `Too many sign-up attempts. Try again in ${Math.ceil(err.retryAfterSeconds / 60)} min.`
                : "Too many sign-up attempts. Try again later.",
            )
          : err,
      );
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <section data-testid="signup-done">
        <PageHeader title="Request received" />
        <Card>
          <p>{done}</p>
          <p className="muted">An operator reviews every request. You can sign in once it is approved.</p>
          {onSignIn ? (
            <Button variant="primary" onClick={onSignIn}>
              Back to sign in
            </Button>
          ) : null}
        </Card>
      </section>
    );
  }

  return (
    <section>
      <PageHeader title="Create a workspace" subtitle="Request an account. An operator approves new workspaces." />
      <Card>
        <form onSubmit={submit} noValidate data-testid="signup-form">
          <label className="field-label" htmlFor="signup-email">
            Work email
          </label>
          <input id="signup-email" type="email" autoComplete="email" value={form.email} onChange={set("email")} />
          {fieldErrors.email ? <p className="field-error" role="alert">{fieldErrors.email}</p> : null}

          <label className="field-label" htmlFor="signup-password">
            Password
          </label>
          <input
            id="signup-password"
            type="password"
            autoComplete="new-password"
            value={form.password}
            onChange={set("password")}
          />
          {fieldErrors.password ? <p className="field-error" role="alert">{fieldErrors.password}</p> : null}

          <label className="field-label" htmlFor="signup-workspace">
            Workspace name
          </label>
          <input id="signup-workspace" value={form.workspaceName} onChange={set("workspaceName")} />
          {fieldErrors.workspaceName ? (
            <p className="field-error" role="alert">{fieldErrors.workspaceName}</p>
          ) : null}

          <InlineError error={error} />
          <Button type="submit" variant="primary" busy={busy}>
            Request account
          </Button>
          {onSignIn ? (
            <Button variant="ghost" onClick={onSignIn}>
              I already have an account
            </Button>
          ) : null}
        </form>
      </Card>
    </section>
  );
}
