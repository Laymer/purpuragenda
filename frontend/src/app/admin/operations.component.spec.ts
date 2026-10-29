import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { provideApi, AdministratorNotificationStateEnum } from '../shared/generated';
import { CommandAccess } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { OperationsComponent } from './operations.component';

const id = '30000000-0000-4000-8000-000000000001', booking = '30000000-0000-4000-8000-000000000002';
const notifications = '/api/v1/admin/notifications', reconciliation = '/api/v1/admin/reconciliation', retryPath = notifications + '/' + id + '/retry';
const now = Date.parse('2026-10-28T10:00:00Z'), timestamp = new Date(now).toISOString();
const session = { role: 'ADMIN', csrfToken: 'x'.repeat(43), idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() };
const job = { id, bookingId: booking, reference: 'SYNTHETIC', audience: 'CUSTOMER', template: '<b>BOOKING_CREATED</b>', version: 42, state: AdministratorNotificationStateEnum.Failed, attempts: 7, dueAt: timestamp, safeCode: 'SMTP_DELIVERY_FAILED' };
const jobs = { entries: [job], nextCursor: booking, serverNow: timestamp };
const refunds = { entries: [{ receiptId: id, bookingId: booking, reference: '<b>SYNTHETIC</b>', state: 'CANCELLED', currency: 'EUR', receivedMinor: 1000, refundedMinor: 400, dueMinor: 1000, outstandingMinor: 600, reason: 'LATE_RECEIPT', verifiedAt: timestamp }], nextCursor: booking, serverNow: timestamp };
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };
describe('Administrator operations follow-up', () => {
  let http: HttpTestingController, router: Router, route: BehaviorSubject<{ mode: string }>;
  let fixture: ReturnType<typeof TestBed.createComponent<OperationsComponent>>, view: OperationsComponent;
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now); route = new BehaviorSubject({ mode: 'notifications' });
    TestBed.configureTestingModule({ imports: [OperationsComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([]), { provide: ActivatedRoute, useValue: { data: route.asObservable() } }] });
    http = TestBed.inject(HttpTestingController); router = TestBed.inject(Router);
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });
  function create(mode = 'notifications') { route.next({ mode }); fixture = TestBed.createComponent(OperationsComponent); view = fixture.componentInstance; fixture.detectChanges(); }
  async function loaded(mode = 'notifications') { create(mode); http.expectOne(r => r.url === (mode === 'notifications' ? notifications : reconciliation)).flush(mode === 'notifications' ? jobs : refunds); await settle(); fixture.detectChanges(); }
  async function post() { await settle(); http.match('/api/v1/session/bootstrap').forEach(r => r.flush(session)); await settle(); return http.expectOne(retryPath); }
  async function refresh() { await settle(); http.expectOne(r => r.url === notifications).flush({ ...jobs, entries: [], nextCursor: null }); await settle(); fixture.detectChanges(); }
  function select(reason = 'Synthetic retry reason') { view.choose(id); view.reasonForm.controls.reason.setValue(reason); fixture.detectChanges(); }
  it('shows full obligation separately from outstanding money and links existing finance entry', async () => {
    await loaded('reconciliation'); expect(fixture.nativeElement.textContent).toContain('Full obligation EUR 10.00; outstanding EUR 6.00'); expect(fixture.nativeElement.textContent).toContain('refunded EUR 4.00'); expect(fixture.nativeElement.querySelector('li b')).toBeNull(); expect(fixture.nativeElement.querySelector('a').getAttribute('href')).toBe('/admin/bookings/' + booking + '/finance');
    view.choose(id); await view.submit(); expect(view.selected()).toBeNull(); const p = view.more(); const r = http.expectOne(q => q.url === reconciliation); expect(r.request.params.get('cursor')).toBe(booking); expect(r.request.params.get('limit')).toBe('20'); expect(r.request.params.has('bookingId')).toBe(false); r.flush({ ...refunds, entries: [], nextCursor: null }); await p; fixture.detectChanges(); expect(fixture.nativeElement.textContent).toContain('No outstanding refunds'); await view.more();
  });
  it('defaults to failed notifications and preserves the loaded status when paging edited filters', async () => {
    create(); const initial = http.expectOne(r => r.url === notifications); expect(initial.request.params.get('state')).toBe('FAILED'); expect(initial.request.params.has('bookingId')).toBe(false); initial.flush(jobs); await settle(); fixture.detectChanges(); expect(fixture.nativeElement.querySelector('li b')).toBeNull(); expect(fixture.nativeElement.textContent).toContain('SMTP DELIVERY FAILED'); expect(fixture.nativeElement.textContent).toContain('Europe/Brussels');
    view.filterForm.controls.state.setValue('SENT'); const p = view.more(); const r = http.expectOne(q => q.url === notifications); expect(r.request.params.get('state')).toBe('FAILED'); expect(r.request.params.get('cursor')).toBe(booking); r.flush({ ...jobs, entries: [], nextCursor: null }); await p;
    const apply = view.load(); const changed = http.expectOne(q => q.url === notifications); expect(changed.request.params.get('state')).toBe('SENT'); changed.flush({ ...jobs, entries: [], nextCursor: null }); await apply; fixture.detectChanges(); expect(fixture.nativeElement.textContent).toContain('No booking notifications');
    view.filterForm.controls.state.setValue(''); const all = view.load(); const unfiltered = http.expectOne(q => q.url === notifications); expect(unfiltered.request.params.has('state')).toBe(false); unfiltered.flush(jobs); await all;
    view.filterForm.controls.state.setValue('INVALID'); expect(await view.load()).toBe(false); expect(view.message()).toContain('Choose a delivery status');
  });
  it('allows only failed jobs with a safe positive observed attempt count', async () => {
    await loaded(); view.choose('missing'); expect(view.selected()).toBeNull();
    for (const state of [AdministratorNotificationStateEnum.Sent, AdministratorNotificationStateEnum.Queued, AdministratorNotificationStateEnum.Leased, AdministratorNotificationStateEnum.Suppressed]) expect(view.retryable({ ...job, state })).toBe(false);
    for (const attempts of [0, -1, 1.5, 2147483647, Number.MAX_SAFE_INTEGER + 1]) expect(view.retryable({ ...job, attempts })).toBe(false);
    view.jobs.set([{ ...job, state: AdministratorNotificationStateEnum.Sent }]); view.choose(id); expect(view.selected()).toBeNull();
    view.jobs.set([job]); select(); view.cancel(); expect(view.selected()).toBeNull(); expect(view.reasonForm.controls.reason.value).toBe(''); await view.retrySame();
  });
  it('queues a normalized reason with observed attempts rather than content revision and refreshes', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'); await loaded(); select(' Synthetic cafe\u0301 retry reason '); fixture.nativeElement.querySelectorAll('form')[1].dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    const r = await post(); expect(JSON.parse(r.request.body)).toEqual({ reason: 'Synthetic café retry reason' }); expect(r.request.headers.get('If-Match')).toBe('"7"'); expect(r.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/); await view.submit(); await view.load(); await view.more(); view.choose(id); view.cancel();
    r.flush({ jobId: id, auditId: booking, version: 1 }); await refresh(); expect(view.message()).toContain('Retry queued'); expect(view.selected()).toBeNull(); expect(view.reasonForm.controls.reason.value).toBe(''); expect(storage).not.toHaveBeenCalled();
  });
  it.each(['short', ' ', 'x'.repeat(501), '😀'.repeat(501), 'Synthetic\nreason', 'Synthetic\u0085reason', 'Synthetic\ud800reason'])('rejects invalid reasons (%s)', async reason => {
    await loaded(); select(reason); await view.submit(); expect(view.message()).toContain('10–500'); http.expectNone(retryPath); expect(document.activeElement?.id).toBe('operations-message');
  });
  it('accepts 500 supplementary characters and safely handles preparation or validation failure', async () => {
    await loaded(); select('😀'.repeat(500)); const p = view.submit(); const r = await post(); expect([...JSON.parse(r.request.body).reason]).toHaveLength(500); r.flush({ detail: 'private-provider' }, { status: 400, statusText: 'x' }); await p; expect(view.message()).not.toContain('provider'); expect(view.loaded()).toBe(false); await view.submit(); await view.more();
    const reload = view.load(); http.expectOne(q => q.url === notifications).flush(jobs); await reload; select(); vi.spyOn(TestBed.inject(CommandAccess), 'prepare').mockImplementation(() => { throw new Error('synthetic'); }); await view.submit(); expect(view.message()).toContain('could not be submitted');
  });
  it('freezes body key and observed attempts through uncertainty and throttling until explicit retry', async () => {
    await loaded(); select(); const p = view.submit(); const r = await post(), body = r.request.body, key = r.request.headers.get('Idempotency-Key'); r.flush({}, { status: 503, statusText: 'x' }); await p;
    expect(view.uncertain()).toBe(true); view.reasonForm.controls.reason.setValue('Changed draft'); view.jobs.set([{ ...job, attempts: 8 }]); await view.submit(); await view.load(); await view.more(); view.choose(id); view.cancel();
    const again = view.retrySame(); const limited = await post(); expect(limited.request.body).toBe(body); expect(limited.request.headers.get('If-Match')).toBe('"7"'); limited.flush({}, { status: 429, statusText: 'x', headers: { 'Retry-After': '2' } }); await again; await view.retrySame(); http.expectNone(retryPath);
    await vi.advanceTimersByTimeAsync(2001); const final = view.retrySame(); const success = await post(); expect(success.request.body).toBe(body); expect(success.request.headers.get('Idempotency-Key')).toBe(key); success.flush({ jobId: id, auditId: booking, version: 1 }); await refresh(); await final; expect(view.uncertain()).toBe(false); await view.retrySame();
  });
  it.each([412, 428])('reloads changed attempt preconditions before another explicit review (%s)', async status => {
    await loaded(); select(); const p = view.submit(); (await post()).flush({}, { status, statusText: 'x' }); await refresh(); await p; expect(view.message()).toContain('notification changed'); expect(view.selected()).toBeNull(); await view.submit();
  });
  it('does not claim a stale-version refresh succeeded when its read fails', async () => {
    await loaded(); select(); const p = view.submit(); (await post()).flush({}, { status: 412, statusText: 'x' }); await settle(); http.expectOne(q => q.url === notifications).flush({}, { status: 503, statusText: 'x' }); await p; expect(view.message()).toContain('could not be refreshed'); expect(view.loaded()).toBe(false);
  });
  it.each(['DELIVERY_SUPPRESSED', 'NOTIFICATION_NOT_RETRYABLE', 'IDEMPOTENCY_KEY_REUSED'])('handles definite conflicts without retaining a retry command (%s)', async code => {
    await loaded(); select(); const p = view.submit(); (await post()).flush({ code, detail: 'private-provider' }, { status: 409, statusText: 'x' }); await p; expect(view.message()).toContain(code === 'DELIVERY_SUPPRESSED' ? 'Delivery is paused' : 'current state'); expect(view.message()).not.toContain('provider'); expect(view.loaded()).toBe(false); expect(view.selected()).toBeNull(); await view.retrySame();
  });
  it('preserves successful queue evidence when refreshing fails and blocks another retry', async () => {
    await loaded(); select(); const p = view.submit(); (await post()).flush({ jobId: id, auditId: booking, version: 1 }); await settle(); http.expectOne(q => q.url === notifications).flush({}, { status: 503, statusText: 'x' }); await p; expect(view.message()).toContain('Retry queued, but'); expect(view.loaded()).toBe(false); view.choose(id); await view.submit();
  });
  it.each([401, 403, 404, 503])('handles read failures without private provider details (%s)', async status => {
    const nav = vi.spyOn(router, 'navigate').mockResolvedValue(false); vi.spyOn(router, 'navigateByUrl').mockRejectedValue(new Error('synthetic navigation')); create('reconciliation'); http.expectOne(q => q.url === reconciliation).flush({ code: status === 401 ? 'SESSION_REQUIRED' : 'BAD', detail: 'private-provider' }, { status, statusText: 'x' }); await settle(); expect(view.loaded()).toBe(false); expect(view.message()).not.toContain('provider'); if (status === 403) expect(nav).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/reconciliation' } });
  });
  it('clears drafts and returns to notifications after fresh MFA is required', async () => {
    const nav = vi.spyOn(router, 'navigate').mockResolvedValue(false); await loaded(); select(); const p = view.submit(); (await post()).flush({ code: 'AUTHENTICATION_REQUIRED' }, { status: 403, statusText: 'x' }); await p; expect(view.jobs()).toEqual([]); expect(view.reasonForm.controls.reason.value).toBe(''); expect(nav).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/notifications' } });
  });
  it('keeps the loaded page and cursor when pagination fails', async () => {
    await loaded(); const p = view.more(); http.expectOne(q => q.url === notifications).flush({}, { status: 503, statusText: 'x' }); await p; expect(view.jobs()).toHaveLength(1); expect(view.cursor()).toBe(booking); expect(view.loaded()).toBe(true);
  });
  it('clears private state and ignores late reads on reused route changes', async () => {
    create(); const old = http.expectOne(q => q.url === notifications); route.next({ mode: 'reconciliation' }); old.flush(jobs); await settle(); expect(view.jobs()).toEqual([]); http.expectOne(q => q.url === reconciliation).flush(refunds); await settle(); expect(view.refunds()).toHaveLength(1); expect(view.notifications()).toBe(false);
    const p = view.load(); const stale = http.expectOne(q => q.url === reconciliation); route.next({ mode: 'notifications' }); stale.flush(refunds); await p; expect(view.refunds()).toEqual([]); http.expectOne(q => q.url === notifications).flush(jobs); await settle();
  });
  it('ignores late commands and refreshes after route changes', async () => {
    await loaded(); select(); const p = view.submit(); const old = await post(); route.next({ mode: 'reconciliation' }); old.flush({ jobId: id, auditId: booking, version: 1 }); await p; http.expectOne(q => q.url === reconciliation).flush(refunds); await settle(); expect(view.message()).not.toContain('Retry queued');
    route.next({ mode: 'notifications' }); http.expectOne(q => q.url === notifications).flush(jobs); await settle(); select(); const q = view.submit(); (await post()).flush({ jobId: id, auditId: booking, version: 1 }); await settle(); const refresh = http.expectOne(r => r.url === notifications); route.next({ mode: 'reconciliation' }); refresh.flush(jobs); await q; http.expectOne(r => r.url === reconciliation).flush(refunds); await settle(); expect(view.message()).not.toContain('Retry queued');
  });
  it('ignores read errors after session invalidation and results after destroy', async () => {
    create(); const r = http.expectOne(q => q.url === notifications); TestBed.inject(SessionAccess).invalidate(); r.flush({}, { status: 503, statusText: 'x' }); await settle(); expect(view.message()).toBe(''); await view.load(); await view.more(); view.choose(id); await view.submit(); await view.retrySame();
    fixture.destroy(); create('reconciliation'); const late = http.expectOne(q => q.url === reconciliation); fixture.destroy(); late.flush(refunds); await settle(); expect(view.refunds()).toEqual([]); await view.load();
  });
});
