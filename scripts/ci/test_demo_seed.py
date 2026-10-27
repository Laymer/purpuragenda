"""demo-seed uses only the public API and Mailpit, refuses non-local targets and never prints secrets."""
import contextlib
import http.server
import importlib.util
import io
import json
import os
from pathlib import Path
import secrets
import threading
import unittest
from unittest import mock
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('demo_seed', ROOT / 'scripts/dev/demo_seed.py')
SEED = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SEED)
SERVICE = '10000000-0000-0000-0000-000000000001'


class FakeStack:
    """Synthetic application plus Mailpit enforcing Origin, CSRF, cookie rotation and idempotency."""

    def __init__(self):
        self.requests, self.sessions, self.tokens, self.messages = [], {}, {}, []
        self.secrets_seen = []
        self.fail_bookings = 0
        # Scripted booking outcomes consumed first: 'drop' closes the connection without a response,
        # (status, code, refreshAvailability) returns a problem.
        self.booking_script = []
        self.redirects = {}
        self.missing = set()
        self.bookings = {}
        self.slots = [{'startInstant': f'2030-01-0{day}T{hour}:00:00Z', 'sessionEndInstant': 'x',
                       'reservedEndInstant': 'x', 'businessDate': f'2030-01-0{day}',
                       'displayLocalTime': f'{hour + 1}:00', 'offset': '+01:00'}
                      for day in (1, 2) for hour in (9, 13)]
        stack = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *arguments):
                pass

            def do_GET(self):
                stack.handle(self, 'GET')

            def do_POST(self):
                stack.handle(self, 'POST')

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.url = f'http://127.0.0.1:{self.server.server_address[1]}'
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()

    def secret(self):
        value = secrets.token_urlsafe(32)
        self.secrets_seen.append(value)
        return value

    def handle(self, handler, method):
        parsed = urllib.parse.urlsplit(handler.path)
        length = int(handler.headers.get('Content-Length') or 0)
        body = json.loads(handler.rfile.read(length)) if length else None
        cookie = (handler.headers.get('Cookie') or '').partition('purpuragenda-local=')[2].split(';')[0]
        session = self.sessions.get(cookie)
        self.requests.append({'method': method, 'path': parsed.path, 'query': urllib.parse.parse_qs(parsed.query),
                              'origin': handler.headers.get('Origin'), 'csrf': handler.headers.get('X-CSRF-Token'),
                              'key': handler.headers.get('Idempotency-Key'), 'session': session, 'body': body})
        route = (method, parsed.path)
        if route in self.missing:
            return self.reply(handler, 404, b'', 'text/plain')
        if route in self.redirects:
            handler.send_response(302)
            handler.send_header('Location', self.redirects[route])
            handler.send_header('Content-Length', '0')
            handler.end_headers()
            return None
        if method == 'POST' and handler.headers.get('Origin') != self.url:
            return self.problem(handler, 403, 'ORIGIN_REJECTED')
        if method == 'POST' and parsed.path != '/api/v1/session/bootstrap' and (
                session is None or handler.headers.get('X-CSRF-Token') != session['csrf']):
            return self.problem(handler, 403, 'CSRF_REJECTED')
        if route == ('GET', '/healthz'):
            return self.reply(handler, 200, {'status': 'UP'})
        if route == ('GET', '/api/v1/messages'):
            listing = [{'ID': m['ID'], 'To': [{'Name': '', 'Address': m['to']}]} for m in reversed(self.messages)]
            return self.reply(handler, 200, {'messages': listing, 'total': len(listing)})
        if method == 'GET' and parsed.path.startswith('/api/v1/message/'):
            message = next(m for m in self.messages if m['ID'] == parsed.path.rsplit('/', 1)[1])
            return self.reply(handler, 200, {'ID': message['ID'], 'Text': message['text'], 'HTML': ''})
        if route == ('POST', '/api/v1/session/bootstrap'):
            name, csrf = self.secret(), self.secret()
            self.sessions[name] = {'role': 'ANONYMOUS', 'csrf': csrf, 'email': None}
            return self.reply(handler, 200, {'role': 'ANONYMOUS', 'idleExpiresAt': 'x', 'absoluteExpiresAt': 'x',
                                             'csrfToken': csrf}, cookie=name)
        if route == ('GET', '/api/v1/services'):
            return self.reply(handler, 200, [{'id': SERVICE, 'version': 4, 'name': 'Synthetic', 'durationMinutes': 60,
                                              'priceMinor': 0, 'currency': 'EUR', 'active': True}])
        if route == ('POST', '/api/v1/guest/create-proofs'):
            token = self.secret()
            self.tokens[token] = body['email']
            self.messages.append({'ID': secrets.token_hex(8), 'to': body['email'],
                                  'text': f'Open this link and choose Verify email:\n{self.url}/verify#token={token}&purpose=CREATE\n'})
            return self.reply(handler, 202, {'accepted': True})
        if route == ('POST', '/api/v1/guest/redemptions'):
            email = self.tokens.pop(body['token'], None)
            if email is None or body['purpose'] != 'CREATE':
                return self.problem(handler, 400, 'PROOF_INVALID')
            del self.sessions[cookie]
            name, csrf = self.secret(), self.secret()
            self.sessions[name] = {'role': 'GUEST', 'csrf': csrf, 'email': email}
            return self.reply(handler, 200, {'role': 'GUEST', 'idleExpiresAt': 'x', 'absoluteExpiresAt': 'x',
                                             'csrfToken': csrf}, cookie=name)
        if route == ('GET', '/api/v1/booking-context'):
            return self.reply(handler, 200, {'service': {'id': SERVICE, 'version': 4}, 'policy': {'version': 7},
                                             'notice': {'version': 'synthetic-1', 'text': 'x', 'synthetic': True}})
        if route == ('GET', '/api/v1/availability'):
            taken = {b['startInstant'] for b, _ in self.bookings.values()}
            return self.reply(handler, 200, {'policyVersion': 7,
                                             'slots': [s for s in self.slots if s['startInstant'] not in taken]})
        if route == ('POST', '/api/v1/bookings'):
            if session['role'] != 'GUEST':
                return self.problem(handler, 403, 'ACCESS_DENIED')
            if self.booking_script:
                outcome = self.booking_script.pop(0)
                if outcome == 'drop':
                    handler.close_connection = True
                    return None
                return self.problem(handler, *outcome)
            if self.fail_bookings:
                self.fail_bookings -= 1
                return self.problem(handler, 503, 'TEMPORARILY_UNAVAILABLE')
            key = handler.headers.get('Idempotency-Key')
            if key in self.bookings:
                return self.reply(handler, 201, {'bookingId': self.bookings[key][1], 'version': 1})
            identifier = '20000000-0000-0000-0000-00000000000' + str(len(self.bookings) + 1)
            self.bookings[key] = (body, identifier)
            return self.reply(handler, 201, {'bookingId': identifier, 'version': 1})
        return self.problem(handler, 404, 'NOT_FOUND')

    def problem(self, handler, status, code, refresh=False):
        return self.reply(handler, status, {'status': status, 'code': code, 'correlationId': 'x', 'retryable': False,
                                            'refreshAvailability': refresh}, 'application/problem+json')

    def reply(self, handler, status, document, content_type='application/json', cookie=None):
        payload = document if isinstance(document, bytes) else json.dumps(document).encode()
        handler.send_response(status)
        handler.send_header('Content-Type', content_type)
        handler.send_header('Content-Length', str(len(payload)))
        if cookie:
            handler.send_header('Set-Cookie', f'purpuragenda-local={cookie}; Path=/; HttpOnly; SameSite=Lax')
        handler.end_headers()
        handler.wfile.write(payload)


