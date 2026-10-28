import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AccountLoginComponent } from './account-login.component';
import { SessionAccess } from '../shared/access/session-access';
import { provideApi } from '../shared/generated';
import { routes } from '../app.config';
import { accountGuard } from './account-guard';
const now = Date.parse('2026-10-28T10:00:00Z');
const reply = (role = 'ANONYMOUS') => ({ role, csrfToken: 'synthetic-proof', idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() });
const settle = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
describe('Account authentication', () => {
  let http: HttpTestingController, sessions: SessionAccess, view: AccountLoginComponent;
  let fixture: ReturnType<typeof TestBed.createComponent<AccountLoginComponent>>;
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now);
    TestBed.configureTestingModule({ imports: [AccountLoginComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([])] });
    http = TestBed.inject(HttpTestingController); sessions = TestBed.inject(SessionAccess);
    fixture = TestBed.createComponent(AccountLoginComponent); view = fixture.componentInstance; fixture.detectChanges();
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });
  function fill() { view.form.setValue({ email: 'synthetic@exämple.test', password: ' synthetic exact password ' }); }
  async function login() { fill(); const pending = view.login(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply()); await settle(); return { pending, request: http.expectOne('/api/v1/account/login') }; }
  it('validates DOM submit, associates errors and never sends incomplete credentials', () => {
    document.body.appendChild(fixture.nativeElement);
    try { fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); fixture.detectChanges(); expect(view.message()).toContain('email and password'); expect(document.activeElement).toBe(fixture.nativeElement.querySelector('#account-login-error')); expect(fixture.nativeElement.querySelector('#account-email').getAttribute('aria-describedby')).toBe('account-login-error'); }
    finally { fixture.nativeElement.remove(); }
    http.expectNone('/api/v1/session/bootstrap');
  });
  it('sends exact credentials once, clears secrets and enters the account only after confirmation', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const { pending, request } = await login(); await view.login(); expect(request.request.body.password === ' synthetic exact password ').toBe(true); expect(request.request.headers.get('X-CSRF-Token') === 'synthetic-proof').toBe(true); expect(view.form.controls.password.value).toBe('');
    request.flush(reply('ACCOUNT')); await pending; fixture.detectChanges(); expect(view.account()).toBe(true); expect(navigate).toHaveBeenCalledWith('/account'); expect(fixture.nativeElement.querySelector('form')).toBeNull(); expect(fixture.nativeElement.querySelector('a[href="/account"]')).not.toBeNull();
  });
  it('maps incorrect passwords generically and keeps anonymous context', async () => {
    const { pending, request } = await login(); request.flush({ code: 'AUTHENTICATION_FAILED', detail: 'synthetic-provider' }, { status: 401, statusText: 'Denied' }); await pending; expect(view.message()).toContain('Check your login'); expect(view.message()).not.toContain('provider'); expect(sessions.state()?.role).toBe('ANONYMOUS'); expect(view.form.controls.password.value).toBe('');
  });
  it.each(['4', '3000000'])('honors bounded and long Retry-After %s without timer overflow', async seconds => {
    const { pending, request } = await login(); request.flush({}, { status: 429, statusText: 'Limited', headers: { 'Retry-After': seconds } }); await pending; fill(); await view.login(); expect(view.cooling()).toBe(true);
    const duration = Number(seconds) * 1000; if (duration > 2147483647) { vi.advanceTimersByTime(2147483647); expect(view.cooling()).toBe(true); vi.advanceTimersByTime(duration - 2147483647); } else { vi.advanceTimersByTime(duration - 1); expect(view.cooling()).toBe(true); vi.advanceTimersByTime(1); } expect(view.cooling()).toBe(false);
  });
  it('clears uncertain authentication and explicitly checks the cookie-backed session', async () => {
    const { pending, request } = await login(); request.error(new ProgressEvent('error')); await pending; expect(sessions.state()).toBeNull(); expect(view.message()).toContain('could not be confirmed');
    const check = view.checkSession(); await view.checkSession(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply('ACCOUNT')); await check; expect(view.message()).toContain('active'); expect(view.account()).toBe(true);
  });
  it('handles expired, unavailable and anonymous session checks without retaining form data', async () => {
    const { pending, request } = await login(); request.flush({}, { status: 401, statusText: 'Expired' }); await pending; expect(view.message()).toContain('expired');
    let check = view.checkSession(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush({}, { status: 503, statusText: 'Unavailable' }); await check; expect(view.message()).toContain('could not be confirmed');
    check = view.checkSession(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply()); await check; expect(view.message()).toContain('Sign in with'); fill(); sessions.invalidate(); expect(view.form.getRawValue()).toEqual({ email: '', password: '' }); expect(view.message()).toContain('expired');
  });
  it('ignores late authentication after destruction and handles a changed role without navigation', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockRejectedValue(new Error('synthetic navigation'));
    const { pending, request } = await login(); request.flush(reply('GUEST')); await pending; expect(view.message()).toContain('changed'); expect(navigate).not.toHaveBeenCalled();
    fill(); const second = view.login(); await settle(); const next = http.expectOne('/api/v1/account/login'); fixture.destroy(); next.flush(reply('ACCOUNT')); await second; expect(navigate).not.toHaveBeenCalled(); expect(view.form.controls.password.value).toBe('');
  });
  it('contains navigation rejection after a successful login', async () => {
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockRejectedValue(new Error('synthetic navigation')); const { pending, request } = await login(); request.flush(reply('ACCOUNT')); await pending; expect(view.busy()).toBe(false);
  });
  it('guards actual routes and redirects anonymous or failed context to sign in', async () => {
    expect(await routes.find(route => route.path === 'account/login')!.loadComponent!()).toBe(AccountLoginComponent); expect(routes.find(route => route.path === 'account')!.canActivate).toContain(accountGuard); expect(routes.find(route => route.path === 'account/bookings/:id')!.canActivate).toContain(accountGuard);
    const invoke = () => TestBed.runInInjectionContext(() => accountGuard({} as never, {} as never));
    let result = invoke(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply()); expect((await result).toString()).toBe('/account/login');
    sessions.invalidate(); result = invoke(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush({}, { status: 503, statusText: 'Unavailable' }); expect((await result).toString()).toBe('/account/login');
    result = invoke(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply('ACCOUNT')); expect(await result).toBe(true); expect(await invoke()).toBe(true);
  });
});
