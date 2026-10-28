import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { AccountHistoryComponent } from './account-history.component';
import { SessionAccess } from '../shared/access/session-access';
import { provideApi } from '../shared/generated';
import { routes } from '../app.config';
const now = Date.parse('2026-10-28T10:00:00Z');
const id = (n: number) => '00000000-0000-4000-8000-00000000000' + n;
const entry = (n: number) => ({ id: id(n), reference: 'B-synthetic-' + n, state: 'PENDING_CONFIRMATION', startInstant: '2026-10-30T09:00:00Z', sessionEndInstant: '2026-10-30T10:00:00Z', pendingExpired: true, serviceName: 'Synthetic consultation', priceMinor: 10000, currency: 'EUR' });
const cursor = { beforeStart: '2026-10-30T09:00:00Z', beforeId: id(1) };
const page = (bookings: unknown[], next: object | null = null) => ({ bookings, next, observedAt: new Date(now).toISOString() });
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };
describe('Account history', () => {
  let http: HttpTestingController, sessions: SessionAccess, view: AccountHistoryComponent;
  let fixture: ReturnType<typeof TestBed.createComponent<AccountHistoryComponent>>;
  const list = () => http.expectOne(request => request.url === '/api/v1/account/history');
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now);
    TestBed.configureTestingModule({ imports: [AccountHistoryComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([])] });
    http = TestBed.inject(HttpTestingController); sessions = TestBed.inject(SessionAccess); fixture = TestBed.createComponent(AccountHistoryComponent); view = fixture.componentInstance; fixture.detectChanges();
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });
  it('renders private account-owned links, Brussels offsets and expired pending holds', async () => {
    expect(await routes.find(route => route.path === 'account')!.loadComponent!()).toBe(AccountHistoryComponent);
    const request = list(); expect(request.request.params.get('limit')).toBe('20'); expect(request.request.params.has('beforeStart')).toBe(false); request.flush(page([entry(1)])); await settle(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a[href="/account/bookings/' + id(1) + '"]')).not.toBeNull(); expect(fixture.nativeElement.textContent).toContain('Europe/Brussels'); expect(fixture.nativeElement.textContent).toContain('UTC+01:00'); expect(fixture.nativeElement.textContent).toContain('hold has expired'); expect(view.loaded()).toBe(true);
  });
  it('uses the exact server cursor, deduplicates overlapping pages and resets on refresh', async () => {
    list().flush(page([entry(1)], cursor)); await settle(); const more = view.more(); await view.more(); await view.load(); const request = list(); expect(new URL(request.request.urlWithParams, 'https://example.test').searchParams.get('beforeStart')).toBe(cursor.beforeStart); expect(request.request.params.get('beforeId')).toBe(cursor.beforeId); request.flush(page([entry(1), entry(2)])); await more; expect(view.entries().length).toBe(2); await view.more(); http.expectNone(r => r.url === '/api/v1/account/history');
    const refresh = view.load(); expect(view.entries()).toEqual([]); list().flush(page([])); await refresh; fixture.detectChanges(); expect(fixture.nativeElement.textContent).toContain('No bookings');
  });
  it.each([401, 403])('clears private state and redirects a %s authority failure', async status => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true); list().flush({ code: status === 401 ? 'SESSION_REQUIRED' : 'ACCOUNT_ACCESS_REQUIRED' }, { status, statusText: 'Denied' }); await settle(); expect(view.entries()).toEqual([]); expect(view.lost()).toBe(true); expect(navigate).toHaveBeenCalledWith('/account/login'); expect(sessions.state()).toBeNull();
  });
  it('contains navigation failures and never exposes provider errors', async () => {
    document.body.appendChild(fixture.nativeElement);
    try { list().flush({ detail: 'synthetic-provider-detail' }, { status: 503, statusText: 'Unavailable' }); await settle(); expect(view.message()).toContain('could not be loaded'); expect(view.message()).not.toContain('provider'); expect(document.activeElement).toBe(fixture.nativeElement.querySelector('#history-message')); }
    finally { fixture.nativeElement.remove(); }
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockRejectedValue(new Error('synthetic-navigation')); const retry = view.load(); list().flush({}, { status: 403, statusText: 'Denied' }); await retry; expect(view.busy()).toBe(false);
  });
  it.each([false, true])('ignores late responses after invalidation or destruction (%s)', async destroyed => {
    const request = list(); await view.logout(); await view.load(); if (destroyed) fixture.destroy(); else sessions.invalidate(); request.flush(page([entry(1)], cursor)); await settle(); expect(view.entries()).toEqual([]); expect(view.cursor()).toBeNull(); expect(view.loaded()).toBe(false); if (!destroyed) { await view.load(); http.expectNone(r => r.url === '/api/v1/account/history'); }
  });
  it('times out a stalled read and permits a deliberate retry', async () => {
    const request = list(); await vi.advanceTimersByTimeAsync(25000); expect(request.cancelled).toBe(true); expect(view.message()).toContain('could not be loaded'); const retry = view.load(); list().flush(page([])); await retry; expect(view.loaded()).toBe(true);
  });
  it.each([false, true])('clears all history for confirmed or uncertain sign-out (%s)', async uncertain => {
    list().flush(page([entry(1)], cursor)); await settle(); const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const logout = view.logout(); await view.logout(); expect(view.entries()).toEqual([]); await settle(); http.expectOne('/api/v1/session/bootstrap').flush({ role: 'ACCOUNT', csrfToken: 'synthetic-proof', idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() }); await settle(); const request = http.expectOne('/api/v1/session/logout');
    if (uncertain) request.error(new ProgressEvent('error')); else request.flush(null); await logout; expect(sessions.state()).toBeNull(); expect(view.cursor()).toBeNull(); if (uncertain) expect(view.message()).toContain('could not be confirmed'); else expect(navigate).toHaveBeenCalledWith('/account/login');
  });
});