class SeedTest(unittest.TestCase):
    def setUp(self):
        self.stack = FakeStack()
        self.addCleanup(self.stack.close)
        self.output = []
        self.sleeps = []

    def seeder(self):
        return SEED.Seeder(self.stack.url, self.stack.url, out=self.output.append, mail_timeout=0.2, poll=0.01,
                           sleep=self.sleeps.append)

    def api_calls(self):
        return [(r['method'], r['path']) for r in self.stack.requests if r['path'].startswith('/api/v1/')
                and not r['path'].startswith(('/api/v1/messages', '/api/v1/message/'))]

    def assert_no_secret_in(self, text):
        for value in self.stack.secrets_seen:
            self.assertNotIn(value, text)

    def test_three_bookings_follow_the_public_sequence(self):
        receipts = self.seeder().run(3)
        self.assertEqual(3, len(receipts))
        flow = [('POST', '/api/v1/session/bootstrap'), ('POST', '/api/v1/guest/create-proofs'),
                ('POST', '/api/v1/guest/redemptions'), ('GET', '/api/v1/booking-context'),
                ('GET', '/api/v1/availability'), ('POST', '/api/v1/bookings')]
        self.assertEqual([('GET', '/api/v1/services')] + flow * 3, self.api_calls())
        bookings = [r for r in self.stack.requests if r['path'] == '/api/v1/bookings']
        self.assertEqual(3, len({r['key'] for r in bookings}))
        for request in bookings:
            self.assertEqual('GUEST', request['session']['role'])
            self.assertEqual(self.stack.url, request['origin'])
            self.assertRegex(request['session']['email'], r'^demo\+[0-9a-f]{6}-[1-3]@example\.test$')
            body = request['body']
            self.assertEqual((SERVICE, 4, 7, 'synthetic-1', 'Europe/Brussels'),
                             (body['serviceId'], body['serviceVersion'], body['policyVersion'],
                              body['noticeVersion'], body['displayZone']))
            self.assertEqual('Exampleville', body['intake']['birthplace'])
        # Distinct starts, spread over distinct business days first.
        starts = [r['body']['startInstant'] for r in bookings]
        self.assertEqual(['2030-01-01T9:00:00Z', '2030-01-02T9:00:00Z', '2030-01-01T13:00:00Z'], starts)
        availability = next(r for r in self.stack.requests if r['path'] == '/api/v1/availability')
        self.assertEqual({'serviceId', 'from', 'to', 'displayZone'}, set(availability['query']))
        self.assertEqual(['Europe/Brussels'], availability['query']['displayZone'])
        text = '\n'.join(self.output)
        self.assertIn('Created 3 synthetic booking(s)', text)
        self.assertIn('20000000-0000-0000-0000-000000000001', text)
        self.assertGreaterEqual(len(self.stack.secrets_seen), 15)
        self.assert_no_secret_in(text)

    def test_uncertain_creation_retries_with_the_same_key_and_body(self):
        self.stack.fail_bookings = 1
        self.seeder().run(1)
        bookings = [r for r in self.stack.requests if r['path'] == '/api/v1/bookings']
        self.assertEqual(2, len(bookings))
        self.assertEqual(bookings[0]['key'], bookings[1]['key'])
        self.assertEqual(bookings[0]['body'], bookings[1]['body'])
        self.assertEqual(1, len(self.stack.bookings))
        self.assertEqual([2], self.sleeps)

    def bookings(self):
        return [r for r in self.stack.requests if r['path'] == '/api/v1/bookings']

    def test_lost_response_is_retried_with_the_same_key_and_body(self):
        self.stack.booking_script = ['drop']
        self.seeder().run(1)
        bookings = self.bookings()
        self.assertEqual(2, len(bookings))
        self.assertEqual(bookings[0]['key'], bookings[1]['key'])
        self.assertEqual(bookings[0]['body'], bookings[1]['body'])
        self.assertEqual(1, len(self.stack.bookings))

    def test_deterministic_client_errors_are_never_retried(self):
        for outcome in ((400, 'INVALID_REQUEST'), (403, 'ACCESS_DENIED'), (422, 'INTAKE_INVALID'),
                        (409, 'IDEMPOTENCY_CONFLICT'), (429, 'RATE_LIMITED')):
            with self.subTest(outcome=outcome):
                before = len(self.bookings())
                self.stack.booking_script = [outcome]
                with self.assertRaises(SEED.SeedError) as raised:
                    self.seeder().run(1)
                self.assertEqual(f'POST /bookings failed with HTTP {outcome[0]} {outcome[1]}', str(raised.exception))
                self.assertEqual(before + 1, len(self.bookings()))
                self.assertEqual([], self.sleeps)
        self.assertEqual({}, self.stack.bookings)

    def test_taken_start_selects_a_new_start_with_a_new_key(self):
        self.stack.booking_script = [(409, 'START_NOT_AVAILABLE', True)]
        self.seeder().run(1)
        first, second = self.bookings()
        self.assertNotEqual(first['key'], second['key'])
        self.assertNotEqual(first['body']['startInstant'], second['body']['startInstant'])

    def test_redirects_are_not_followed_with_session_credentials(self):
        elsewhere = FakeStack()
        self.addCleanup(elsewhere.close)
        self.stack.redirects[('POST', '/api/v1/session/bootstrap')] = elsewhere.url + '/collect'
        with self.assertRaises(SEED.SeedError) as raised:
            self.seeder().run(1)
        self.assertEqual('POST /session/bootstrap failed with HTTP 302', str(raised.exception))
        self.assertEqual([], elsewhere.requests)

    def test_missing_endpoint_fails_with_a_clear_message(self):
        self.stack.missing.add(('POST', '/api/v1/guest/create-proofs'))
        with self.assertRaises(SEED.SeedError) as raised:
            self.seeder().run(1)
        self.assertIn('POST /guest/create-proofs is not available on this server', str(raised.exception))

    def test_problem_codes_are_reported_without_bodies(self):
        self.stack.fail_bookings = 3
        with self.assertRaises(SEED.SeedError) as raised:
            self.seeder().run(1)
        self.assertEqual('POST /bookings failed with HTTP 503 TEMPORARILY_UNAVAILABLE', str(raised.exception))
        self.assertEqual(0, len(self.stack.bookings))

    def test_missing_mail_times_out_without_redeeming(self):
        original = self.stack.handle

        def drop_mail(handler, method):
            if handler.path.startswith('/api/v1/messages'):
                self.stack.messages.clear()
            return original(handler, method)
        self.stack.handle = drop_mail
        with self.assertRaises(SEED.SeedError) as raised:
            self.seeder().run(1)
        self.assertIn('no verification email', str(raised.exception))
        self.assertNotIn(('POST', '/api/v1/guest/redemptions'), self.api_calls())

    def test_failure_output_never_contains_tokens(self):
        self.stack.missing.add(('POST', '/api/v1/bookings'))
        errors = io.StringIO()
        seeder = self.seeder()
        with contextlib.redirect_stderr(errors), contextlib.redirect_stdout(errors):
            self.assertEqual(1, SEED.main([], seeder_factory=lambda target, mailpit: seeder))
        self.assertIn('DEMO_SEED_FAILED: POST /bookings is not available', errors.getvalue())
        self.assert_no_secret_in(errors.getvalue() + '\n'.join(self.output))


