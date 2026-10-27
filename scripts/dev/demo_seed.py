#!/usr/bin/env python3
"""Create synthetic local bookings through the real public API and Mailpit only.

No authentication bypass, no database access and no token, cookie or CSRF value is ever printed.
"""
import argparse
import datetime
import json
import re
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

LOCAL_TARGET = 'http://localhost:8080'
LOCAL_MAILPIT = 'http://localhost:8025'
API = '/api/v1'
DISPLAY_ZONE = 'Europe/Brussels'
# Matches the guest proof mail: <origin>/verify#token=<43 chars>&purpose=CREATE
PROOF_LINK = re.compile(r'/verify#token=([A-Za-z0-9_-]{43})&purpose=CREATE(?![A-Za-z0-9_-])')
RETRYABLE_STATUS = (500, 502, 503, 504)


class SeedError(Exception):
    """A failure whose message is safe to print: never a token, cookie or response body."""


class Response:
    def __init__(self, status, headers, body):
        self.status, self.headers, self.body = status, headers, body

    def json(self):
        try:
            return json.loads(self.body.decode('utf-8'))
        except (UnicodeDecodeError, ValueError):
            raise SeedError('unexpected non-JSON response') from None

    def problem_code(self):
        try:
            code = json.loads(self.body.decode('utf-8')).get('code')
        except (UnicodeDecodeError, ValueError, AttributeError):
            return None
        return code if isinstance(code, str) and re.fullmatch(r'[A-Z][A-Z0-9_]{1,63}', code) else None


class Http:
    """Minimal client keeping one session cookie in memory per instance."""

    def __init__(self, timeout=15, opener=None):
        self.timeout = timeout
        self.cookies = {}
        self.opener = opener or urllib.request.build_opener()

    def request(self, method, url, body=None, headers=None):
        data = None if body is None else json.dumps(body).encode('utf-8')
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header('Accept', 'application/json')
        if data is not None:
            request.add_header('Content-Type', 'application/json')
        if self.cookies:
            request.add_header('Cookie', '; '.join(k + '=' + v for k, v in self.cookies.items()))
        for name, value in (headers or {}).items():
            request.add_header(name, value)
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                result = Response(response.status, response.headers, response.read(2_000_000))
        except urllib.error.HTTPError as error:
            with error:
                result = Response(error.code, error.headers, error.read(100_000))
        self.remember(result.headers)
        return result

    def remember(self, headers):
        for header in (headers.get_all('Set-Cookie') or []) if headers else []:
            pair = header.split(';', 1)[0]
            name, _, value = pair.partition('=')
            name = name.strip()
            if not name:
                continue
            attributes = header.lower()
            if value == '' or 'max-age=0' in attributes:
                self.cookies.pop(name, None)
            else:
                self.cookies[name] = value.strip()


def describe(method, path, response):
    code = response.problem_code()
    if response.status in (404, 405) and code is None:
        return f'{method} {path} is not available on this server (endpoint missing; update to current main)'
    return f'{method} {path} failed with HTTP {response.status}' + (f' {code}' if code else '')


