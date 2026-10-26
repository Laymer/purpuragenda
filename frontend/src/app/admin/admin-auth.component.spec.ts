import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AdminAuthComponent } from './admin-auth.component';
import { provideApi } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { routes } from '../app.config';
const now = Date.parse('2026-10-26T10:00:00Z');
const reply = (role = 'ANONYMOUS') => ({ role, csrfToken: 'synthetic-proof', idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() });
const settle = async () => { for (let i = 0; i < 18; i++) await Promise.resolve(); };
describe('Administrator authentication with real typed SDK', () => {
  let http: HttpTestingController, view: AdminAuthComponent, sessions: SessionAccess;
  let fixture: ReturnType<typeof TestBed.createComponent<AdminAuthComponent>>;
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now);
    TestBed.configureTestingModule({ imports: [AdminAuthComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1')] });
    http = TestBed.inject(HttpTestingController); sessions = TestBed.inject(SessionAccess);
    fixture = TestBed.createComponent(AdminAuthComponent); view = fixture.componentInstance; fixture.detectChanges();
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });
  function fill() { view.form.setValue({ email: 'synthetic@exämple.test', password: ' synthetic exact password ', totp: '123456' }); }
  async function pendingLogin() {
    fill(); const result = view.login(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply()); await settle();
    return { result, request: http.expectOne('/api/v1/admin/login') };
  }
  it('keeps book and verify routes and lazily loads actual administrator UI', async () => {
    expect(routes.some(route => route.path === 'book')).toBe(true); expect(routes.some(route => route.path === 'verify')).toBe(true);
    expect(await routes.find(route => route.path === 'admin/login')!.loadComponent!()).toBe(AdminAuthComponent);
  });
  it('validates a real DOM submit without sending credentials and associates errors', () => {
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); fixture.detectChanges();
    expect(view.message()).toContain('six-digit'); expect(fixture.nativeElement.querySelector('#admin-totp').getAttribute('aria-describedby')).toBe('admin-error');
    http.expectNone('/api/v1/session/bootstrap');
  });
  it('submits exact credentials once, privately rotates proof and clears secret controls', async () => {
    const { result, request } = await pendingLogin(); await view.login();
    expect(request.request.body.email === 'synthetic@exämple.test' && request.request.body.password === ' synthetic exact password ' && request.request.body.totp === '123456').toBe(true);
    expect(request.request.headers.get('X-CSRF-Token') === 'synthetic-proof').toBe(true); expect(view.form.controls.password.value).toBe('');
    request.flush(reply('ADMIN')); await result; fixture.detectChanges();
    expect(view.administrator()).toBe(true); expect(fixture.nativeElement.querySelector('form')).toBeNull(); expect(view.message()).toContain('complete');
  });
  it('handles wrong factors generically without exposing provider text or clearing the valid anonymous session', async () => {
    const { result, request } = await pendingLogin(); request.flush({ code: 'AUTHENTICATION_FAILED', detail: 'private synthetic provider detail' }, { status: 401, statusText: 'Unauthorized' }); await result;
    expect(sessions.state()?.role).toBe('ANONYMOUS'); expect(view.message()).toContain('fresh'); expect(view.message()).not.toContain('provider'); expect(view.form.controls.totp.value).toBe('');
  });
  it('honors Retry-After and clears cooldown only at equality', async () => {
    const { result, request } = await pendingLogin(); request.flush({}, { status: 429, statusText: 'Too many', headers: { 'Retry-After': '4' } }); await result;
    fill(); await view.login(); expect(view.cooling()).toBe(true); vi.advanceTimersByTime(3999); expect(view.cooling()).toBe(true); vi.advanceTimersByTime(1); expect(view.cooling()).toBe(false);
  });
  it('does not let a large valid Retry-After overflow a browser timer', async () => {
    const { result, request } = await pendingLogin(); request.flush({}, { status: 429, statusText: 'Too many', headers: { 'Retry-After': '3000000' } }); await result;
    vi.advanceTimersByTime(2147483647); expect(view.cooling()).toBe(true); vi.advanceTimersByTime(3000000000 - 2147483647); expect(view.cooling()).toBe(false);
  });
  it('treats lost authentication acknowledgement as uncertain and explicitly checks real server context', async () => {
    const { result, request } = await pendingLogin(); request.error(new ProgressEvent('error')); await result;
    expect(sessions.state()).toBeNull(); expect(view.message()).toContain('could not be confirmed');
    const check = view.checkSession(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply('ADMIN')); await check; expect(view.administrator()).toBe(true);
  });
  it('refreshes anonymous context explicitly and handles unavailable/expired sessions safely', async () => {
    const check = view.checkSession(); await view.checkSession(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply()); await check; expect(view.message()).toContain('fresh');
    fill(); const result = view.login(); await settle(); http.expectOne('/api/v1/admin/login').flush({}, { status: 401, statusText: 'Unauthorized' }); await result;
    expect(view.message()).toContain('expired'); expect(sessions.state()).toBeNull();
    const failed = view.checkSession(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush({}, { status: 503, statusText: 'Unavailable' }); await failed; expect(view.message()).toContain('could not be confirmed');
  });
  it.each([false, true])('clears private state for successful or uncertain sign-out (%s)', async uncertain => {
    const { result, request } = await pendingLogin(); request.flush(reply('ADMIN')); await result;
    const logout = view.logout(); await view.logout(); await settle(); const call = http.expectOne('/api/v1/session/logout');
    if (uncertain) call.error(new ProgressEvent('error')); else call.flush(null); await logout;
    expect(sessions.state()).toBeNull(); expect(view.message()).toContain(uncertain ? 'could not be confirmed' : 'signed out');
  });
  it('clears secrets on invalidation and rejects late successful authentication UI state', async () => {
    const { result, request } = await pendingLogin(); fill(); sessions.invalidate(); expect(view.form.controls.password.value).toBe('');
    request.flush(reply('ADMIN')); await result; expect(view.administrator()).toBe(false); expect(view.message()).toContain('expired');
  });
  it('does not repopulate a destroyed view after a late response', async () => {
    const { result, request } = await pendingLogin(); fixture.destroy(); request.flush(reply('ADMIN')); await result;
    expect(view.message()).toBe(''); expect(view.form.controls.password.value).toBe('');
  });
});
