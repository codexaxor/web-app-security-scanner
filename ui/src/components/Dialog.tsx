import { useEffect, useRef, type ReactNode } from "react";

const FOCUSABLE =
  'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

/**
 * Accessible dialog shell reused by every modal and the side panel. role="dialog" + aria-modal with the title as
 * the accessible name; Esc closes; Tab/Shift+Tab are trapped inside; a backdrop click closes; and focus returns
 * to whatever was focused when it opened (e.g. the feed row). `variant="drawer"` slides in from the right,
 * `"center"` is a centered modal.
 */
export default function Dialog({
  title,
  onClose,
  variant = "center",
  className,
  children,
}: {
  title: string;
  onClose: () => void;
  variant?: "center" | "drawer";
  className?: string;
  children: ReactNode;
}) {
  const boxRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null;
    const el = boxRef.current;
    const list = () => (el ? Array.from(el.querySelectorAll<HTMLElement>(FOCUSABLE)) : []);
    (list()[0] ?? el)?.focus();

    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") {
        e.stopPropagation();
        onClose();
        return;
      }
      if (e.key === "Tab") {
        const f = list();
        if (f.length === 0) {
          e.preventDefault();
          return;
        }
        const first = f[0];
        const last = f[f.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    }

    el?.addEventListener("keydown", onKey);
    return () => {
      el?.removeEventListener("keydown", onKey);
      prev?.focus?.();
    };
  }, [onClose]);

  const overlayCls = variant === "center" ? "overlay overlay-center" : "overlay";
  const boxCls = [variant === "center" ? "modal" : "panel", className ?? ""].filter(Boolean).join(" ");

  return (
    <div
      className={overlayCls}
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div ref={boxRef} className={boxCls} role="dialog" aria-modal="true" aria-label={title} tabIndex={-1}>
        {children}
      </div>
    </div>
  );
}
