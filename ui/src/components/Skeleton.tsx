/**
 * Loading skeletons (design pass, item 2). Pure presentational placeholders shown while a query is loading so
 * the layout does not jump. `Skeleton` is a single shimmer block; `TableSkeleton` fills a table body with
 * placeholder rows that match the real column count. The shimmer respects prefers-reduced-motion (CSS).
 */
export function Skeleton({ width, className }: { width?: string; className?: string }) {
  return (
    <span
      className={["skeleton", className ?? ""].filter(Boolean).join(" ")}
      style={width ? { width } : undefined}
      aria-hidden="true"
    />
  );
}

export function TableSkeleton({ columns, rows = 6 }: { columns: number; rows?: number }) {
  return (
    <tbody aria-hidden="true" data-testid="table-skeleton">
      {Array.from({ length: rows }, (_, r) => (
        <tr key={r} className="skeleton-row">
          {Array.from({ length: columns }, (_, c) => (
            <td key={c}>
              <span className="skeleton" />
            </td>
          ))}
        </tr>
      ))}
    </tbody>
  );
}
