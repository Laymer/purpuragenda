"""purpuragenda.sh dispatch, safety refusals and doctor detection with hermetic stub tools."""
import os
from pathlib import Path
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'purpuragenda.sh'
# macOS /bin/bash is 3.2: exercising it on the macOS CI job proves the script stays compatible.
BASH = '/bin/bash' if Path('/bin/bash').is_file() else shutil.which('bash')
COMMANDS = ('help', 'doctor', 'setup', 'up', 'smoke', 'admin', 'down', 'status', 'logs', 'demo-seed',
            'test', 'verify', 'e2e')

FAKE_DOCKER = r'''#!/bin/sh
printf 'docker %s\n' "$*" >> "$FAKE_LOG"
case "$1" in
  version)
    if [ "${FAKE_DOCKER_DAEMON:-up}" = up ]; then echo 27.3.1; exit 0; fi
    echo 'Cannot connect to the Docker daemon' >&2; exit 1 ;;
  compose)
    shift
    while [ "$1" = -f ]; do shift 2; done
    case "$1" in
      version) echo "${FAKE_COMPOSE_VERSION:-2.29.7}"; exit 0 ;;
      port) for owned in ${FAKE_OWNED_PORTS:-}; do
              if [ "$3" = "$owned" ]; then echo "127.0.0.1:$owned"; exit 0; fi
            done; exit 1 ;;
      *) exit 0 ;;
    esac ;;
esac
exit 0
'''
FAKE_UNAME = r'''#!/bin/sh
case "$1" in
  -s) echo "$FAKE_UNAME_S" ;;
  -r) echo "$FAKE_UNAME_R" ;;
  -m) echo "$FAKE_UNAME_M" ;;
  *) echo "$FAKE_UNAME_S" ;;
esac
'''
FAKE_DF = r'''#!/bin/sh
printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/fake 999999999 1 %s 1%% /\n' "$FAKE_DF_KB"
'''
FAKE_GIT = '#!/bin/sh\necho "git version 2.47.0"\n'
RECORDER = '#!/bin/sh\nprintf "%s %s\\n" "{name}" "$*" >> "$FAKE_LOG"\nexit ${{FAKE_EXIT:-0}}\n'


def write(path, text, mode=0o755):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    path.chmod(mode)


def wrapper(directory, name, target):
    write(directory / name, f'#!/bin/sh\nexec "{target}" "$@"\n')


