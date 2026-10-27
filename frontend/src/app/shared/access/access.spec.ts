import { HttpClient, HttpErrorResponse, HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AdministratorService, SessionBootstrap, SessionBootstrapRoleEnum, provideApi } from '../generated';
import { AccessFailure, retryAfter, safeFailure } from './access-failure';
import { SessionAccess } from './session-access';
import { CommandAccess } from './command-access';

const now = Date.parse('2026-10-25T00:30:00Z');
const reply = (changes: Partial<SessionBootstrap> = {}): SessionBootstrap => ({ role: SessionBootstrapRoleEnum.Anonymous, csrfToken: 'SYNTHETIC_PROOF_A', idleExpiresAt: new Date(now + 60000).toISOString(), absoluteExpiresAt: new Date(now + 120000).toISOString(), ...changes });
const settle = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };

describe('Shared in-memory session and frozen command access', () => {
  let sessions: SessionAccess, commands: CommandAccess, http: HttpTestingController, client: HttpClient;
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now);
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1')] });
    sessions = TestBed.inject(SessionAccess); commands = TestBed.inject(CommandAccess);
    http = TestBed.inject(HttpTestingController); client = TestBed.inject(HttpClient);
  });
  afterEach(() => { TestBed.resetTestingModule(); http.verify(); vi.useRealTimers(); });
  async function ready(value = reply()): Promise<void> {
    const boot = sessions.bootstrap(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(value); await boot;
  }

  it('coalesces bootstrap and exposes no proof in public state or serialization', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    const first = sessions.bootstrap(), second = sessions.bootstrap(); expect(first === second).toBe(true);
    await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply()); await first;
    expect(sessions.state()?.role).toBe('ANONYMOUS');
    expect(JSON.stringify(sessions.state()).includes('SYNTHETIC_PROOF')).toBe(false);
    expect(JSON.stringify(sessions).includes('SYNTHETIC_PROOF')).toBe(false); expect(storage).not.toHaveBeenCalled(); storage.mockRestore();
  });

  it('serializes rotation before subsequent work and borrows only the fresh proof', async () => {
    await ready();
    const rotate = sessions.authenticate(csrf => TestBed.inject(AdministratorService).loginAdministrator(csrf, { email: 'owner@example.test', password: 'SYNTHETIC_PASSWORD', totp: '123456' }));
    await settle(); const auth = http.expectOne('/api/v1/admin/login'); http.expectNone('/api/v1/action');
    expect(auth.request.headers.get('X-CSRF-Token') === reply().csrfToken).toBe(true);
    auth.flush(reply({ csrfToken: 'SYNTHETIC_PROOF_B', role: SessionBootstrapRoleEnum.Admin })); const context = await rotate; expect('csrfToken' in context).toBe(false);
    const mutation = sessions.perform(csrf => client.post('/api/v1/action', {}, { headers: { 'X-CSRF-Token': csrf } })); await settle();
    const action = http.expectOne('/api/v1/action'); expect(action.request.headers.get('X-CSRF-Token') === 'SYNTHETIC_PROOF_B').toBe(true);
    action.flush({ ok: true }); await mutation; expect(sessions.state()?.role).toBe('ADMIN');
  });

  it('invalidates old intents on an explicit refresh that may observe another tab identity', async () => {
    await ready(); const refresh = sessions.bootstrap(); const command = commands.prepare('POST', '/api/v1/bookings', { synthetic: true });
    const queued = command.attempt(); const expired = expect(queued).rejects.toMatchObject({ kind: 'SESSION_EXPIRED' }); await settle();
    http.expectOne('/api/v1/session/bootstrap').flush(reply({ role: SessionBootstrapRoleEnum.Guest })); await refresh; await expired;
    expect(sessions.state()?.role).toBe('GUEST'); expect(command.state()).toBe('expired'); http.expectNone('/api/v1/bookings');
  });

  it('clears prior identity commands when authentication succeeds, including unchanged roles', async () => {
    await ready(); const authentication = sessions.authenticate(csrf => TestBed.inject(AdministratorService).loginAdministrator(csrf, { email: 'owner@example.test', password: 'SYNTHETIC_PASSWORD', totp: '123456' }));
    const command = commands.prepare('POST', '/api/v1/bookings', { synthetic: true }); const attempt = command.attempt();
    const expired = expect(attempt).rejects.toMatchObject({ kind: 'SESSION_EXPIRED' });
    await settle(); http.expectOne('/api/v1/admin/login').flush(reply({ csrfToken: 'SYNTHETIC_PROOF_B' }));
    await authentication; await expired; expect(command.state()).toBe('expired'); expect(sessions.invalidations()).toBe(1);
    http.expectNone('/api/v1/bookings'); expect(sessions.state()?.role).toBe('ANONYMOUS');
  });

  it('retains a valid context when the backend rejects authentication factors', async () => {
    await ready(); const generation = sessions.invalidations();
    const authentication = sessions.authenticate(csrf => TestBed.inject(AdministratorService).loginAdministrator(csrf, { email: 'owner@example.test', password: 'SYNTHETIC_PASSWORD', totp: '123456' }));
    const rejected = expect(authentication).rejects.toMatchObject({ kind: 'REJECTED', status: 401 }); await settle();
    http.expectOne('/api/v1/admin/login').flush({ code: 'AUTHENTICATION_FAILED' }, { status: 401, statusText: 'Unauthorized' }); await rejected;
    expect(sessions.state()).not.toBeNull(); expect(sessions.invalidations()).toBe(generation);
  });

  it('invalidates uncertain authentication rotation without replaying queued work', async () => {
    await ready(); const authentication = sessions.authenticate(csrf => TestBed.inject(AdministratorService).loginAdministrator(csrf, { email: 'owner@example.test', password: 'SYNTHETIC_PASSWORD', totp: '123456' }));
    const rejected = expect(authentication).rejects.toMatchObject({ kind: 'UNCERTAIN' });
    const mutation = commands.prepare('POST', '/api/v1/bookings', {}).attempt(); const expired = expect(mutation).rejects.toMatchObject({ kind: 'SESSION_EXPIRED' });
    await settle(); http.expectOne('/api/v1/admin/login').error(new ProgressEvent('error')); await rejected; await expired;
    expect(sessions.state()).toBeNull(); http.expectNone('/api/v1/bookings'); http.expectNone('/api/v1/admin/login');
  });

  it('bootstraps automatically for a first unsafe operation without an authentication bypass', async () => {
    const command = commands.prepare('POST', '/api/v1/bookings', { synthetic: true }); const attempt = command.attempt();
    await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply()); await settle();
    const mutation = http.expectOne('/api/v1/bookings'); expect(mutation.request.headers.has('X-CSRF-Token')).toBe(true);
    mutation.flush({ ok: true }); await attempt;
  });

  it('freezes body, idempotency and version across explicit uncertain retries', async () => {
    await ready(); const body = { synthetic: 'original' };
    const command = commands.prepare<{ ok: boolean }>('PATCH', '/api/v1/bookings/123', body, '"7"'); body.synthetic = 'changed';
    const first = command.attempt(); expect(command.attempt() === first).toBe(true);
    const rejected = expect(first).rejects.toMatchObject({ kind: 'UNCERTAIN' }); await settle();
    const request = http.expectOne('/api/v1/bookings/123'); const key = request.request.headers.get('Idempotency-Key');
    expect(request.request.body === '{"synthetic":"original"}').toBe(true); expect(request.request.headers.get('If-Match')).toBe('"7"');
    request.error(new ProgressEvent('error')); await rejected; await settle(); expect(command.state()).toBe('uncertain');
    http.expectNone('/api/v1/bookings/123');
    const retry = command.attempt(); await settle(); const repeated = http.expectOne('/api/v1/bookings/123');
    expect(repeated.request.headers.get('Idempotency-Key') === key).toBe(true); expect(repeated.request.body === request.request.body).toBe(true);
    repeated.flush({ ok: true }); expect(await retry).toEqual({ ok: true }); expect(command.state()).toBe('succeeded');
    expect(await command.attempt()).toEqual({ ok: true }); http.expectNone('/api/v1/bookings/123');
    expect(JSON.stringify(command).includes('original')).toBe(false); expect(String(command)).toBe('[FrozenCommand]');
  });

  it('treats authoritative version conflicts as rejection with no automatic retry', async () => {
    await ready(); const command = commands.prepare('POST', '/api/v1/bookings', {}); const attempt = command.attempt();
    const failed = expect(attempt).rejects.toMatchObject({ kind: 'VERSION_CONFLICT' }); await settle();
    http.expectOne('/api/v1/bookings').flush({ private: 'SYNTHETIC_PROVIDER_DETAIL' }, { status: 412, statusText: 'Precondition failed' });
    await failed; expect(command.state()).toBe('rejected'); await settle(); http.expectNone('/api/v1/bookings');
  });

  it('honors Retry-After before an explicit retry and preserves its submission identity', async () => {
    await ready(); const command = commands.prepare('POST', '/api/v1/bookings', {}); const attempt = command.attempt();
    const rejected = expect(attempt).rejects.toMatchObject({ kind: 'THROTTLED', retryAfterSeconds: 2 }); await settle();
    const first = http.expectOne('/api/v1/bookings'); const key = first.request.headers.get('Idempotency-Key');
    first.flush({}, { status: 429, statusText: 'Too many requests', headers: { 'Retry-After': '2' } }); await rejected;
    vi.advanceTimersByTime(1999); await expect(command.attempt()).rejects.toMatchObject({ kind: 'THROTTLED', retryAfterSeconds: 1 });
    http.expectNone('/api/v1/bookings'); vi.advanceTimersByTime(1);
    const retry = command.attempt(); await settle(); const next = http.expectOne('/api/v1/bookings');
    expect(next.request.headers.get('Idempotency-Key') === key).toBe(true); next.flush({}); await retry;
  });

  it('invalidates all frozen commands and private context at idle expiry equality', async () => {
    await ready(); const command = commands.prepare('POST', '/api/v1/bookings', { synthetic: true });
    vi.advanceTimersByTime(59999); expect(sessions.state()).not.toBeNull(); vi.advanceTimersByTime(1);
    expect(sessions.state()).toBeNull(); expect(sessions.invalidations()).toBe(1); expect(command.state()).toBe('expired');
    await expect(command.attempt()).rejects.toMatchObject({ kind: 'SESSION_EXPIRED' }); http.expectNone('/api/v1/bookings');
  });

  it('refuses old private commands when wall time expires before its timer callback', async () => {
    await ready(); const command = commands.prepare('POST', '/api/v1/bookings', { synthetic: true });
    vi.setSystemTime(now + 60000); await expect(command.attempt()).rejects.toMatchObject({ kind: 'SESSION_EXPIRED' });
    expect(sessions.state()).toBeNull(); http.expectNone('/api/v1/bookings'); http.expectNone('/api/v1/session/bootstrap');
  });

  it('honors the earlier absolute expiry and ignores a late bootstrap after invalidation', async () => {
    await ready(reply({ absoluteExpiresAt: new Date(now + 1000).toISOString() }));
    vi.advanceTimersByTime(1000); expect(sessions.state()).toBeNull();
    const boot = sessions.bootstrap(); const rejected = expect(boot).rejects.toMatchObject({ kind: 'SESSION_EXPIRED' });
    await settle(); const request = http.expectOne('/api/v1/session/bootstrap'); sessions.invalidate(); request.flush(reply());
    await rejected; expect(sessions.state()).toBeNull();
  });

  it('does not let a stale bootstrap failure invalidate a deliberate fresh context', async () => {
    const old = sessions.bootstrap(); const rejected = expect(old).rejects.toMatchObject({ kind: 'SESSION_EXPIRED' });
    await settle(); const stale = http.expectOne('/api/v1/session/bootstrap'); sessions.invalidate();
    const fresh = sessions.bootstrap(); stale.flush(reply()); await rejected; await settle();
    http.expectOne('/api/v1/session/bootstrap').flush(reply({ role: SessionBootstrapRoleEnum.Guest })); await fresh;
    expect(sessions.state()?.role).toBe('GUEST'); expect(sessions.invalidations()).toBe(1);
  });

  it('does not let stale mutation responses poison the next queued bootstrap', async () => {
    await ready(); const old = sessions.perform(csrf => client.post('/api/v1/action', {}, { headers: { 'X-CSRF-Token': csrf } }));
    const rejected = expect(old).rejects.toMatchObject({ kind: 'SESSION_EXPIRED' }); await settle();
    const request = http.expectOne('/api/v1/action'); sessions.invalidate(); const fresh = sessions.bootstrap();
    request.flush({}); await rejected; await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply()); await fresh;
    expect(sessions.state()).not.toBeNull(); expect(sessions.invalidations()).toBe(1);
  });

  it('rejects queued work after revocation and never replays it under a new session', async () => {
    await ready(); const first = commands.prepare('POST', '/api/v1/bookings', {}).attempt();
    const second = commands.prepare('POST', '/api/v1/other', {}).attempt();
    const failedFirst = expect(first).rejects.toMatchObject({ kind: 'SESSION_EXPIRED' }); const failedSecond = expect(second).rejects.toMatchObject({ kind: 'SESSION_EXPIRED' });
    await settle(); http.expectOne('/api/v1/bookings').flush({ code: 'SESSION_REQUIRED' }, { status: 401, statusText: 'Unauthorized' });
    await failedFirst; await failedSecond; http.expectNone('/api/v1/other'); expect(sessions.state()).toBeNull();
  });

  it('discards queued commands without sending an empty or changed payload', async () => {
    await ready(); const first = commands.prepare('POST', '/api/v1/first', {}).attempt();
    const second = commands.prepare('POST', '/api/v1/second', { synthetic: true }); const queued = second.attempt();
    const rejected = expect(queued).rejects.toMatchObject({ kind: 'SESSION_EXPIRED' }); await settle(); second.discard();
    http.expectOne('/api/v1/first').flush({}); await first; await rejected; http.expectNone('/api/v1/second');
  });

  it('does not resurrect a discarded in-flight command after its response arrives', async () => {
    await ready(); const command = commands.prepare('POST', '/api/v1/bookings', {}); const attempt = command.attempt(); await settle();
    const request = http.expectOne('/api/v1/bookings'); const rejected = expect(attempt).rejects.toMatchObject({ kind: 'SESSION_EXPIRED' }); command.discard(); request.flush({}); await rejected;
    expect(command.state()).toBe('expired'); await expect(command.attempt()).rejects.toMatchObject({ kind: 'SESSION_EXPIRED' });
  });

  it('logout uses the current proof then clears context and prepared commands', async () => {
    await ready(); const command = commands.prepare('POST', '/api/v1/bookings', {}); const logout = sessions.logout(); const queued = command.attempt(); const expired = expect(queued).rejects.toMatchObject({ kind: 'SESSION_EXPIRED' }); await settle();
    const request = http.expectOne('/api/v1/session/logout'); expect(request.request.headers.get('X-CSRF-Token') === reply().csrfToken).toBe(true);
    request.flush(null); await logout; await expired; http.expectNone('/api/v1/bookings'); expect(sessions.state()).toBeNull(); expect(command.state()).toBe('expired');
  });

  it('clears private context even when logout acknowledgement is lost', async () => {
    await ready(); const command = commands.prepare('POST', '/api/v1/bookings', { synthetic: true });
    const logout = sessions.logout(); const rejected = expect(logout).rejects.toMatchObject({ kind: 'UNCERTAIN' });
    const queued = command.attempt(); const expired = expect(queued).rejects.toMatchObject({ kind: 'SESSION_EXPIRED' }); await settle();
    http.expectOne('/api/v1/session/logout').error(new ProgressEvent('error')); await rejected; await expired; http.expectNone('/api/v1/bookings');
    expect(sessions.state()).toBeNull(); expect(command.state()).toBe('expired');
  });

  it('clears untrusted bootstrap state, then allows a deliberate fresh bootstrap', async () => {
    for (const value of [reply({ csrfToken: '' }), reply({ idleExpiresAt: new Date(now).toISOString() }), reply({ absoluteExpiresAt: 'invalid' }), ...['UNKNOWN', 'toString', '__proto__'].map(role => reply({ role: role as SessionBootstrapRoleEnum })), reply({ idleExpiresAt: new Date(now + 3e9).toISOString(), absoluteExpiresAt: new Date(now + 3e9).toISOString() })]) {
      const boot = sessions.bootstrap(); const rejected = expect(boot).rejects.toMatchObject({ kind: 'SESSION_EXPIRED' });
      await settle(); http.expectOne('/api/v1/session/bootstrap').flush(value); await rejected; expect(sessions.state()).toBeNull();
    }
    await ready(); expect(sessions.state()).not.toBeNull();
  });

  it('bounds a hung request as uncertain and does not auto-retry it', async () => {
    await ready(); const attempt = commands.prepare('POST', '/api/v1/bookings', {}).attempt();
    const rejected = expect(attempt).rejects.toMatchObject({ kind: 'UNCERTAIN' }); await settle(); const request = http.expectOne('/api/v1/bookings');
    vi.advanceTimersByTime(25000); await rejected; expect(request.cancelled).toBe(true); http.expectNone('/api/v1/bookings');
  });

  it('continues privacy invalidation if a view listener throws and supports removing listeners', async () => {
    await ready(); let calls = 0;
    const remove = sessions.onInvalidated(() => { calls++; }); remove();
    sessions.onInvalidated(() => { throw new Error('SYNTHETIC_VIEW_FAILURE'); });
    sessions.onInvalidated(() => { calls++; });
    expect(() => sessions.invalidate()).not.toThrow(); expect(calls).toBe(1);
  });

  it('rejects invalid endpoints, versions and unserializable commands without network work', () => {
    for (const path of ['https://example.test/api/v1/bookings', '//example.test/api/v1/bookings', '/api/v1/../private', '/api/v1/bookings?secret=synthetic']) expect(() => commands.prepare('POST', path, {})).toThrow(AccessFailure);
    for (const version of ['7', 'W/"7"', '"0"']) expect(() => commands.prepare('POST', '/api/v1/bookings', {}, version)).toThrow(AccessFailure);
    expect(() => commands.prepare('POST', '/api/v1/bookings', undefined)).toThrow(AccessFailure);
    expect(() => commands.prepare('GET' as 'POST', '/api/v1/bookings', {})).toThrow(AccessFailure);
    const circular: { self?: unknown } = {}; circular.self = circular;
    expect(() => commands.prepare('POST', '/api/v1/bookings', circular)).toThrow(AccessFailure);
  });
});

