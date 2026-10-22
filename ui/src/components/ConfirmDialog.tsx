import type { ReactNode } from "react";
import Dialog from "./Dialog";
import Button from "./Button";

/** A small confirm/cancel modal for destructive or irreversible actions (delete rule, remove connection). */
export default function ConfirmDialog({
  title,
  children,
  confirmLabel = "Confirm",
  confirmVariant = "danger",
  busy,
  onConfirm,
  onCancel,
}: {
  title: string;
  children: ReactNode;
  confirmLabel?: string;
  confirmVariant?: "primary" | "danger";
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <Dialog title={title} onClose={onCancel} variant="center">
      <h2>{title}</h2>
      <div className="stack">{children}</div>
      <div className="modal-actions">
        <Button variant="secondary" onClick={onCancel} disabled={busy}>
          Cancel
        </Button>
        <Button variant={confirmVariant} onClick={onConfirm} disabled={busy}>
          {confirmLabel}
        </Button>
      </div>
    </Dialog>
  );
}
