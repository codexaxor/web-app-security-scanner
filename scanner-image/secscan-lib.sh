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

# nuget_version_unresolved <version>
#   Exit 0 (true) when a NuGet <PackageReference> version string is NOT a concrete pinned version that OSV can
#   match statically — an MSBuild property ($(..)), a version range (contains [ ] ( ) or a comma), a floating
#   wildcard (*), or empty. Exit 1 (false) for a concrete version such as 12.0.1 or 1.2.3-beta.2. We never guess
#   a concrete version from one of these (D-031/D-035); the caller raises an UNRESOLVED_VERSION warning instead.
nuget_version_unresolved() {
    case "$1" in
        '' | *'$('* | *'['* | *']'* | *'('* | *')'* | *','* | *'*'*) return 0 ;;
        *) return 1 ;;
    esac
}

# xml_count <file> <local-name>
#   Number of elements with the given local-name (namespace-agnostic) in an XML file. Uses xmllint --nonet
#   (no network) and never --noent (no entity expansion), so a malicious .csproj cannot pull in external
#   entities. Prints 0 on any parse error.
xml_count() {
    xmllint --nonet --xpath "count(//*[local-name()='$2'])" "$1" 2>/dev/null || printf '0'
}

# xml_str <file> <xpath>
#   The string value of an XPath expression, or empty on error. Same --nonet/no --noent guarantees.
xml_str() {
    xmllint --nonet --xpath "string($2)" "$1" 2>/dev/null || printf ''
}

# csproj_package_versions <csproj-file>
#   Prints one "name<TAB>version" line per <PackageReference> (namespace-agnostic). The version is the Version
#   attribute, else a child <Version> element, else the VersionOverride attribute, else empty (central package
#   management — OSV resolves those from Directory.Packages.props natively, so we leave them to OSV). Reads with
#   xmllint --nonet only.
csproj_package_versions() {
    _f="$1"
    _n="$(xml_count "$_f" PackageReference)"
    _n="${_n%%.*}"
    [ -n "$_n" ] && [ "$_n" -gt 0 ] 2>/dev/null || { unset _f _n; return 0; }
    _i=1
    while [ "$_i" -le "$_n" ]; do
        _base="(//*[local-name()='PackageReference'])[$_i]"
        _name="$(xml_str "$_f" "$_base/@Include")"
        [ -z "$_name" ] && _name="$(xml_str "$_f" "$_base/@Update")"
        _ver="$(xml_str "$_f" "$_base/@Version")"
        [ -z "$_ver" ] && _ver="$(xml_str "$_f" "$_base/*[local-name()='Version']")"
        [ -z "$_ver" ] && _ver="$(xml_str "$_f" "$_base/@VersionOverride")"
        [ -n "$_name" ] && printf '%s\t%s\n' "$_name" "$_ver"
        _i=$((_i + 1))
    done
    unset _f _n _i _base _name _ver
}
