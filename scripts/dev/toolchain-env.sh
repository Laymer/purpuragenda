# Source with bash from any directory. System installations remain unchanged.
PURPURAGENDA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export JAVA_HOME="$PURPURAGENDA_ROOT/.toolchain/jdk"
export PATH="$JAVA_HOME/bin:$PURPURAGENDA_ROOT/.toolchain/node/bin:$PURPURAGENDA_ROOT/.toolchain/maven/bin:$PATH"