class OpenerTest(unittest.TestCase):
    """The default client ignores proxy variables and never follows redirects."""

    def setUp(self):
        self.stack, self.proxy, self.elsewhere = FakeStack(), FakeStack(), FakeStack()
        for server in (self.stack, self.proxy, self.elsewhere):
            self.addCleanup(server.close)
        variables = {'http_proxy': self.proxy.url, 'HTTP_PROXY': self.proxy.url, 'all_proxy': self.proxy.url,
                     'no_proxy': '', 'NO_PROXY': ''}
        patcher = mock.patch.dict(os.environ, variables)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_environment_proxy_is_ignored(self):
        # An empty ProxyHandler replaces the default one and registers no proxy-capable methods.
        self.assertFalse([h for h in SEED.Http().opener.handlers if isinstance(h, urllib.request.ProxyHandler)])
        response = SEED.Http().request('GET', self.stack.url + '/healthz', headers={'X-CSRF-Token': 'synthetic'})
        self.assertEqual(200, response.status)
        self.assertEqual([], self.proxy.requests)
        self.assertEqual(['/healthz'], [r['path'] for r in self.stack.requests])
        # Sensitivity check: the stock opener in this environment would have used the proxy.
        with contextlib.suppress(Exception):
            urllib.request.build_opener().open(self.stack.url + '/healthz', timeout=5).close()
        self.assertEqual(1, len(self.proxy.requests))

    def test_redirect_is_returned_not_followed(self):
        self.stack.redirects[('GET', '/start')] = self.elsewhere.url + '/collect'
        client = SEED.Http()
        client.cookies['purpuragenda-local'] = 'synthetic-cookie'
        response = client.request('GET', self.stack.url + '/start', headers={'X-CSRF-Token': 'synthetic'})
        self.assertEqual(302, response.status)
        self.assertEqual([], self.elsewhere.requests)


