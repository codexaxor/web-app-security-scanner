import type { ButtonHTMLAttributes } from "react";

type Variant = "primary" | "secondary" | "ghost" | "danger";

/** The one button. One primary per view; secondary/ghost/danger for the rest. Forwards every native button
 *  prop (type, disabled, onClick, title, aria-*, data-testid). Defaults to type="button" so it never submits a
 *  form by accident. */
export default function Button({
  variant = "secondary",
  size,
  className,
  type,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: "sm" }) {
  const cls = ["btn", `btn-${variant}`, size === "sm" ? "btn-sm" : "", className ?? ""]
    .filter(Boolean)
    .join(" ");
  return <button type={type ?? "button"} className={cls} {...rest} />;
}