class Seeder:
    def __init__(self, target, mailpit, out=print, mail_timeout=90.0, poll=1.0, sleep=time.sleep, opener=None):
        self.target, self.mailpit = target.rstrip('/'), mailpit.rstrip('/')
        self.out, self.mail_timeout, self.poll, self.sleep = out, mail_timeout, poll, sleep
        self.opener = opener
        self.seen_messages = set()
        self.used_starts = set()
        self.used_dates = set()

    def client(self):
        return Http(opener=self.opener)

    def api(self, http, method, path, body=None, csrf=None, extra=None, expect=(200,)):
        headers = dict(extra or {})
        if method != 'GET':
            headers['Origin'] = self.target
        if csrf is not None:
            headers['X-CSRF-Token'] = csrf
        try:
            response = http.request(method, self.target + API + path, body, headers)
        except (urllib.error.URLError, OSError):
            raise SeedError(f'{method} {path.split("?")[0]}: application not reachable at {self.target}') from None
        if response.status not in expect:
            raise SeedError(describe(method, path.split('?')[0], response))
        return response

    def mail(self, path):
        http = Http(opener=self.opener)
        try:
            response = http.request('GET', self.mailpit + path)
        except (urllib.error.URLError, OSError):
            raise SeedError(f'Mailpit not reachable at {self.mailpit}') from None
        if response.status != 200:
            raise SeedError(f'Mailpit request failed with HTTP {response.status}')
        return response.json()

    def preflight(self):
        """Refuse unless both the application and Mailpit answer."""
        document = self.mail('/api/v1/messages?limit=1')
        if not isinstance(document, dict) or 'messages' not in document:
            raise SeedError(f'Mailpit API not found at {self.mailpit}')
        http = self.client()
        try:
            health = http.request('GET', self.target + '/healthz')
        except (urllib.error.URLError, OSError):
            raise SeedError(f'application not reachable at {self.target}; run ./purpuragenda.sh up') from None
        if health.status != 200:
            raise SeedError('application is not ready; run ./purpuragenda.sh up')

    def bootstrap(self, http):
        session = self.api(http, 'POST', '/session/bootstrap').json()
        if not isinstance(session, dict) or not isinstance(session.get('csrfToken'), str):
            raise SeedError('session bootstrap returned no CSRF proof')
        if not http.cookies:
            raise SeedError('session bootstrap set no session cookie')
        return session['csrfToken']

    def service(self):
        services = self.api(self.client(), 'GET', '/services').json()
        active = [s for s in services if isinstance(s, dict) and s.get('active')] if isinstance(services, list) else []
        if not active:
            raise SeedError('no active service is configured')
        return active[0]

    def proof_token(self, email):
        deadline = time.monotonic() + self.mail_timeout
        while True:
            listing = self.mail('/api/v1/messages?limit=50')
            for message in listing.get('messages') or []:
                identifier = message.get('ID')
                recipients = {str(item.get('Address', '')).lower() for item in message.get('To') or []}
                if not isinstance(identifier, str) or identifier in self.seen_messages or email not in recipients:
                    continue
                detail = self.mail('/api/v1/message/' + urllib.parse.quote(identifier, safe=''))
                match = PROOF_LINK.search(str(detail.get('Text') or '')) or PROOF_LINK.search(str(detail.get('HTML') or ''))
                self.seen_messages.add(identifier)
                if match:
                    return match.group(1)
            if time.monotonic() >= deadline:
                raise SeedError(f'no verification email for {email} arrived in Mailpit within {int(self.mail_timeout)} s')
            self.sleep(self.poll)

    def slot(self, service_id, policy_version):
        today = brussels_today()
        query = urllib.parse.urlencode({'serviceId': service_id, 'from': today.isoformat(),
                                        'to': (today + datetime.timedelta(days=27)).isoformat(),
                                        'displayZone': DISPLAY_ZONE})
        availability = self.api(self.client(), 'GET', '/availability?' + query).json()
        if availability.get('policyVersion') != policy_version:
            raise SeedError('scheduling policy changed during seeding; rerun demo-seed')
        slots = [s for s in availability.get('slots') or [] if s.get('startInstant') not in self.used_starts]
        if not slots:
            raise SeedError('no available start time in the next four weeks; configure the schedule first')
        fresh = [s for s in slots if s.get('businessDate') not in self.used_dates]
        return (fresh or slots)[0]

    def create(self, number, service, run_id):
        email = f'demo+{run_id}-{number}@example.test'
        http = self.client()
        csrf = self.bootstrap(http)
        accepted = self.api(http, 'POST', '/guest/create-proofs', {'email': email}, csrf, expect=(202,)).json()
        if not accepted.get('accepted'):
            raise SeedError('guest email proof request was not accepted')
        token = self.proof_token(email)
        redeemed = self.api(http, 'POST', '/guest/redemptions', {'token': token, 'purpose': 'CREATE'}, csrf).json()
        del token
        if redeemed.get('role') != 'GUEST' or not isinstance(redeemed.get('csrfToken'), str):
            raise SeedError('email proof redemption did not grant a guest session')
        csrf = redeemed['csrfToken']
        context = self.api(http, 'GET', '/booking-context?' + urllib.parse.urlencode({'serviceId': service['id']})).json()
        if context['service']['version'] != service['version']:
            raise SeedError('service changed during seeding; rerun demo-seed')
        for attempt in range(3):
            chosen = self.slot(service['id'], context['policy']['version'])
            body = {'serviceId': service['id'], 'serviceVersion': context['service']['version'],
                    'policyVersion': context['policy']['version'], 'startInstant': chosen['startInstant'],
                    'displayZone': DISPLAY_ZONE, 'noticeVersion': context['notice']['version'],
                    'intake': intake(number)}
            response = self.submit(http, csrf, body)
            if response.status == 201:
                receipt = response.json()
                self.used_starts.add(chosen['startInstant'])
                self.used_dates.add(chosen.get('businessDate'))
                self.out(f'Booking {number}: {receipt.get("bookingId")} pending approval, '
                         f'{chosen.get("businessDate")} {chosen.get("displayLocalTime")} {DISPLAY_ZONE} ({email})')
                return receipt
            if response.status == 409 and attempt < 2:
                self.used_starts.add(chosen['startInstant'])  # Taken meanwhile: choose another start.
                continue
            raise SeedError(describe('POST', '/bookings', response))
        raise SeedError('no start time could be booked')

    def submit(self, http, csrf, body):
        """Retry an uncertain outcome with the same Idempotency-Key and body; never duplicates a booking."""
        key = str(uuid.uuid4())
        for attempt in range(3):
            try:
                response = self.api(http, 'POST', '/bookings', body, csrf, {'Idempotency-Key': key},
                                    expect=(201, 409) + RETRYABLE_STATUS)
            except SeedError:
                if attempt == 2:
                    raise
                self.sleep(2)
                continue
            if response.status in RETRYABLE_STATUS and attempt < 2:
                self.sleep(2)
                continue
            return response
        return response

    def run(self, count):
        self.preflight()
        service = self.service()
        run_id = secrets.token_hex(3)
        receipts = [self.create(number, service, run_id) for number in range(1, count + 1)]
        self.out(f'Created {len(receipts)} synthetic booking(s). Review them as administrator; '
                 f'verification mail is in Mailpit at {self.mailpit}.')
        return receipts


