#!/usr/bin/env bash
# Intel macOS CI: authenticate real media and compile native auth helper, never install/connect.
set +x
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/../ops/bootstrap-macos.sh"
python3 - "$MAC_BOOTSTRAP_ROOT" <<'PY'
from pathlib import Path
import sys
sys.path.insert(0,str(Path(sys.argv[1])/'scripts/ci'))
from artifact_paths import remove_artifact
try:
    remove_artifact(Path(sys.argv[1]),Path('artifacts/host/macos-package.json'))
except (OSError,ValueError):
    raise SystemExit('MAC_PACKAGE_RECEIPT_CLEANUP_FAILED')
PY
host_supported
validate_pin
native_package_tmp="$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/purpuragenda-package.XXXXXX")"
trap '/bin/rm -rf -- "$native_package_tmp"' EXIT
download_package "$native_package_tmp/tailscale.pkg"
verify_package "$native_package_tmp/tailscale.pkg"
/usr/bin/osacompile -o "$native_package_tmp/install.scpt" \
    "$MAC_BOOTSTRAP_ROOT/scripts/ops/install-macos-package.applescript" >/dev/null 2>&1 || { fail MAC_ADMIN_HELPER_SYNTAX_FAILED; exit 1; }
python3 - "$MAC_BOOTSTRAP_ROOT" "$(macos_version)" <<'PY'
from pathlib import Path
import json,sys
root=Path(sys.argv[1]);sys.path.insert(0,str(root/'scripts/ci'))
from artifact_paths import write_artifact
pin=json.loads((root/'ops/host-tools.lock.json').read_text())['tailscale']
receipt={'schemaVersion':1,'platform':'macos-intel','macosVersion':sys.argv[2],
         'tailscaleVersion':pin['version'],'sha256':pin['sha256'],
         'packageSignatureVerified':True,'gatekeeperAccepted':True,'adminHelperCompiled':True}
try:
    write_artifact(root,Path('artifacts/host/macos-package.json'),json.dumps(receipt,indent=2)+'\n')
except (OSError,ValueError):
    raise SystemExit('MAC_PACKAGE_RECEIPT_WRITE_FAILED')
PY
printf '%s\n' MAC_PACKAGE_VERIFIED_WITHOUT_INSTALLATION