class RefusalTest(unittest.TestCase):
    def run_main(self, *arguments):
        errors = io.StringIO()
        factory = mock.Mock(side_effect=AssertionError('no network for refused targets'))
        with contextlib.redirect_stderr(errors), mock.patch.object(SEED.urllib.request, 'urlopen',
                                                                   side_effect=AssertionError('network')):
            code = SEED.main(list(arguments), seeder_factory=factory)
        return code, errors.getvalue(), factory

    def test_non_local_targets_are_refused_before_any_request(self):
        for arguments in (['--target', 'https://medinavie.com'], ['--target', 'http://127.0.0.1:8080'],
                          ['--target', 'http://localhost:8081'], ['--mailpit', 'http://mail.example.test:8025'],
                          ['--count', '4'], ['--count', '0']):
            with self.subTest(arguments=arguments):
                code, errors, factory = self.run_main(*arguments)
                self.assertEqual(2, code)
                self.assertIn('DEMO_SEED_REFUSED', errors)
                factory.assert_not_called()

    def test_unreachable_mailpit_refuses_seeding(self):
        seeder = SEED.Seeder('http://127.0.0.1:9', 'http://127.0.0.1:9')
        with self.assertRaisesRegex(SEED.SeedError, 'Mailpit not reachable'):
            seeder.preflight()

    def test_default_target_is_the_local_stack(self):
        factory = mock.Mock()
        self.assertEqual(0, SEED.main([], seeder_factory=factory))
        factory.assert_called_once_with('http://localhost:8080', 'http://localhost:8025')
        factory.return_value.run.assert_called_once_with(3)


if __name__ == '__main__':
    unittest.main()
