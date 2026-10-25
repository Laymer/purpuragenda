#!/usr/bin/env bash
# Host prerequisites only; no application deployment, DNS, ingress or volume changes.
set +x
set -euo pipefail
MAC_BOOTSTRAP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MAC_TAILSCALE_APP='/Applications/Tailscale.app'
MAC_TAILSCALE_CLI="$MAC_TAILSCALE_APP/Contents/MacOS/Tailscale"
MAC_DOCKER_CLI='/Applications/Docker.app/Contents/Resources/bin/docker'

fail() { printf '%s\n' "$1" >&2; return 1; }
platform_name() { /usr/bin/uname -s; }
host_architecture() { /usr/bin/uname -m; }
macos_version() { /usr/bin/sw_vers -productVersion; }
pin() { /usr/bin/plutil -extract "tailscale.$1" raw -o - -- "$MAC_BOOTSTRAP_ROOT/ops/host-tools.lock.json" 2>/dev/null; }
extract_json() { /usr/bin/plutil -extract "$1" raw -o - -- - 2>/dev/null; }
wait_two_seconds() { /bin/sleep 2; }

host_supported() {
    [[ "$(platform_name)" == Darwin && "$(host_architecture)" == x86_64 ]] || { fail UNSUPPORTED_MAC_HOST; return 1; }
    local version
    version="$(macos_version)"
    [[ "$version" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ && "${version%%.*}" -ge 15 ]] || { fail MACOS_15_OR_LATER_REQUIRED; return 1; }
}

