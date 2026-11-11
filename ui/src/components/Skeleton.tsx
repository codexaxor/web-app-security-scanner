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

/** A small stack of shimmer lines, for non-table loading areas (panels, wizards, drawers). */
export function SkeletonLines({ lines = 3 }: { lines?: number }) {
  return (
    <div className="skeleton-lines" aria-hidden="true" data-testid="skeleton-lines">
      {Array.from({ length: lines }, (_, i) => (
        <span key={i} className="skeleton" />
      ))}
    </div>
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