def intake(number):
    return {'firstName': 'Demo', 'lastName': f'Synthetic {number}', 'telephone': f'+32 470 00 00 {number:02d}',
            'birthDate': f'1990-01-{number:02d}', 'birthTime': '12:00', 'birthplace': 'Exampleville',
            'message': 'Synthetic demo booking created by purpuragenda.sh demo-seed.'}


def brussels_today():
    try:
        from zoneinfo import ZoneInfo
        return datetime.datetime.now(ZoneInfo(DISPLAY_ZONE)).date()
    except Exception:  # Missing tz database: the server still validates the civil range.
        return datetime.datetime.now(datetime.timezone.utc).date()


def main(arguments, seeder_factory=Seeder):
    parser = argparse.ArgumentParser(prog='./purpuragenda.sh demo-seed', description=__doc__.splitlines()[0])
    parser.add_argument('--count', type=int, default=3, help='synthetic bookings to create (1-3, default 3)')
    parser.add_argument('--target', default=LOCAL_TARGET, help='must be ' + LOCAL_TARGET)
    parser.add_argument('--mailpit', default=LOCAL_MAILPIT, help='must be ' + LOCAL_MAILPIT)
    args = parser.parse_args(arguments)
    if args.target.rstrip('/') != LOCAL_TARGET or args.mailpit.rstrip('/') != LOCAL_MAILPIT:
        print(f'DEMO_SEED_REFUSED: local mode only; target must be {LOCAL_TARGET} and Mailpit {LOCAL_MAILPIT}.',
              file=sys.stderr)
        return 2
    if not 1 <= args.count <= 3:
        print('DEMO_SEED_REFUSED: --count must be between 1 and 3.', file=sys.stderr)
        return 2
    try:
        seeder_factory(LOCAL_TARGET, LOCAL_MAILPIT).run(args.count)
    except SeedError as error:
        print(f'DEMO_SEED_FAILED: {error}', file=sys.stderr)
        return 1
    except (KeyError, TypeError, AttributeError):
        print('DEMO_SEED_FAILED: unexpected response shape; compare contracts/openapi.yaml', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
