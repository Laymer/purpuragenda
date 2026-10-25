# Source with bash from any directory. System installations remain unchanged.
PURPURAGENDA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export JAVA_HOME="$PURPURAGENDA_ROOT/.toolchain/jdk"
export PATH="$PURPURAGENDA_ROOT/.toolchain/python/bin:$JAVA_HOME/bin:$PURPURAGENDA_ROOT/.toolchain/node/bin:$PURPURAGENDA_ROOT/.toolchain/maven/bin:$PATH"

# macOS aliases /tmp to /private/tmp; use one canonical temporary root in reports.
export TMPDIR="$(cd "${TMPDIR:-/tmp}" && pwd -P)"
if ! command -v docker >/dev/null 2>&1 && [[ -x /Applications/Docker.app/Contents/Resources/bin/docker ]]; then
  export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
fi
