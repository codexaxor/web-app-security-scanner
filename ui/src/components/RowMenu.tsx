import { useEffect, useRef, useState, type ReactNode } from "react";
import Icon from "./icons";

/**
 * A row/header "⋮" overflow menu (M8.6). The trigger and popover are kept inside a wrapper that stops click
 * propagation, so opening the menu or using an item never triggers an enclosing clickable row. The popover
 * closes on Escape, on an outside click, and when the caller invokes the `close` passed to `children`.
 * Keyboard accessible: the trigger is a real button with aria-haspopup/aria-expanded; items are plain buttons.
 */
export default function RowMenu({
  label = "Row actions",
  children,
}: {
  label?: string;
  children: (close: () => void) => ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onDoc);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDoc);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  return (
    <div className="rowmenu" ref={ref} onClick={(e) => e.stopPropagation()}>
      <button
        type="button"
        className="rowmenu-trigger"
        aria-haspopup="true"
        aria-expanded={open}
        aria-label={label}
        onClick={() => setOpen((v) => !v)}
      >
        <Icon name="dots" className="rowmenu-dots" />
      </button>
      {open ? <div className="rowmenu-pop">{children(() => setOpen(false))}</div> : null}
    </div>
  );
}