validate_pin() {
    local version checksum team
    version="$(pin version)"; checksum="$(pin sha256)"; team="$(pin teamId)"
    [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$checksum" =~ ^[a-f0-9]{64}$ && "$team" =~ ^[A-Z0-9]{10}$ ]] || { fail INVALID_HOST_TOOL_PIN; return 1; }
    [[ "$(pin url)" == "https://pkgs.tailscale.com/stable/Tailscale-$version-macos.pkg" ]] || { fail INVALID_HOST_TOOL_ORIGIN; return 1; }
}

installed() { [[ -e "$MAC_TAILSCALE_APP" || -L "$MAC_TAILSCALE_APP" ]]; }
installed_version() { /usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$MAC_TAILSCALE_APP/Contents/Info.plist" 2>/dev/null; }
verify_installed() {
    [[ ! -L "$MAC_TAILSCALE_APP" && -d "$MAC_TAILSCALE_APP" && -x "$MAC_TAILSCALE_CLI" ]] || { fail UNSAFE_TAILSCALE_INSTALLATION; return 1; }
    [[ ! -e "$MAC_TAILSCALE_APP/Contents/_MASReceipt" ]] || { fail TAILSCALE_VARIANT_REVIEW_REQUIRED; return 1; }
    [[ "$(installed_version)" == "$(pin version)" ]] || { fail TAILSCALE_VERSION_REVIEW_REQUIRED; return 1; }
    /usr/bin/codesign --verify --deep --strict -R "anchor apple generic and certificate leaf[subject.OU] = \"$(pin teamId)\"" "$MAC_TAILSCALE_APP" >/dev/null 2>&1 || { fail TAILSCALE_APP_SIGNATURE_FAILED; return 1; }
}

package_hash() { /usr/bin/shasum -a 256 "$1" | /usr/bin/awk '{print $1}'; }
package_signature() { /usr/sbin/pkgutil --check-signature "$1" 2>/dev/null; }
package_assessed() { /usr/sbin/spctl --assess --type install "$1" >/dev/null 2>&1; }
verify_package() {
    [[ "$(package_hash "$1")" == "$(pin sha256)" ]] || { fail TAILSCALE_CHECKSUM_FAILED; return 1; }
    local signature
    signature="$(package_signature "$1")" || { fail TAILSCALE_PACKAGE_SIGNATURE_FAILED; return 1; }
    [[ "$signature" == *"$(pin installerSigner)"* ]] || { fail TAILSCALE_INSTALLER_IDENTITY_FAILED; return 1; }
    package_assessed "$1" || { fail TAILSCALE_GATEKEEPER_FAILED; return 1; }
}

install_tailscale() (
    # A private, owned temporary directory is the only tree removed by this script.
    local bootstrap_tmp
    bootstrap_tmp="$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/purpuragenda-host.XXXXXX")"
    trap '/bin/rm -rf -- "$bootstrap_tmp"' EXIT
    /usr/bin/curl -q -fLsS --proto '=https' --proto-redir '=https' --tlsv1.2 --max-time 120 \
        "$(pin url)" -o "$bootstrap_tmp/tailscale.pkg" >/dev/null 2>&1 || { fail TAILSCALE_DOWNLOAD_FAILED; return 1; }
    verify_package "$bootstrap_tmp/tailscale.pkg" || return 1
    /usr/bin/sudo /usr/sbin/installer -pkg "$bootstrap_tmp/tailscale.pkg" -target / >/dev/null 2>&1 || { fail TAILSCALE_INSTALL_FAILED; return 1; }
)

open_tailscale() { /usr/bin/open -a "$MAC_TAILSCALE_APP" >/dev/null 2>&1; }
open_docker() { /usr/bin/open -a /Applications/Docker.app >/dev/null 2>&1; }
tailnet_connected() {
    # Never print status JSON: it contains private device/user information.
    local state backend online
    state="$(TAILSCALE_BE_CLI=1 "$MAC_TAILSCALE_CLI" status --json 2>/dev/null)" || return 1
    backend="$(printf '%s' "$state" | extract_json BackendState)" || return 1
    online="$(printf '%s' "$state" | extract_json Self.Online)" || return 1
    [[ "$backend" == Running && "$online" == true ]]
}

docker_facts() {
    [[ -x "$MAC_DOCKER_CLI" ]] || return 1
    local endpoint
    endpoint="$("$MAC_DOCKER_CLI" context inspect desktop-linux --format '{{.Endpoints.docker.Host}}' 2>/dev/null)" || return 1
    [[ "$endpoint" == unix://* ]] || return 1
    "$MAC_DOCKER_CLI" --context desktop-linux info \
        --format '{"type":"{{.OSType}}","architecture":"{{.Architecture}}","cpus":{{.NCPU}},"memory":{{.MemTotal}}}' 2>/dev/null
}
docker_ready() {
    local facts runtime architecture cpus memory
    facts="$(docker_facts)" || return 1
    runtime="$(printf '%s' "$facts" | extract_json type)" || return 1
    architecture="$(printf '%s' "$facts" | extract_json architecture)" || return 1
    cpus="$(printf '%s' "$facts" | extract_json cpus)" || return 1
    memory="$(printf '%s' "$facts" | extract_json memory)" || return 1
    [[ "$runtime" == linux && ( "$architecture" == x86_64 || "$architecture" == amd64 ) \
        && "$cpus" =~ ^[0-9]+$ && "$memory" =~ ^[0-9]+$ && "$cpus" -ge 2 && "$memory" -ge 4294967296 ]]
}

main() {
    local mode="${1:---check}" attempt
    [[ $# -le 1 && ( "$mode" == --check || "$mode" == --install ) ]] || { fail 'Usage: bootstrap-macos.sh [--check|--install]'; return 1; }
    host_supported || return 1
    validate_pin || return 1
    if ! installed; then
        [[ "$mode" == --install ]] || { printf '%s\n' TAILSCALE_NOT_INSTALLED >&2; return 2; }
        install_tailscale || return 1
    fi
    verify_installed || return 1
    if [[ "$mode" == --install ]] && ! (tailnet_connected && docker_ready); then
        open_tailscale || { fail TAILSCALE_ONBOARDING_UNAVAILABLE; return 1; }
        open_docker || { fail DOCKER_DESKTOP_UNAVAILABLE; return 1; }
        for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
            if tailnet_connected && docker_ready; then break; fi
            wait_two_seconds
        done
    fi
    tailnet_connected || { printf '%s\n' TAILSCALE_INTERACTIVE_APPROVAL_REQUIRED >&2; return 2; }
    docker_ready || { fail DOCKER_LINUX_2CPU_4GIB_REQUIRED; return 1; }
    printf '%s\n' HOST_PREREQUISITES_READY
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then main "$@"; fi
