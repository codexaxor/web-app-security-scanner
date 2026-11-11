import { ApiError } from "../api";

/**
 * An inline error shown in place of (or beside) the content it belongs to — a list that failed to load, or a
 * form/field that was rejected (M8.8 item 6). It renders where the content is, never pinned at the bottom of the
 * page, and stays until the error clears (unlike a toast). Everything shown is plain text (D-067(d)); a 401 is
 * handled by the auth layer, not here. Replaces the old bottom ErrorBanner.
 */
export default function InlineError({
  error,
  variant = "block",
}: {
  error: unknown;
  variant?: "block" | "field";
}) {
  if (!error) {
    return null;
  }
  const message =
    error instanceof ApiError
      ? error.message
      : error instanceof Error
        ? error.message
        : "Something went wrong.";
  if (variant === "field") {
    return (
      <p className="field-error" role="alert">
        {message}
      </p>
    );
  }
  return (
    <div className="inline-error" role="alert">
      {message}
    </div>
  );
}
