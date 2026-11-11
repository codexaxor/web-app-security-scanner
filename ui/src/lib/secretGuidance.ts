// Fix guidance per secret level-2 category (M8.6 item 6). Static text, keyed by the category the taxonomy
// assigns from the Betterleaks rule id (Private key / Cloud credential / Password / API token / Secret). Every
// message ends with the note that removing a secret from the code does not remove it from git history. The
// generic fallback (revoke/rotate first, then remove and move to a secret store) covers any other category.

const GIT_HISTORY_NOTE =
  "Removing it from the current code does not remove it from the repository's git history.";

const GENERIC =
  "Revoke or rotate the exposed secret first, then remove it from the code and move it to a secret store.";

const BY_CATEGORY: Record<string, string> = {
  "Cloud credential":
    "Revoke this cloud credential in the provider console and issue a new one, then remove it from the code " +
    "and load it from the environment or a secret manager.",
  "API token":
    "Revoke the token in the issuing service and generate a replacement, then remove it from the code and " +
    "inject it from a secret store or environment variable.",
  "Private key":
    "Treat the private key as compromised: rotate the key pair (and re-issue anything signed with it), then " +
    "remove the key from the repository and keep it in a secrets manager.",
  Password:
    "Change the password everywhere it is used, then remove it from the code and load it from a secret store " +
    "or environment variable.",
};

/** The fix guidance for a secret of the given level-2 category (null/unknown → the generic fallback). */
export function secretFixGuidance(category: string | null | undefined): string {
  const base = (category && BY_CATEGORY[category]) || GENERIC;
  return `${base} ${GIT_HISTORY_NOTE}`;
}
