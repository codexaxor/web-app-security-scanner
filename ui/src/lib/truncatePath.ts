/**
 * Truncates a file path from the START, always keeping the file name intact, so a long path reads as
 * "…/terraform/networking.tf" (M8.8 L2). A path already within {@code maxChars} is returned unchanged; the
 * caller shows the full path in a title tooltip. Pure — unit-tested; the cell also uses CSS nowrap/ellipsis so
 * the final width is responsive.
 */
export function truncatePathStart(path: string, maxChars = 44): string {
  if (!path || path.length <= maxChars) {
    return path;
  }
  const slash = path.lastIndexOf("/");
  const file = slash < 0 ? path : path.slice(slash + 1);
  // The file name is always kept in full, even when it alone fills (or exceeds) the budget.
  if (file.length + 2 >= maxChars) {
    return "…/" + file;
  }
  // Keep as much of the leading path as fits, beginning at a clean "/" boundary.
  const tail = path.slice(path.length - (maxChars - 1));
  const cut = tail.indexOf("/");
  return "…" + (cut >= 0 ? tail.slice(cut) : "/" + tail);
}