describe('Conflict codes', () => {
  it('exposes only a short uppercase conflict code, never arbitrary provider text', () => {
    const code = (value: unknown) => safeFailure(new HttpErrorResponse({ status: 409, error: { code: value } }), true).code;
    expect(code('MEETING_IN_USE')).toBe('MEETING_IN_USE'); expect(code('lower case provider text')).toBeUndefined();
    expect(code(42)).toBeUndefined(); expect(code('A'.repeat(41))).toBeUndefined();
    expect(safeFailure(new HttpErrorResponse({ status: 412, error: { code: 'MEETING_IN_USE' } }), true).code).toBeUndefined();
  });
});

describe('Safe transport failures and Retry-After', () => {
  it('projects only known safe categories and never provider bodies or error causes', () => {
    for (const [status, kind] of [[401, 'SESSION_EXPIRED'], [403, 'REJECTED'], [409, 'CONFLICT'], [412, 'VERSION_CONFLICT'], [0, 'UNCERTAIN'], [200, 'UNCERTAIN'], [408, 'UNCERTAIN'], [503, 'UNCERTAIN']] as const) {
      const failure = safeFailure(new HttpErrorResponse({ status, error: { detail: 'SYNTHETIC_PROVIDER_DETAIL' } }), true);
      expect(failure.kind).toBe(kind); expect(JSON.stringify(failure).includes('SYNTHETIC_PROVIDER_DETAIL')).toBe(false); expect(failure.cause).toBeUndefined();
    }
    expect(safeFailure(new HttpErrorResponse({ status: 403, error: { code: 'CSRF_REJECTED' } }), true).kind).toBe('SESSION_EXPIRED');
    expect(safeFailure(new HttpErrorResponse({ status: 503 }), false).kind).toBe('UNAVAILABLE');
    expect(safeFailure(new Error('SYNTHETIC_PROVIDER_DETAIL'), false).kind).toBe('UNAVAILABLE');
    expect(safeFailure(new Error('SYNTHETIC_PROVIDER_DETAIL'), true).kind).toBe('UNCERTAIN');
    const existing = new AccessFailure('REJECTED'); expect(safeFailure(existing, true) === existing).toBe(true);
    expect(safeFailure(new HttpErrorResponse({ status: 429, headers: new HttpHeaders({ 'Retry-After': '7' }) }), true)).toMatchObject({ kind: 'THROTTLED', retryAfterSeconds: 7 });
  });
  it('retains normal server delays and rejects unsafe or malformed cooldowns', () => {
    expect(retryAfter('7', now)).toBe(7); expect(retryAfter('0', now)).toBe(1);
    expect(retryAfter(new Date(now + 12000).toUTCString(), now)).toBe(12); expect(retryAfter(new Date(now - 1000).toUTCString(), now)).toBe(1);
    for (const header of [null, 'invalid', '-2', '1.5', 'Fri, 31 Feb 2026 12:00:00 GMT', '1' + '0'.repeat(100), '9'.repeat(400), '9000000000000']) expect(retryAfter(header, now)).toBe(60);
  });
});
