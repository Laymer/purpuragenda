# Source this file from the repository root. Does not change system installations.
PURPURAGENDA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export JAVA_HOME="$PURPURAGENDA_ROOT/.toolchain/jdk"
export PATH="$JAVA_HOME/bin:$PURPURAGENDA_ROOT/.toolchain/node/bin:$PURPURAGENDA_ROOT/.toolchain/maven/bin:$PATH"