class ScriptCase(unittest.TestCase):
    """A copied script in a temporary tree, so no real toolchain, secrets or compose file is used."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.temp = Path(temporary.name)
        self.log = self.temp / 'calls.log'
        self.log.write_text('')
        self.tree = self.temp / 'tree'
        self.tree.mkdir()
        shutil.copy2(SCRIPT, self.tree / 'purpuragenda.sh')
        (self.tree / 'compose.yaml').write_text('name: stub\n')
        self.compose = 'docker compose -f ' + str(self.tree / 'compose.yaml')

    def calls(self):
        return self.log.read_text().splitlines()


class DispatchTest(ScriptCase):
    """A copied script in a stub tree: every delegated command is recorded, nothing real runs."""

    def setUp(self):
        super().setUp()
        for relative in ('scripts/dev/local.sh', 'scripts/dev/install-toolchain.sh', 'verify.sh',
                         'scripts/ci/verify-frontend.sh'):
            write(self.tree / relative, RECORDER.format(name=relative))
        write(self.tree / 'scripts/dev/toolchain-env.sh', '# stub environment\n', 0o644)
        write(self.tree / 'scripts/dev/demo_seed.py',
              'import os, sys\nopen(os.environ["FAKE_LOG"], "a").write("demo_seed.py " + " ".join(sys.argv[1:]) + "\\n")\n', 0o644)
        write(self.tree / 'scripts/ci/run-python-tests.py',
              'import os, sys\nopen(os.environ["FAKE_LOG"], "a").write("run-python-tests " + " ".join(sys.argv[1:]) + "\\n")\n', 0o644)
        self.bin = self.temp / 'bin'
        write(self.bin / 'docker', FAKE_DOCKER)

    def run_script(self, *arguments, **extra):
        environment = dict(os.environ, FAKE_LOG=str(self.log), PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                           PURPURAGENDA_DOCKER_DESKTOP_BIN=str(self.temp / 'no-desktop'))
        environment.update(extra)
        environment.pop('COMPOSE_FILE', None)
        return subprocess.run([BASH, str(self.tree / 'purpuragenda.sh'), *arguments], cwd=self.temp,
                              env=environment, capture_output=True, text=True, timeout=60)

    def test_help_lists_every_command_with_examples(self):
        for arguments in (['help'], ['--help'], ['-h']):
            result = self.run_script(*arguments)
            self.assertEqual(0, result.returncode)
            for command in COMMANDS:
                self.assertRegex(result.stdout, r'\n  ' + command + r' +\S')
            self.assertIn('Examples:', result.stdout)
        self.assertEqual([], self.calls())

    def test_every_command_has_help_without_side_effects(self):
        for command in COMMANDS:
            for form in ([command, '--help'], [command, '-h'], ['help', command]):
                with self.subTest(form=form):
                    result = self.run_script(*form)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertIn('Usage: ./purpuragenda.sh ' + command, result.stdout)
        self.assertEqual([], self.calls())

    def test_unknown_or_missing_command_prints_help_and_exits_2(self):
        for arguments in (['launch'], [], ['help', 'launch'], ['--volumes']):
            with self.subTest(arguments=arguments):
                result = self.run_script(*arguments)
                self.assertEqual(2, result.returncode)
                self.assertIn('Usage: ./purpuragenda.sh <command>', result.stderr)
        self.assertEqual([], self.calls())

    def test_local_commands_delegate_to_local_sh(self):
        for command, expected in (('up', 'up'), ('smoke', 'smoke'), ('admin', 'admin-setup')):
            self.log.write_text('')
            result = self.run_script(command)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(['scripts/dev/local.sh ' + expected], self.calls())
        self.assertEqual(2, self.run_script('up', 'now').returncode)

    def test_setup_installs_toolchain_then_initializes_secrets(self):
        self.assertEqual(0, self.run_script('setup').returncode)
        self.assertEqual(['scripts/dev/install-toolchain.sh ', 'scripts/dev/local.sh init'], self.calls())
        self.log.write_text('')
        self.assertNotEqual(0, self.run_script('setup', FAKE_EXIT='1').returncode)
        self.assertEqual(['scripts/dev/install-toolchain.sh '], self.calls())

    def test_down_stops_without_removing_volumes(self):
        compose = self.compose
        self.assertEqual(0, self.run_script('down').returncode)
        self.assertEqual(0, self.run_script('down', 'backend').returncode)
        self.assertEqual([compose + ' stop', compose + ' stop backend'], self.calls())

    def test_down_refuses_every_volume_removal_form(self):
        for arguments in (['-v'], ['--volumes'], ['--volumes=true'], ['backend', '-v'], ['-tv'], ['-v', '--rmi']):
            with self.subTest(arguments=arguments):
                result = self.run_script('down', *arguments)
                self.assertEqual(2, result.returncode)
                self.assertIn('Refused', result.stderr)
                self.assertIn('database', result.stderr)
                self.assertIn('Never use', result.stderr)
        self.assertEqual(2, self.run_script('down', '--remove-orphans').returncode)
        self.assertEqual([], self.calls())

    def test_status_and_logs_use_project_compose_file(self):
        compose = self.compose
        self.assertEqual(0, self.run_script('status').returncode)
        self.assertEqual(0, self.run_script('logs').returncode)
        self.assertEqual(0, self.run_script('logs', 'backend').returncode)
        self.assertEqual([compose + ' ps', compose + ' logs -f', compose + ' logs -f backend'], self.calls())
        self.log.write_text('')
        self.assertEqual(2, self.run_script('logs', 'backend', 'web').returncode)
        self.assertEqual(2, self.run_script('logs', '--tail=5').returncode)
        self.assertEqual([], self.calls())

    def test_docker_commands_fail_clearly_without_docker(self):
        (self.bin / 'docker').unlink()
        result = self.run_script('status', PATH=str(self.bin))
        self.assertEqual(1, result.returncode)
        self.assertIn('Docker CLI not found', result.stderr)

    def test_e2e_always_fails(self):
        result = self.run_script('e2e')
        self.assertEqual(1, result.returncode)
        self.assertIn('core journey suite not implemented yet (P38/P39 slice pending)', result.stderr)
        self.assertEqual([], self.calls())

    def test_verify_test_and_demo_seed_delegate(self):
        self.assertEqual(0, self.run_script('verify').returncode)
        self.assertEqual(['verify.sh all'], self.calls())
        self.log.write_text('')
        result = self.run_script('test')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['scripts/ci/verify-frontend.sh ',
                          'run-python-tests --start ' + str(self.tree / 'scripts/ci'),
                          'run-python-tests --start ' + str(self.tree / 'scripts/ci/tests')], self.calls())
        self.log.write_text('')
        self.assertEqual(0, self.run_script('demo-seed', '--count', '2').returncode)
        self.assertEqual(['demo_seed.py --count 2'], self.calls())
        self.log.write_text('')
        self.assertNotEqual(0, self.run_script('test', FAKE_EXIT='1').returncode)
        self.assertEqual(['scripts/ci/verify-frontend.sh '], self.calls())


class DoctorTest(ScriptCase):
    """Doctor runs with a PATH holding only stub tools and wrappers for basic utilities."""

    def setUp(self):
        super().setUp()
        self.bin = self.temp / 'bin'
        for name in ('awk', 'cat'):
            wrapper(self.bin, name, shutil.which(name))
        wrapper(self.bin, 'python3', sys.executable)
        write(self.bin / 'docker', FAKE_DOCKER)
        write(self.bin / 'uname', FAKE_UNAME)
        write(self.bin / 'df', FAKE_DF)
        write(self.bin / 'git', FAKE_GIT)
        self.environment = {'PATH': str(self.bin), 'FAKE_LOG': str(self.log), 'HOME': str(self.temp),
                            'FAKE_UNAME_S': 'Linux', 'FAKE_UNAME_R': '6.8.0-45-generic', 'FAKE_UNAME_M': 'x86_64',
                            'FAKE_DF_KB': str(100 * 1024 * 1024), 'PURPURAGENDA_DOCTOR_PORTS': '',
                            'PURPURAGENDA_DOCKER_DESKTOP_BIN': str(self.temp / 'no-desktop')}

    def doctor(self, **extra):
        return subprocess.run([BASH, str(self.tree / 'purpuragenda.sh'), 'doctor'], cwd=self.temp, env={**self.environment, **extra},
                              capture_output=True, text=True, timeout=60)

    def busy_port(self):
        listener = socket.socket()
        listener.bind(('127.0.0.1', 0))
        listener.listen(1)
        self.addCleanup(listener.close)
        return str(listener.getsockname()[1])

    def test_healthy_linux_host_passes(self):
        result = self.doctor()
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn('Linux (x86_64', result.stdout)
        self.assertIn('Docker daemon 27.3.1', result.stdout)
        self.assertIn('Docker Compose 2.29.7', result.stdout)
        self.assertIn('git version 2.47.0', result.stdout)
        self.assertIn('no blocking problems', result.stdout)

    def test_operating_system_detection(self):
        cases = ((('Darwin', '24.6.0', 'x86_64'), 'macOS on Intel'), (('Darwin', '24.6.0', 'arm64'), 'macOS on Apple silicon'),
                 (('Linux', '6.6.87.2-microsoft-standard-WSL2', 'x86_64'), 'Linux on WSL2'),
                 (('Linux', '6.8.0', 'aarch64'), 'Linux (aarch64'))
        for (kernel, release, machine), expected in cases:
            with self.subTest(expected=expected):
                result = self.doctor(FAKE_UNAME_S=kernel, FAKE_UNAME_R=release, FAKE_UNAME_M=machine)
                self.assertEqual(0, result.returncode, result.stdout)
                self.assertIn('[ok]   ' + expected, result.stdout)
        result = self.doctor(FAKE_UNAME_S='MINGW64_NT-10.0')
        self.assertEqual(1, result.returncode)
        self.assertIn('unsupported operating system', result.stdout)
        self.assertIn('WSL2', result.stdout)

    def test_missing_docker_gives_os_specific_fix(self):
        (self.bin / 'docker').unlink()
        for kernel, release, fix in (('Darwin', '24.6.0', 'install Docker Desktop'),
                                     ('Linux', '5.15.0-microsoft-standard-WSL2', 'WSL integration'),
                                     ('Linux', '6.8.0', 'install Docker Engine')):
            with self.subTest(fix=fix):
                result = self.doctor(FAKE_UNAME_S=kernel, FAKE_UNAME_R=release)
                self.assertEqual(1, result.returncode)
                self.assertIn('[FAIL] Docker CLI not found', result.stdout)
                self.assertIn(fix, result.stdout)

    def test_docker_desktop_bundle_is_discovered(self):
        desktop = self.temp / 'Docker.app/Contents/Resources/bin'
        desktop.mkdir(parents=True)
        (self.bin / 'docker').rename(desktop / 'docker')
        result = self.doctor(FAKE_UNAME_S='Darwin', PURPURAGENDA_DOCKER_DESKTOP_BIN=str(desktop))
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn('Docker CLI ' + str(desktop / 'docker'), result.stdout)

    def test_stopped_daemon_and_compose_v1_block(self):
        result = self.doctor(FAKE_DOCKER_DAEMON='down', FAKE_UNAME_S='Darwin')
        self.assertEqual(1, result.returncode)
        self.assertIn('[FAIL] Docker daemon not reachable', result.stdout)
        self.assertIn('open -a Docker', result.stdout)
        result = self.doctor(FAKE_COMPOSE_VERSION='1.29.2')
        self.assertEqual(1, result.returncode)
        self.assertIn('Docker Compose v2 plugin not found', result.stdout)

    def test_ports_free_owned_or_foreign(self):
        port = self.busy_port()
        result = self.doctor(PURPURAGENDA_DOCTOR_PORTS=port)
        self.assertEqual(1, result.returncode)
        self.assertIn(f'[FAIL] loopback port {port} is used by another process', result.stdout)
        self.assertIn('ss -ltnp', result.stdout)
        result = self.doctor(PURPURAGENDA_DOCTOR_PORTS=port, FAKE_UNAME_S='Darwin')
        self.assertIn(f'lsof -nP -iTCP:{port}', result.stdout)
        result = self.doctor(PURPURAGENDA_DOCTOR_PORTS=port, FAKE_OWNED_PORTS=port)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn(f"port {port} is used by this project's running stack", result.stdout)
        self.assertIn(f'{self.compose} port web {port}', self.calls())
        # Ownership is never trusted when the daemon cannot confirm it.
        result = self.doctor(PURPURAGENDA_DOCTOR_PORTS=port, FAKE_OWNED_PORTS=port, FAKE_DOCKER_DAEMON='down')
        self.assertIn(f'[FAIL] loopback port {port} is used by another process', result.stdout)
        probe = socket.socket()
        probe.bind(('127.0.0.1', 0))
        free = str(probe.getsockname()[1])
        probe.close()
        result = self.doctor(PURPURAGENDA_DOCTOR_PORTS=free)
        self.assertIn(f'[ok]   loopback port {free} is free', result.stdout)

    def test_disk_space_thresholds(self):
        result = self.doctor(FAKE_DF_KB=str(1024 * 1024))
        self.assertEqual(1, result.returncode)
        self.assertIn('only 1024 MiB free', result.stdout)
        result = self.doctor(FAKE_DF_KB=str(10 * 1024 * 1024))
        self.assertEqual(0, result.returncode)
        self.assertIn('[warn] 10 GiB free', result.stdout)

    def test_missing_git_and_python_block(self):
        (self.bin / 'git').unlink()
        (self.bin / 'python3').unlink()
        result = self.doctor(FAKE_UNAME_S='Darwin')
        self.assertEqual(1, result.returncode)
        self.assertIn('[FAIL] git not found', result.stdout)
        self.assertIn('xcode-select --install', result.stdout)
        self.assertIn('[FAIL] Python 3.9 or newer not found', result.stdout)
        self.assertIn('2 blocking problem(s)', result.stdout)

    def test_doctor_rejects_arguments(self):
        result = subprocess.run([BASH, str(self.tree / 'purpuragenda.sh'), 'doctor', '--fix'], cwd=self.temp, env=self.environment,
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(2, result.returncode)


class ShellSyntaxTest(unittest.TestCase):
    def test_scripts_parse_and_are_executable(self):
        for script in (SCRIPT, ROOT / 'verify.sh'):
            self.assertTrue(stat.S_IMODE(script.stat().st_mode) & 0o111, script.name)
            self.assertEqual(0, subprocess.run([BASH, '-n', str(script)]).returncode)
            self.assertIn('set -euo pipefail', script.read_text())


if __name__ == '__main__':
    unittest.main()
