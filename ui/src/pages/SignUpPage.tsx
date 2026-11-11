import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { RateLimitedError, signUp, type SignUpRequest } from "../api";
import Button from "../components/Button";
import InlineError from "../components/InlineError";
import { retryText, validateSignUp, MIN_PASSWORD_CHARS, type SignUpErrors } from "../lib/signupValidation";

/**
 * M8.10 public sign-up (D-093/D-098) at /signup (M8.13: a standalone public route, outside the app shell).
 * Whatever the email, a well-formed request ends on the SAME neutral confirmation — the page never says whether
 * an account already exists. The password lives only in component state for the submit and is cleared
 * afterwards; nothing goes to web storage.
 */
export default function SignUpPage() {
  const [form, setForm] = useState<SignUpRequest>({ email: "", password: "", workspaceName: "" });
  const [fieldErrors, setFieldErrors] = useState<SignUpErrors>({});
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

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
      await signUp(form);
      setForm({ email: "", password: "", workspaceName: "" });
      setDone(true);
    } catch (err) {
      setError(
        err instanceof RateLimitedError ? new Error(`Too many sign-up attempts. ${retryText(err.retryAfterSeconds)}`) : err,
      );
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    // The same text for every accepted request (D-098: no account enumeration).
    return (
      <div className="form signin-form" data-testid="signup-done">
        <h1>Check back soon</h1>
        <p className="signin-note">
          Thanks — your request was received. If it can be approved, the operator will set up your workspace and
          you can sign in with the email and password you chose.
        </p>
        <div className="signin-footer">
          <Link to="/login">Back to sign in</Link>
        </div>
      </div>
    );
  }

  return (
    <form onSubmit={submit} className="form signin-form" noValidate data-testid="signup-form">
      <h1>Create account</h1>
      <p className="muted">Request your own workspace. The operator approves every new workspace.</p>
      <div className="field">
        <label htmlFor="signup-email">Work email</label>
        <input
          id="signup-email"
          type="email"
          autoComplete="email"
          value={form.email}
          onChange={set("email")}
          aria-invalid={fieldErrors.email ? true : undefined}
          aria-describedby={fieldErrors.email ? "signup-email-error" : undefined}
        />
        {fieldErrors.email ? (
          <p className="field-error" id="signup-email-error">
            {fieldErrors.email}
          </p>
        ) : null}
      </div>
      <div className="field">
        <label htmlFor="signup-password">Password</label>
        <input
          id="signup-password"
          type="password"
          autoComplete="new-password"
          value={form.password}
          onChange={set("password")}
          aria-invalid={fieldErrors.password ? true : undefined}
          aria-describedby={fieldErrors.password ? "signup-password-error" : "signup-password-hint"}
        />
        {fieldErrors.password ? (
          <p className="field-error" id="signup-password-error">
            {fieldErrors.password}
          </p>
        ) : (
          <p className="muted field-hint" id="signup-password-hint">
            At least {MIN_PASSWORD_CHARS} characters.
          </p>
        )}
      </div>
      <div className="field">
        <label htmlFor="signup-workspace">Workspace name</label>
        <input
          id="signup-workspace"
          autoComplete="organization"
          value={form.workspaceName}
          onChange={set("workspaceName")}
          aria-invalid={fieldErrors.workspaceName ? true : undefined}
          aria-describedby={fieldErrors.workspaceName ? "signup-workspace-error" : undefined}
        />
        {fieldErrors.workspaceName ? (
          <p className="field-error" id="signup-workspace-error">
            {fieldErrors.workspaceName}
          </p>
        ) : null}
      </div>
      <InlineError error={error} variant="field" />
      <Button type="submit" variant="primary" busy={busy}>
        {busy ? "Sending…" : "Request account"}
      </Button>
      <div className="signin-footer">
        <span className="muted">Already have an account?</span>{" "}
        <Link to="/login">Sign in</Link>
      </div>
    </form>
  );
}
