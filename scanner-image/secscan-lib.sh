# SecScan scanner shell helpers (POSIX sh / busybox ash). Sourced by run-scan.sh and exercised directly by
# the Java test MaskPreviewScriptTest, so the preview rule is tested against the real code, not a copy.

# secret_preview <secret>
#   Masked preview of a secret value. Reveals the first 4 characters ONLY when the secret is long enough
#   (>= 12 chars) that 4 characters are not a large fraction of it; shorter secrets are fully masked as
#   "****" so we never reveal a meaningful part of a short credential. The raw value is passed as a function
#   argument (no external process, so it never reaches another command's argv) and is never echoed.
secret_preview() {
    _secret="$1"
    _len=${#_secret}
    if [ "$_len" -ge 12 ]; then
        # Command substitution strips cut's trailing newline; printf re-emits without one, so the output has
        # no trailing newline in either branch.
        _p="$(printf '%s' "$_secret" | cut -c1-4)"
        printf '%s' "$_p"
    else
        printf '****'
    fi
    unset _secret _len _p
}
