import { useSearchParams } from "react-router";

export const PAGE_SIZES = [25, 50, 100] as const;
export const DEFAULT_PAGE_SIZE = 50;

function normalizeSize(n: number): number {
  return (PAGE_SIZES as readonly number[]).includes(n) ? n : DEFAULT_PAGE_SIZE;
}

/**
 * The one shared pager for every list (M8.8 item 8): "1–50 of 282", a 25/50/100 size selector, and Prev/Next.
 * Pure/presentational — the page + size live wherever the caller keeps them (the URL for a page-level list via
 * {@link useUrlPaging}, or local state for a side-panel/drawer list). Returns nothing for an empty list (the
 * empty state covers that case).
 */
export default function Pager({
  page,
  size,
  totalElements,
  totalPages,
  onPage,
  onSize,
}: {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  onPage: (page: number) => void;
  onSize?: (size: number) => void;
}) {
  if (totalElements <= 0) {
    return null;
  }
  const from = page * size + 1;
  const to = Math.min((page + 1) * size, totalElements);
  const lastPage = Math.max(totalPages, 1);
  return (
    <div className="pager" data-testid="pager">
      <span className="pager-range" data-testid="pager-range">
        {from}–{to} of {totalElements}
      </span>
      {onSize ? (
        <select
          className="pager-size"
          aria-label="Rows per page"
          value={size}
          onChange={(e) => onSize(Number(e.target.value))}
        >
          {PAGE_SIZES.map((s) => (
            <option key={s} value={s}>
              {s} / page
            </option>
          ))}
        </select>
      ) : null}
      <div className="pager-nav">
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          disabled={page <= 0}
          onClick={() => onPage(page - 1)}
        >
          Previous
        </button>
        <span className="muted">
          Page {page + 1} of {lastPage}
        </span>
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          disabled={page + 1 >= lastPage}
          onClick={() => onPage(page + 1)}
        >
          Next
        </button>
      </div>
    </div>
  );
}

/**
 * Keeps a list's page + size in the URL (M8.8 item 8), so a paged list is shareable/back-button-friendly. Each
 * list on a page uses its own keys (e.g. Ignored uses "page"/"size" and "rulesPage"/"rulesSize"). Page 0 and the
 * default size are omitted from the URL to keep it clean. Side-panel/drawer lists use local state instead.
 */
export function useUrlPaging(pageKey: string, sizeKey: string, defaultSize = DEFAULT_PAGE_SIZE) {
  const [sp, setSp] = useSearchParams();
  const page = Math.max(0, Number(sp.get(pageKey) || "0"));
  const size = normalizeSize(Number(sp.get(sizeKey) || defaultSize));

  const setPage = (p: number) =>
    setSp(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (p <= 0) {
          next.delete(pageKey);
        } else {
          next.set(pageKey, String(p));
        }
        return next;
      },
      { replace: true },
    );

  const setSize = (s: number) =>
    setSp(
      (prev) => {
        const next = new URLSearchParams(prev);
        const normalized = normalizeSize(s);
        if (normalized === defaultSize) {
          next.delete(sizeKey);
        } else {
          next.set(sizeKey, String(normalized));
        }
        next.delete(pageKey); // a size change resets to page 1
        return next;
      },
      { replace: true },
    );

  return { page, size, setPage, setSize };
}
