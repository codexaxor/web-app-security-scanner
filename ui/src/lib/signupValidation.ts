// M8.10 sign-up: a client-side mirror of the server's validation (SignupService) so the form can explain a
// problem before submitting. The server stays authoritative. Pure, unit-tested.
import type { SignUpRequest } from "../api";

export const MIN_PASSWORD_CHARS = 2; // D-101: temporarily relaxed from 12; restore before first external tenant
export const MAX_PASSWORD_BYTES = 72; // bcrypt's input limit
export const MAX_WORKSPACE_NAME = 80;
/** The seeded Default workspace's name: reserved at sign-up (M8.10 review N-6). */
export const RESERVED_WORKSPACE_NAME = "Default";

export type SignUpErrors = Partial<Record<keyof SignUpRequest, string>>;

const CONTROL_CHARS = /[\u0000-\u001f\u007f]/;

export function validateSignUp(req: SignUpRequest): SignUpErrors {
  const errors: SignUpErrors = {};
  const email = req.email.trim();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    errors.email = "Enter a valid email address.";
  } else if (!/^[\x21-\x7e]+$/.test(email)) {
    errors.email = "Use an email address with ASCII characters only.";
  }
  if ([...req.password].length < MIN_PASSWORD_CHARS) {
    errors.password = `Use at least ${MIN_PASSWORD_CHARS} characters.`;
  } else if (new TextEncoder().encode(req.password).length > MAX_PASSWORD_BYTES) {
    errors.password = `Use at most ${MAX_PASSWORD_BYTES} bytes (fewer characters, or fewer emoji).`;
  }
  const name = req.workspaceName.trim();
  if (name.length < 1 || name.length > MAX_WORKSPACE_NAME || CONTROL_CHARS.test(name)) {
    errors.workspaceName = `Name your workspace (1–${MAX_WORKSPACE_NAME} characters).`;
  } else if (name.toLowerCase() === RESERVED_WORKSPACE_NAME.toLowerCase()) {
    errors.workspaceName = `"${RESERVED_WORKSPACE_NAME}" is reserved. Choose another name.`;
  }
  return errors;
}

/** "try again in N minutes" text from a Retry-After in seconds (at least 1 minute; null → no number). */
export function retryText(retryAfterSeconds: number | null): string {
  if (retryAfterSeconds == null) return "Try again later.";
  const min = Math.max(1, Math.ceil(retryAfterSeconds / 60));
  return `Try again in ${min} ${min === 1 ? "minute" : "minutes"}.`;
}
