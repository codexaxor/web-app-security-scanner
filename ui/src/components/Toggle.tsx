/**
 * An on/off toggle backed by a real checkbox (keeps role="checkbox" and keyboard behaviour). The input is a
 * full-size transparent overlay on top of the switch graphic, so a click anywhere on the switch toggles it and
 * nothing intercepts the hit. Shows the state as text. Controlled: `onChange` receives the next boolean.
 */
export default function Toggle({
  checked,
  onChange,
  disabled,
  label,
  testid,
}: {
  checked: boolean;
  onChange: (next: boolean) => void;
  disabled?: boolean;
  label: string;
  testid?: string;
}) {
  return (
    <label className="toggle">
      <span className="toggle-switch">
        <input
          type="checkbox"
          checked={checked}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked)}
          aria-label={label}
          data-testid={testid}
        />
        <span className="toggle-track" aria-hidden="true">
          <span className="toggle-knob" />
        </span>
      </span>
      <span className="toggle-state">{checked ? "On" : "Off"}</span>
    </label>
  );
}
