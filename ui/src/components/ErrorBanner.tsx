import { ApiError } from "../api";

/**
 * Renders a 4xx/5xx error from the API as a dismissable banner using the server's own {error, message} body
 * (OD-14 / change 9). Everything shown is plain text (OD-13d). A 401 is handled by the auth layer, not here.
 */
export default function ErrorBanner({ error, onDismiss }: { error: unknown; onDismiss?: () => void }) {
  if (!error) {
    return null;
  }
  const message =
    error instanceof ApiError
      ? error.message
      : error instanceof Error
        ? error.message
        : "Something went wrong.";
  const code = error instanceof ApiError ? error.code : null;
  return (
    <div className="banner banner-error" role="alert">
      <span>
        {code ? <strong>{code}: </strong> : null}
        {message}
      </span>
      {onDismiss ? (
        <button className="banner-close" onClick={onDismiss} aria-label="Dismiss">
          ×
        </button>
      ) : null}
    </div>
  );
}
