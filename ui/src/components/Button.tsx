import type { ButtonHTMLAttributes } from "react";

type Variant = "primary" | "secondary" | "ghost" | "danger";

/** The one button. One primary per view; secondary/ghost/danger for the rest. Forwards every native button
 *  prop (type, disabled, onClick, title, aria-*, data-testid). Defaults to type="button" so it never submits a
 *  form by accident. `busy` adds a spinner, sets aria-busy, and disables the button (M8.8 item 10). */
export default function Button({
  variant = "secondary",
  size,
  className,
  type,
  busy,
  disabled,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: "sm"; busy?: boolean }) {
  const cls = ["btn", `btn-${variant}`, size === "sm" ? "btn-sm" : "", busy ? "is-busy" : "", className ?? ""]
    .filter(Boolean)
    .join(" ");
  return (
    <button
      type={type ?? "button"}
      className={cls}
      aria-busy={busy || undefined}
      disabled={disabled || busy || undefined}
      {...rest}
    />
  );
}
