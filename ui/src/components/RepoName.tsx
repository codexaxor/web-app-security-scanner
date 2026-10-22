/**
 * A repository's name. Shows the project name (last path segment) by default, truncated with an ellipsis for
 * long names; the full `group/sub/project` path is the title tooltip and can also be shown muted beneath via
 * `showPath`. Plain text (OD-13d).
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
      {showPath && name !== path ? <span className="repo-path"> {path}</span> : null}
    </span>
  );
}
