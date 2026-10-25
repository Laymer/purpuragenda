"""Synthetic native-command boundaries; no host install, login, Docker mutation or credentials."""
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/ops/bootstrap-macos.sh'
PINS = json.loads((ROOT / 'ops/host-tools.lock.json').read_text())['tailscale']


class MacBootstrapTest(unittest.TestCase):
    def run_shell(self, operations, overrides='', pins=None):
        values = PINS if pins is None else pins
        cases = '\n'.join(f'{key}) printf %s {shlex.quote(value)};;' for key, value in values.items()
                          if isinstance(value, str))
        setup = f'''
source {shlex.quote(str(SCRIPT))}
platform_name() {{ printf Darwin; }}
host_architecture() {{ printf x86_64; }}
macos_version() {{ printf 15.7.8; }}
pin() {{ case "$1" in {cases} esac; }}
installed() {{ return 0; }}
verify_installed() {{ return 0; }}
tailnet_connected() {{ return 0; }}
docker_ready() {{ return 0; }}
install_tailscale() {{ printf UNEXPECTED_INSTALL; return 99; }}
open_tailscale() {{ printf UNEXPECTED_GUI; return 99; }}
open_docker() {{ printf UNEXPECTED_GUI; return 99; }}
wait_two_seconds() {{ :; }}
'''
        return subprocess.run(['bash', '-c', setup + overrides + '\n' + operations],
                              text=True, capture_output=True, timeout=10)

    def test_real_unsupported_host_fails_before_native_operations(self):
        result = self.run_shell('main --check', 'platform_name() { printf Linux; }')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('UNSUPPORTED_MAC_HOST', result.stderr)

    def test_check_and_repeated_install_are_read_only_when_ready(self):
        for command in ('main --check', 'main --install', 'main'):
            with self.subTest(command=command):
                result = self.run_shell(command)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual('HOST_PREREQUISITES_READY\n', result.stdout)
                self.assertEqual('', result.stderr)

    def test_missing_check_does_not_install_and_new_install_verifies_then_waits(self):
        missing = self.run_shell('main --check', 'installed() { return 1; }')
        self.assertEqual(2, missing.returncode)
        self.assertEqual('', missing.stdout)
        self.assertIn('TAILSCALE_NOT_INSTALLED', missing.stderr)
        installed = self.run_shell('main --install', '''
installed() { return 1; }
install_tailscale() { printf 'INSTALL_CALLED\n'; }
''')
        self.assertEqual(0, installed.returncode, installed.stderr)
        self.assertEqual('INSTALL_CALLED\nHOST_PREREQUISITES_READY\n', installed.stdout)

    def test_unapproved_tailnet_returns_pending_without_false_readiness(self):
        result = self.run_shell('main --install', '''
tailnet_connected() { return 1; }
open_tailscale() { :; }
open_docker() { :; }
''')
        self.assertEqual(2, result.returncode)
        self.assertEqual('', result.stdout)
        self.assertIn('TAILSCALE_INTERACTIVE_APPROVAL_REQUIRED', result.stderr)

    def test_platform_version_and_unknown_options_fail_closed(self):
        for override in ('platform_name() { printf Linux; }', 'host_architecture() { printf arm64; }',
                         'macos_version() { printf 14.7.1; }', 'macos_version() { printf invalid; }'):
            with self.subTest(override=override):
                self.assertNotEqual(0, self.run_shell('main --check', override).returncode)
        for command in ('main --unknown', 'main --install extra'):
            self.assertNotEqual(0, self.run_shell(command).returncode)

    def test_pins_reject_altered_origin_version_hash_and_team(self):
        for key, value in (('url', 'https://example.test/installer.pkg'), ('version', '../escape'),
                           ('sha256', 'short'), ('teamId', 'bad"; requirement')):
            altered = dict(PINS, **{key: value})
            with self.subTest(key=key):
                self.assertNotEqual(0, self.run_shell('validate_pin', pins=altered).returncode)

    def test_package_requires_hash_signer_signature_and_gatekeeper(self):
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / 'synthetic.pkg'
            file.write_bytes(b'synthetic installer')
            values = dict(PINS, sha256=hashlib.sha256(file.read_bytes()).hexdigest())
            overrides = 'package_signature() { printf %s ' + shlex.quote(PINS['installerSigner']) + '; }\npackage_assessed() { return 0; }\n'
            operation = 'verify_package ' + shlex.quote(str(file))
            self.assertEqual(0, self.run_shell(operation, overrides, values).returncode)
            for extra in ('package_signature() { return 1; }',
                          'package_signature() { printf "Different developer"; }',
                          'package_assessed() { return 1; }',
                          'package_hash() { printf "incorrect"; }'):
                with self.subTest(extra=extra):
                    result = self.run_shell(operation, overrides + extra, values)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual('', result.stdout)

    def test_unavailable_docker_is_not_ready(self):
        result = self.run_shell('main --check', 'docker_ready() { return 1; }')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('DOCKER_LINUX_2CPU_4GIB_REQUIRED', result.stderr)

    def test_real_pin_provenance_is_specific_and_script_has_no_exposure_commands(self):
        self.assertEqual('1.104.2', PINS['version'])
        self.assertEqual('1724d2e021d3c5f817f427f628260c013531a528f1258a7d477735d198b11f55', PINS['sha256'])
        self.assertIn(PINS['url'] + '.sha256', PINS['provenance'])
        text = SCRIPT.read_text()
        self.assertIn("curl -q -fLsS --proto '=https'", text)
        self.assertIn('TAILSCALE_BE_CLI=1', text)
        for command in ('tailscale funnel', 'tailscale serve', 'docker compose', 'auth-key', 'set -x'):
            self.assertNotIn(command, text)


    def extraction_function(self):
        program = "import json,sys; value=json.load(sys.stdin); " + \
            "\nfor part in sys.argv[1].split('.'): value=value[part]\n" + \
            "print(json.dumps(value) if isinstance(value,bool) else value)"
        return 'extract_json() { python3 -c ' + shlex.quote(program) + ' "$1" 2>/dev/null; }\n'

    def test_docker_resource_checks_use_only_expected_linux_runtime(self):
        for facts, passed in (({'type':'linux','architecture':'x86_64','cpus':2,'memory':4294967296}, True),
                              ({'type':'windows','architecture':'x86_64','cpus':2,'memory':4294967296}, False),
                              ({'type':'linux','architecture':'arm64','cpus':2,'memory':4294967296}, False),
                              ({'type':'linux','architecture':'x86_64','cpus':1,'memory':4294967296}, False),
                              ({'type':'linux','architecture':'x86_64','cpus':2,'memory':2147483648}, False)):
            overrides = 'source ' + shlex.quote(str(SCRIPT)) + '\n' + self.extraction_function()
            overrides += 'docker_facts() { printf %s ' + shlex.quote(json.dumps(facts)) + '; }\n'
            with self.subTest(facts=facts):
                result = self.run_shell('docker_ready', overrides)
                self.assertEqual(passed, result.returncode == 0)
                self.assertEqual('', result.stdout)

    def test_tailnet_status_never_prints_private_identity_or_raw_errors(self):
        with tempfile.TemporaryDirectory() as temp:
            binary = Path(temp) / 'synthetic-client'
            for state, online, passed in (('Running', True, True), ('Running', False, False), ('NeedsLogin', True, False)):
                document = {'BackendState':state, 'Self':{'Online':online},
                            'User':{'1':{'LoginName':'synthetic-private-marker'}}}
                binary.write_text('#!/bin/sh\n[ "$TAILSCALE_BE_CLI" = 1 ] || exit 77\nprintf %s ' +
                                  shlex.quote(json.dumps(document)) + '\n')
                binary.chmod(0o755)
                overrides = 'source ' + shlex.quote(str(SCRIPT)) + '\n' + self.extraction_function()
                overrides += 'MAC_TAILSCALE_CLI=' + shlex.quote(str(binary)) + '\n'
                with self.subTest(state=state, online=online):
                    result = self.run_shell('tailnet_connected', overrides)
                    self.assertEqual(passed, result.returncode == 0)
                    self.assertEqual('', result.stdout)
                    self.assertNotIn('synthetic-private-marker', result.stderr)

    def test_malformed_private_status_cannot_report_ready_or_escape_to_output(self):
        with tempfile.TemporaryDirectory() as temp:
            binary = Path(temp) / 'synthetic-client'
            binary.write_text("#!/bin/sh\nprintf '%s' 'synthetic-private-marker-invalid-json'\n")
            binary.chmod(0o755)
            overrides = 'source ' + shlex.quote(str(SCRIPT)) + '\n' + self.extraction_function()
            overrides += 'MAC_TAILSCALE_CLI=' + shlex.quote(str(binary)) + '\n'
            result = self.run_shell('tailnet_connected', overrides)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual('', result.stdout)
            self.assertEqual('', result.stderr)

    def test_existing_app_links_and_store_variant_are_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            outside = root / 'outside'; outside.mkdir()
            sentinel = outside / 'sentinel'; sentinel.write_text('preserve outside')
            link = root / 'Tailscale.app'; link.symlink_to(outside, target_is_directory=True)
            overrides = 'source ' + shlex.quote(str(SCRIPT)) + '\nMAC_TAILSCALE_APP=' + shlex.quote(str(link)) + '\n'
            result = self.run_shell('verify_installed', overrides)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual('preserve outside', sentinel.read_text())
            link.unlink(); (link / 'Contents/_MASReceipt').mkdir(parents=True)
            binary = link / 'Contents/MacOS/Tailscale'; binary.parent.mkdir(); binary.write_text('synthetic'); binary.chmod(0o755)
            result = self.run_shell('verify_installed', overrides + 'MAC_TAILSCALE_CLI=' + shlex.quote(str(binary)) + '\n')
            self.assertNotEqual(0, result.returncode)
            self.assertIn('TAILSCALE_VARIANT_REVIEW_REQUIRED', result.stderr)
            self.assertEqual('synthetic', binary.read_text())


    def test_admin_install_uses_cached_authority_or_native_gui_only(self):
        for cached, expected in ((True, 'CACHED_ADMIN'), (False, 'NATIVE_GUI_ADMIN')):
            overrides = 'source ' + shlex.quote(str(SCRIPT)) + '\n'
            overrides += 'admin_cached() { return ' + ('0' if cached else '1') + '; }\n'
            overrides += 'sudo_install() { printf CACHED_ADMIN; }\ngui_install() { printf NATIVE_GUI_ADMIN; }\n'
            result = self.run_shell('elevated_install /tmp/synthetic.pkg', overrides)
            self.assertEqual(0, result.returncode)
            self.assertEqual(expected, result.stdout)
        helper = (ROOT / 'scripts/ops/install-macos-package.applescript').read_text()
        self.assertIn('quoted form of installerPath', helper)
        self.assertIn('with administrator privileges', helper)
        self.assertNotIn('password', helper)

    def test_failed_package_verification_never_requests_administrator_installation(self):
        with tempfile.TemporaryDirectory() as temp:
            marker = Path(temp) / 'admin-called'
            overrides = 'source ' + shlex.quote(str(SCRIPT)) + '\n'
            overrides += 'download_package() { printf synthetic > "$1"; }\nverify_package() { return 1; }\n'
            overrides += 'elevated_install() { touch ' + shlex.quote(str(marker)) + '; }\n'
            result = self.run_shell('install_tailscale', overrides)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(marker.exists())

    def test_app_verifier_passes_inline_requirement_to_codesign(self):
        # codesign interprets a requirement without '=' as a file path.
        self.assertIn('-R "=anchor apple generic and certificate leaf[subject.OU]', SCRIPT.read_text())
        self.assertNotIn('-R "anchor apple generic', SCRIPT.read_text())

    def test_failed_admin_install_stops_before_onboarding_or_readiness(self):
        overrides = 'source ' + shlex.quote(str(SCRIPT)) + '\n'
        overrides += "platform_name() { printf Darwin; }\nhost_architecture() { printf x86_64; }\nmacos_version() { printf 15.7.8; }\npin() { return 0; }\nvalidate_pin() { return 0; }\ninstalled() { return 1; }\ndownload_package() { printf synthetic > \"$1\"; }\n"
        overrides += 'verify_package() { return 0; }\nelevated_install() { return 1; }\n'
        result = self.run_shell('main --install', overrides)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('', result.stdout)
        self.assertEqual('TAILSCALE_INSTALL_FAILED\n', result.stderr)

    def test_intel_native_ci_uses_stock_bash_real_verification_and_required_receipt(self):
        text = (ROOT / '.github/workflows/verify.yml').read_text()
        self.assertIn('runs-on: macos-15-intel', text)
        self.assertIn('/bin/bash scripts/ci/verify-macos-package.sh', text)
        self.assertIn('artifacts/host/macos-package.json', text)
        helper = (ROOT / 'scripts/ci/verify-macos-package.sh').read_text()
        self.assertIn('verify_package "$native_package_tmp/tailscale.pkg"', helper)
        self.assertIn('/usr/bin/osacompile', helper)
        self.assertIn('/usr/sbin/pkgutil --expand-full', helper)
        self.assertIn('verify_installed', helper)
        self.assertIn("'appSignatureVerified':True", helper)
        self.assertNotIn('elevated_install ', helper)


if __name__ == '__main__':
    unittest.main()
