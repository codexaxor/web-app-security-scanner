# SecScan scanner shell helpers (POSIX sh / busybox ash). Sourced by run-scan.sh and exercised directly by
# the Java test MaskPreviewScriptTest, so the preview rule is tested against the real code, not a copy.

# secret_preview <secret>
#   Masked preview of a secret value. Reveals the first 4 characters of the WHOLE secret ONLY when it is long
#   enough (>= 12 chars) that 4 characters are not a large fraction of it; shorter secrets are fully masked as
#   "****" so we never reveal a meaningful part of a short credential. The raw value is passed as a function
#   argument (no external process, so it never reaches another command's argv) and is never echoed.
#
#   The first 4 characters are taken with parameter expansion, which treats the secret as a single string — NOT
#   line-by-line. (`cut -c1-4` is line-oriented and would emit the first 4 characters of EVERY line, leaking a
#   fragment of each line of a multi-line secret such as a PEM private key.) Any CR/LF is then stripped so the
#   preview is always single-line and never longer than 4 characters.
secret_preview() {
    _secret="$1"
    _len=${#_secret}
    if [ "$_len" -ge 12 ]; then
        _rest=${_secret#????}         # drop the first 4 characters of the whole string
        _p=${_secret%"$_rest"}        # what remains is exactly those first 4 characters
        _p="$(printf '%s' "$_p" | tr -d '\r\n')"
        printf '%s' "$_p"
    else
        printf '****'
    fi
    unset _secret _len _rest _p
}
