import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from "react";

/**
 * The single toast system (M8.8 item 6). Transient, non-blocking feedback — success, info, and transient errors
 * (e.g. an action that failed but is not tied to a visible form). Toasts render in one polite aria-live region
 * (announced without stealing focus), auto-dismiss after a few seconds, and skip the slide-in animation when the
 * viewer prefers reduced motion. Errors tied to a form are shown inline next to the field (see InlineError), and
 * confirmations stay in dialogs — never here.
 */
export type ToastKind = "success" | "info" | "error";

export interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
}

interface ToastApi {
  success: (message: string) => void;
  info: (message: string) => void;
  error: (message: string) => void;
  dismiss: (id: number) => void;
  toasts: Toast[];
}

const Ctx = createContext<ToastApi | null>(null);

// Errors linger a little longer than success/info so they are not missed.
const DEFAULT_MS: Record<ToastKind, number> = { success: 4000, info: 4000, error: 7000 };

export function ToastProvider({ children, autoDismiss = true }: { children: ReactNode; autoDismiss?: boolean }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(1);

  const dismiss = useCallback((id: number) => {
    setToasts((list) => list.filter((t) => t.id !== id));
  }, []);

  const push = useCallback(
    (kind: ToastKind, message: string) => {
      const id = nextId.current++;
      setToasts((list) => [...list, { id, kind, message }]);
      if (autoDismiss) {
        setTimeout(() => dismiss(id), DEFAULT_MS[kind]);
      }
    },
    [autoDismiss, dismiss],
  );

  const api: ToastApi = {
    success: (m) => push("success", m),
    info: (m) => push("info", m),
    error: (m) => push("error", m),
    dismiss,
    toasts,
  };

  return (
    <Ctx.Provider value={api}>
      {children}
      <ToastRegion toasts={toasts} onDismiss={dismiss} />
    </Ctx.Provider>
  );
}

function ToastRegion({ toasts, onDismiss }: { toasts: Toast[]; onDismiss: (id: number) => void }) {
  return (
    <div className="toast-region" role="status" aria-live="polite" aria-atomic="false">
      {toasts.map((t) => (
        <div key={t.id} className={`toast toast-${t.kind}`}>
          <span className="toast-message">{t.message}</span>
          <button
            type="button"
            className="toast-close"
            aria-label="Dismiss notification"
            onClick={() => onDismiss(t.id)}
          >
            ×
          </button>
        </div>
      ))}
    </div>
  );
}

export function useToast(): ToastApi {
  const c = useContext(Ctx);
  if (!c) {
    throw new Error("useToast must be used within ToastProvider");
  }
  return c;
}

/** Turns an unknown thrown value (ApiError / Error / anything) into a safe, plain message for a toast. */
export function errorMessage(error: unknown, fallback = "Something went wrong."): string {
  if (error && typeof error === "object" && "message" in error && typeof (error as { message: unknown }).message === "string") {
    return (error as { message: string }).message;
  }
  return fallback;
}
