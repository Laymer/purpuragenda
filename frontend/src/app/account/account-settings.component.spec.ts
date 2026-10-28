import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AccountSettingsComponent } from './account-settings.component';
import { SessionAccess } from '../shared/access/session-access';
import { CommandAccess } from '../shared/access/command-access';
import { provideApi } from '../shared/generated';
import { routes } from '../app.config';
const now = Date.parse('2026-10-28T10:00:00Z');
const profile = (version = 1) => ({ loginEmail: 'synthetic@example.test', version, firstName: '', lastName: '', telephone: '', observedAt: new Date(now).toISOString() });
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };
describe('Account settings', () => {
  let http: HttpTestingController, sessions: SessionAccess, view: AccountSettingsComponent;
  let fixture: ReturnType<typeof TestBed.createComponent<AccountSettingsComponent>>;
  const read = () => http.expectOne(r => r.url === '/api/v1/account/profile' && r.method === 'GET');
  const write = () => http.expectOne(r => r.url === '/api/v1/account/profile' && r.method === 'POST');
  const ready = async (tag?: string) => { read().flush(profile(), { headers: tag ? { ETag: tag } : {} }); await settle(); };
  const bootstrap = async () => { await settle(); http.expectOne('/api/v1/session/bootstrap').flush({ role: 'ACCOUNT', csrfToken: 'synthetic-proof', idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() }); await settle(); };
  const credentials = () => view.passwords.setValue({ current: 'synthetic old password', replacement: 'synthetic new password', confirm: 'synthetic new password' });
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now);
    TestBed.configureTestingModule({ imports: [AccountSettingsComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([])] });
    http = TestBed.inject(HttpTestingController); sessions = TestBed.inject(SessionAccess); fixture = TestBed.createComponent(AccountSettingsComponent); view = fixture.componentInstance; fixture.detectChanges();
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });
  it('loads the guarded settings route and immutable login identity', async () => {
    await ready(); fixture.detectChanges(); const route = routes.find(r => r.path === 'account/settings')!;
    expect(await route.loadComponent!()).toBe(AccountSettingsComponent); expect(route.canActivate?.length).toBe(1);
    expect(fixture.nativeElement.textContent).toContain('synthetic@example.test'); expect(view.form.getRawValue()).toEqual({ firstName: '', lastName: '', telephone: '' });
  });
  it('sends normalized optional fields with the exact ETag and refreshes on success', async () => {
    await ready('"7"'); view.form.setValue({ firstName: ' E\u0301lodie ', lastName: ' Example ', telephone: '+32 470 00 00 01' });
    const save = view.saveProfile(); await view.saveProfile(); await view.load(); await bootstrap(); const request = write();
    expect(request.request.headers.get('If-Match')).toBe('"7"'); expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(JSON.parse(request.request.body)).toEqual({ firstName: 'Élodie', lastName: 'Example', telephone: '+32 470 00 00 01' }); request.flush({}); await settle(); read().flush(profile(8)); await save; expect(view.message()).toContain('was saved');
  });
  it.each(['a'.repeat(101), 'bad\u0001name'])('rejects invalid names before a request', async name => { await ready(); view.form.controls.firstName.setValue(name); await view.saveProfile(); expect(view.message()).toContain('at most 100'); });
  it.each(['local', '+012345678', '+1', '+' + '1'.repeat(41)])('rejects invalid telephone %s', async telephone => { await ready(); view.form.controls.telephone.setValue(telephone); await view.saveProfile(); expect(view.message()).toContain('international'); });
  it('contains command preparation failure', async () => { await ready(); vi.spyOn(TestBed.inject(CommandAccess), 'prepare').mockImplementation(() => { throw Error('synthetic'); }); await view.saveProfile(); expect(view.message()).toContain('could not be prepared'); });
  it.each([400, 412, 428])('unlocks an uncertain command after a definite %s response', async status => {
    await ready('W/"1"'); const save = view.saveProfile(); await bootstrap(); const first = write(); const key = first.request.headers.get('Idempotency-Key'), body = first.request.body;
    expect(first.request.headers.get('If-Match')).toBe('"1"'); first.error(new ProgressEvent('error')); await save; expect(view.uncertain()).toBe(true);
    await view.load(); await view.saveProfile(); credentials(); await view.changePassword(); const retry = view.retry(); await settle(); const second = write(); expect(second.request.body).toBe(body); expect(second.request.headers.get('Idempotency-Key')).toBe(key);
    second.flush({}, { status, statusText: 'Rejected' }); await settle(); if (status !== 400) read().flush(profile(2)); await retry; expect(view.uncertain()).toBe(false); expect(view.busy()).toBe(false); expect(view.message()).toContain(status === 400 ? 'could not be saved' : 'Review the latest');
  });
  it('honours Retry-After on the same profile command', async () => {
    await ready(); const save = view.saveProfile(); await bootstrap(); const first = write(), key = first.request.headers.get('Idempotency-Key'); first.flush({}, { status: 429, statusText: 'Wait', headers: { 'Retry-After': '2' } }); await save;
    await view.retry(); expect(view.uncertain()).toBe(true); await vi.advanceTimersByTimeAsync(2000); const retry = view.retry(); await settle(); const second = write(); expect(second.request.headers.get('Idempotency-Key')).toBe(key); second.flush({}); await settle(); read().flush(profile(2)); await retry; expect(view.uncertain()).toBe(false);
  });
  it.each([401, 403])('clears a denied profile read %s', async status => { read().flush({}, { status, statusText: 'Denied' }); await settle(); expect(view.lost()).toBe(true); await view.load(); await view.saveProfile(); await view.retry(); });
  it.each([null, 'unavailable'])('permits retry after a failed read %s', async kind => { if (kind === null) read().flush(null); else read().flush({}, { status: 503, statusText: 'Unavailable' }); await settle(); expect(view.profile()).toBeNull(); const retry = view.load(); read().flush(profile()); await retry; expect(view.profile()).not.toBeNull(); });
  it.each([false, true])('ignores a late read after private state cleared %s', async destroy => { const request = read(); if (destroy) fixture.destroy(); else sessions.invalidate(); request.flush(profile()); await settle(); expect(view.profile()).toBeNull(); });
  it('does not replace an invalidated session message after a conflict reload', async () => { await ready(); const save = view.saveProfile(); await bootstrap(); write().flush({}, { status: 412, statusText: 'Stale' }); await settle(); const request = read(); sessions.invalidate(); request.flush(profile(2)); await save; expect(view.profile()).toBeNull(); expect(view.message()).toContain('session expired'); });
  it.each([false, true])('reports a committed save even if its refresh fails %s', async conflict => { await ready(); const save = view.saveProfile(); await bootstrap(); if (conflict) write().flush({}, { status: 428, statusText: 'Missing' }); else write().flush({}); await settle(); read().flush({}, { status: 503, statusText: 'Unavailable' }); await save; expect(view.message()).toContain(conflict ? 'Reload its latest' : 'was saved, but'); });
  it('validates password confirmation and Unicode lengths before sending', async () => { await ready(); await view.changePassword(); expect(view.passwordInvalid('current')).toBe(true); credentials(); view.passwords.controls.confirm.setValue('different'); await view.changePassword(); expect(view.passwordInvalid('confirm')).toBe(true); view.passwords.patchValue({ replacement: '😀'.repeat(8), confirm: '😀'.repeat(8) }); await view.changePassword(); expect(view.passwordInvalid('replacement')).toBe(true); });
  it.each([204, 0, 401, 403, 429])('handles password result %s without replay or retaining credentials', async status => {
    await ready(); credentials(); const change = view.changePassword(); await view.changePassword(); expect(view.passwords.controls.current.value).toBe(''); await bootstrap(); const request = http.expectOne('/api/v1/account/password');
    expect(request.request.body).toEqual({ email: 'synthetic@example.test', currentPassword: 'synthetic old password', newPassword: 'synthetic new password' });
    if (status === 0) request.error(new ProgressEvent('error')); else if (status === 204) request.flush(null, { status, statusText: 'No content' }); else request.flush({ code: status === 401 ? 'AUTHENTICATION_FAILED' : 'DENIED' }, { status, statusText: 'Denied', headers: { 'Retry-After': '2' } });
    await change; expect(view.passwords.controls.replacement.value).toBe(''); expect(request.request.body.currentPassword).toBe('');
    if (status === 429) { expect(view.cooling()).toBe(true); credentials(); await view.changePassword(); await vi.advanceTimersByTimeAsync(2000); expect(view.cooling()).toBe(false); }
    else if (status === 401) { expect(view.lost()).toBe(false); expect(view.message()).toContain('current password'); }
    else { expect(view.lost()).toBe(true); expect(view.profile()).toBeNull(); expect(view.message()).toContain(status === 204 ? 'all account sessions' : status === 0 ? 'could not be confirmed' : 'session expired'); }
  });
  it('ignores late password completion after navigation', async () => { await ready(); credentials(); const change = view.changePassword(); await bootstrap(); const request = http.expectOne('/api/v1/account/password'); fixture.destroy(); request.flush(null); await change; expect(view.profile()).toBeNull(); });
});
