/**
 * A repository's name. Shows the project name (last path segment), truncated with an ellipsis for long names;
 * the full `group/sub/project` path is the title tooltip and, with `showPath`, also shown muted on its OWN line
 * beneath the name (ellipsized, full path in title). Plain text (OD-13d).
 */
export default function RepoName({
  path,
  showPath = false,
}: {
  path: string;
  showPath?: boolean;
}) {
  const name = path.split("/").filter(Boolean).pop() ?? path;
  return (
    <span className="repo-name-wrap">
      <span className="repo-name" title={path}>
        {name}
      </span>
      {showPath && name !== path ? (
        <span className="repo-path" title={path}>
          {path}
        </span>
      ) : null}
    </span>
  );
}
