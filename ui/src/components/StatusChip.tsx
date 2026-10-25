// Issue status as a color-coded chip. new = blue, todo = neutral grey, ignored = muted, solved = green.
// The visible label is the exact text the Feed has always shown ("New", "To Do", "Ignored", "Solved").

type StatusKind = "new" | "todo" | "ignored" | "solved";

const LABEL: Record<StatusKind, string> = {
  new: "New",
  todo: "To Do",
  ignored: "Ignored",
  solved: "Solved",
};

export default function StatusChip({ kind }: { kind: StatusKind }) {
  return <span className={`chip chip-${kind}`}>{LABEL[kind]}</span>;
}

export type { StatusKind };
