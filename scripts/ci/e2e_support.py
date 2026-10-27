"""Docker-free pieces of the core-journey orchestrator (scripts/ci/verify-e2e.py), kept separate so they are unit tested.

Nothing here prints or stores a credential. `scrub` removes known secret values and secret-shaped text from any
output that may reach a CI log or an uploaded artifact.
"""
import re
import socket
import time
import xml.etree.ElementTree as ET

CHECKS = ['core-journey']
JOURNEY_TEST = 'com.purpuragenda.acceptance.CoreJourneyE2eTest'
JOURNEY_CASE = 'guestRequestsAdministratorApprovesAndBothSidesSeeConfirmed'
LOCALHOST_PORT = re.compile(r'[1-9][0-9]{3,4}')

# Fixed synthetic values the browser types; they are also scrubbed from Maven output because Playwright call logs
# can echo the value passed to fill().
SYNTHETIC_LITERALS = ('Synthetic', 'Journey', '+32 470 00 00 01', '1990-05-17', 'Exampleville')
_PATTERNS = (
    (re.compile(r'(?i)\b(set-cookie|cookie|authorization|x-csrf-token|idempotency-key)\b\s*[:=][^\r\n]*'), lambda m: m.group(1) + ': [redacted]'),
    # A URL fragment follows a path character; a CSS selector such as "#booking-email" does not and stays readable.
    (re.compile(r'(?<=[A-Za-z0-9/_-])#[^\s"\')<>]*'), lambda m: '#[redacted]'),
    (re.compile(r'\?[^\s"\')<>#]*'), lambda m: '?[redacted]'),
    (re.compile(r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'), lambda m: '[redacted-email]'),
    (re.compile(r'[A-Za-z0-9_-]{32,}'), lambda m: '[redacted-token]'),
    (re.compile(r'(?<![\d.])\d{6}(?![\d.])'), lambda m: '[redacted-code]'),
)


def scrub(text, literals=()):
    """Exact secrets first (longest first), then secret-shaped text. Safe to call on any captured output."""
    value = text if isinstance(text, str) else ''
    for literal in sorted({item for item in tuple(literals) + SYNTHETIC_LITERALS if item}, key=len, reverse=True):
        value = value.replace(literal, '[redacted]')
    for pattern, replacement in _PATTERNS:
        value = pattern.sub(replacement, value)
    return value


def free_port(bind=socket.socket):
    """A currently unused loopback TCP port. The window before Compose publishes it is tiny and a clash fails loudly."""
    with bind(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def distinct_ports(allocate=free_port):
    first = allocate()
    for _ in range(20):
        second = allocate()
        if second != first:
            return first, second
    raise RuntimeError('E2E_PORTS_UNAVAILABLE')


def journey_settings(base_port, mail_port, email, password, key, run_id, evidence_dir):
    """Environment for the browser suite. The browser origin must be localhost: local mode pins it there."""
    if not (isinstance(base_port, int) and isinstance(mail_port, int) and base_port != mail_port
            and all(LOCALHOST_PORT.fullmatch(str(port)) and int(port) <= 65535 for port in (base_port, mail_port))):
        raise ValueError('INVALID_E2E_PORTS')
    if not re.fullmatch(r'[a-f0-9]{8,32}', run_id) or not re.fullmatch(r'[A-Z2-7]{16,128}', key):
        raise ValueError('INVALID_E2E_IDENTITY')
    if not email.endswith('@example.test') or not 15 <= len(password) <= 128:
        raise ValueError('INVALID_E2E_CREDENTIAL')
    return {'E2E_BASE_URL': f'http://localhost:{base_port}', 'E2E_MAIL_URL': f'http://127.0.0.1:{mail_port}',
            'E2E_ADMIN_EMAIL': email, 'E2E_ADMIN_PASSWORD': password, 'E2E_ADMIN_TOTP_KEY': key,
            'E2E_RUN_ID': run_id, 'E2E_EVIDENCE_DIR': str(evidence_dir)}


def validate_published(document, base_port, mail_port):
    """The rendered e2e Compose model publishes only the two loopback ports and pins the public origin to the first."""
    services = document['services']
    for name, service in services.items():
        ports = service.get('ports') or []
        if name != 'web' and ports:
            raise ValueError('UNSAFE_E2E_PORT')
        if any('docker.sock' in str(volume) for volume in service.get('volumes', [])):
            raise ValueError('DOCKER_SOCKET_EXPOSED')
    published = sorted((int(port['published']), int(port['target']), port.get('host_ip')) for port in services['web'].get('ports') or [])
    if published != sorted([(base_port, 8080, '127.0.0.1'), (mail_port, 8025, '127.0.0.1')]):
        raise ValueError('UNSAFE_E2E_PORT')
    environment = services['backend']['environment']
    if environment.get('APP_PUBLIC_ORIGIN') != f'http://localhost:{base_port}' or environment.get('APP_MODE') != 'local':
        raise ValueError('INVALID_E2E_ORIGIN')
    if environment.get('APP_SMTP_HOST') != 'mailpit':
        raise ValueError('INVALID_E2E_MAIL_BOUNDARY')


def wait_for_next_step(used_step, now=time.time, sleep=time.sleep, step_seconds=30):
    """Block until the TOTP step after the enrollment code's step, so the sign-in code cannot be a replay."""
    while int(now() // step_seconds) <= used_step:
        sleep(1)
    return int(now() // step_seconds)


def surefire(report):
    """Counts from the journey's surefire XML; any gap in the report fails the run."""
    try:
        root = ET.parse(report).getroot()
    except (OSError, ET.ParseError):
        raise ValueError('MISSING_E2E_REPORT') from None
    cases = [case for case in root.iter('testcase') if case.get('classname') == JOURNEY_TEST and case.get('name') == JOURNEY_CASE]
    counts = {key: int(root.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
    if root.get('name') != JOURNEY_TEST or len(cases) != 1 or counts['tests'] != 1:
        raise ValueError('INCOMPLETE_E2E_REPORT')
    if counts['failures'] or counts['errors'] or counts['skipped'] or any(
            case.find(tag) is not None for case in cases for tag in ('failure', 'error', 'skipped')):
        raise ValueError('FAILED_E2E_REPORT')
    return counts


def validate_receipt(report, revision):
    if (report.get('revision') != revision or report.get('dirty') is not False or report.get('passed') is not True
            or report.get('checks') != CHECKS or report.get('browser') != 'chromium'
            or report.get('execution') not in ('local-docker', 'hosted-docker')):
        raise ValueError('INCOMPLETE_E2E_EVIDENCE')
