import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { provideApi } from '../shared/generated';
import { CommandAccess } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { RescheduleBookingComponent } from './reschedule-booking.component';

const id = '30000000-0000-4000-8000-000000000001', path = '/api/v1/admin/bookings/' + id;
const now = Date.parse('2026-10-28T10:00:00Z');
const booking = { id, reference: 'B-synthetic', version: 7, state: 'CONFIRMED', startInstant: '2026-11-01T09:00:00Z', pendingExpired: false, snapshot: { service: { name: 'Synthetic consultation' } } };
const config = { calendarVersion: 5, policyVersion: 3, blackouts: [{ id, startInstant: '2026-11-02T08:00:00Z', endInstant: '2026-11-02T10:00:00Z', reason: 'Synthetic blackout' }, { id: 'outside', startInstant: '2026-11-02T11:00:00Z', endInstant: '2026-11-02T12:00:00Z', reason: 'Outside reservation' }] };
const session = { role: 'ADMIN', csrfToken: 'x'.repeat(43), idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() };
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };

describe('Administrator rescheduling', () => {
  let http: HttpTestingController, router: Router, routeId: string;
  let fixture: ReturnType<typeof TestBed.createComponent<RescheduleBookingComponent>>, view: RescheduleBookingComponent;
  const create = () => { fixture = TestBed.createComponent(RescheduleBookingComponent); view = fixture.componentInstance; fixture.detectChanges(); };
  async function loaded(body = booking, tag: string | null = '"7"') { create(); http.expectOne(path).flush(body, tag === null ? {} : { headers: { ETag: tag } }); await settle(); fixture.detectChanges(); }
  const fill = () => view.form.setValue({ day: '2026-11-02', time: '10:00', startInstant: '', reason: 'Synthetic rescheduling reason' });
  async function reviewed() { await loaded(); fill(); const p = view.review(); http.expectOne('/api/v1/admin/configuration').flush(config); await p; fixture.detectChanges(); }
  async function post() { await settle(); http.match('/api/v1/session/bootstrap').forEach(r => r.flush(session)); await settle(); return http.expectOne(path + '/reschedule'); }
  async function reject(code: string, status = 409) { const p = view.submit(); (await post()).flush({ code, detail: 'synthetic-provider-error' }, { status, statusText: 'x' }); await p; fixture.detectChanges(); }
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now); routeId = id;
    TestBed.configureTestingModule({ imports: [RescheduleBookingComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([]), { provide: ActivatedRoute, useFactory: () => ({ snapshot: { paramMap: convertToParamMap({ id: routeId }) } }) }] });
    http = TestBed.inject(HttpTestingController); router = TestBed.inject(Router);
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });

  it('sends reviewed booking/configuration versions and only the move, preserving snapshots and money', async () => {
    const nav = vi.spyOn(router, 'navigateByUrl'); await reviewed(); expect(fixture.nativeElement.textContent).toContain('recorded money stay unchanged');
    const p = view.submit(); const r = await post(); expect(r.request.headers.get('If-Match')).toBe('"7"');
    expect(JSON.parse(r.request.body)).toEqual({ policyVersion: 3, calendarVersion: 5, startInstant: '2026-11-02T09:00:00.000Z', reason: 'Synthetic rescheduling reason', override: { rules: [], reason: null, blackoutIds: [] } });
    r.flush({ bookingId: id, version: 8 }); await p; expect(nav).toHaveBeenCalledWith('/admin/bookings/' + id); expect(view.message()).toBe('Booking rescheduled.'); expect(view.form.controls.reason.value).toBe('');
  });
  it('requires a different unambiguous time, review and reason', async () => {
    await loaded(); await view.submit(); await view.review(); expect(view.message()).toContain('different');
    fill(); view.form.controls.day.setValue('2026-11-01'); await view.review(); expect(view.configuration()).toBeNull();
    view.form.patchValue({ day: '2027-03-28', time: '02:30' }); await view.review(); expect(view.instants()).toEqual([]);
    view.form.controls.day.setValue('2027-10-31'); fixture.detectChanges(); expect(fixture.nativeElement.querySelector('#move-occurrence')).not.toBeNull(); await view.review(); expect(view.configuration()).toBeNull();
    view.form.controls.startInstant.setValue(view.instants()[1]); view.form.controls.reason.setValue('short'); await view.review(); expect(view.configuration()).toBeNull();
    view.form.controls.reason.setValue('Synthetic correct reason'); const p = view.review(); http.expectOne('/api/v1/admin/configuration').flush(config); await p;
    view.form.controls.time.setValue('03:00'); expect(view.configuration()).toBeNull();
  });
  it.each([{ ...booking, state: 'CANCELLED' }, { ...booking, state: 'PENDING_CONFIRMATION', pendingExpired: true }])('forbids rescheduling terminal or expired bookings', async body => {
    await loaded(body); expect(view.eligible()).toBe(false); await view.review(); await view.submit(); expect(fixture.nativeElement.textContent).toContain('cannot be rescheduled');
  });
  it('accepts a live pending booking and falls back to its strong version when ETag is absent', async () => {
    await loaded({ ...booking, state: 'PENDING_CONFIRMATION' }, null); fill(); const p = view.review(); http.expectOne('/api/v1/admin/configuration').flush(config); await p;
    const send = view.submit(); const r = await post(); expect(r.request.headers.get('If-Match')).toBe('"7"'); r.flush({ bookingId: id, version: 8 }); await send;
  });
  it('requires explicit rule/blackout choices and a separate valid exception reason', async () => {
    await reviewed(); await reject('MINIMUM_NOTICE'); await reject('MINIMUM_NOTICE'); expect(view.violations()).toHaveLength(1); expect(view.selected.size).toBe(0);
    const event = (checked: boolean) => ({ target: { checked } } as unknown as Event);
    view.toggle(view.selected, 'NOTICE', event(true)); await view.submit(); expect(view.message()).toContain('exception reason');
    view.overrideReason.setValue('Synthetic override reason'); await reject('HARD_BLACKOUT'); view.toggle(view.selected, 'HARD_BLACKOUT', event(true)); fixture.detectChanges(); expect(view.blackouts().map(b => b.id)).toEqual([id]);
    view.toggle(view.blackoutIds, id, event(true)); const p = view.submit(); const r = await post(); expect(JSON.parse(r.request.body).override).toEqual({ rules: ['NOTICE', 'HARD_BLACKOUT'], reason: 'Synthetic override reason', blackoutIds: [id] });
    r.flush({ code: 'BLACKOUT_OVERRIDE_REQUIRED' }, { status: 409, statusText: 'x' }); await p; expect(view.message()).toContain('every intersecting');
    view.toggle(view.selected, 'NOTICE', event(false)); expect(view.selected.has('NOTICE')).toBe(false);
    await reject('OVERRIDE_UNUSED'); expect(view.violations()).toEqual([]); expect(view.selected.size).toBe(0);
  });
  it.each([['STALE_VERSION', 412], ['MISSING_VERSION', 428], ['POLICY_CHANGED', 409], ['CALENDAR_CHANGED', 409], ['HOLD_EXPIRED', 409], ['INVALID_TRANSITION', 409]])('forces a booking refresh and new review after %s', async (code, status) => {
    await reviewed(); await reject(code as string, status as number); expect(view.booking()).toBeNull(); expect(view.configuration()).toBeNull(); expect(view.message()).toContain('Refresh');
    const p = view.load(); http.expectOne(path).flush(booking); await p; expect(view.eligible()).toBe(true); expect(view.configuration()).toBeNull();
  });
  it.each([['SLOT_UNAVAILABLE', 'overlap cannot'], ['SOMETHING', 'could not be rescheduled']])('safely reports %s without offering an override', async (code, message) => {
    await reviewed(); await reject(code); expect(view.message()).toContain(message); expect(view.message()).not.toContain('provider'); expect(view.violations()).toEqual([]);
  });
  it('keeps an uncertain command immutable through throttling and cooldown', async () => {
    await reviewed(); const p = view.submit(); const r = await post(); const body = r.request.body, key = r.request.headers.get('Idempotency-Key');
    await view.submit(); await view.load(); await view.review(); await view.retry(); r.flush({}, { status: 503, statusText: 'x' }); await p;
    await view.submit(); await view.load(); await view.review(); expect(view.uncertain()).toBe(true);
    const retry = view.retry(); const limited = await post(); limited.flush({}, { status: 429, statusText: 'x', headers: { 'Retry-After': '2' } }); await retry;
    await view.retry(); expect(view.uncertain()).toBe(true); http.expectNone(path + '/reschedule'); expect(view.message()).toContain('still unknown');
    await vi.advanceTimersByTimeAsync(2001); const last = view.retry(); const success = await post(); expect(success.request.body).toBe(body); expect(success.request.headers.get('Idempotency-Key')).toBe(key); success.flush({ bookingId: id, version: 8 }); await last; await view.retry();
  });
  it('re-authenticates on a stale factor and returns to the same rescheduling route', async () => {
    const nav = vi.spyOn(router, 'navigate'); await reviewed(); await reject('AUTHENTICATION_REQUIRED', 403);
    expect(nav).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/bookings/' + id + '/reschedule' } }); expect(view.booking()).toBeNull(); expect(view.form.controls.reason.value).toBe('');
  });
  it('clears private state on session expiry', async () => {
    await reviewed(); await reject('SESSION_REQUIRED', 401); expect(view.booking()).toBeNull(); expect(TestBed.inject(SessionAccess).state()).toBeNull();
  });
  it('rejects malformed IDs without HTTP and handles read failures safely', async () => {
    routeId = 'bad'; create(); expect(view.message()).toContain('could not be found'); http.expectNone(path);
  });
  it('handles missing reads and session expiry without stale booking data', async () => {
    create(); http.expectOne(path).flush({}, { status: 404, statusText: 'x' }); await settle(); expect(view.message()).toContain('could not be loaded');
    const nav = vi.spyOn(router, 'navigateByUrl'); const p = view.load(); http.expectOne(path).flush({}, { status: 401, statusText: 'x' }); await p; expect(nav).toHaveBeenCalledWith('/admin/login'); expect(view.booking()).toBeNull();
  });
  it('ignores late reads after destruction and null read bodies', async () => {
    create(); http.expectOne(path).flush(null); await settle(); expect(view.booking()).toBeNull();
    const p = view.load(); const r = http.expectOne(path); fixture.destroy(); r.flush(booking); await p; expect(view.booking()).toBeNull();
  });
  it('ignores late review and mutation responses after invalidation', async () => {
    await loaded(); fill(); const p = view.review(); const r = http.expectOne('/api/v1/admin/configuration'); TestBed.inject(SessionAccess).invalidate(); r.flush(config); await p; expect(view.configuration()).toBeNull();
  });
  it('ignores mutation completion after destruction', async () => {
    await reviewed(); const p = view.submit(); const r = await post(); fixture.destroy(); r.flush({ bookingId: id, version: 8 }); await p; expect(view.booking()).toBeNull();
  });
  it('reports configuration failure, throttling and failed preparation safely', async () => {
    await loaded(); fill(); const p = view.review(); http.expectOne('/api/v1/admin/configuration').flush({}, { status: 503, statusText: 'x' }); await p; expect(view.message()).toContain('could not be loaded');
    const next = view.review(); http.expectOne('/api/v1/admin/configuration').flush(config); await next; await reject('THROTTLED', 429); expect(view.message()).toContain('wait');
    vi.spyOn(TestBed.inject(CommandAccess), 'prepare').mockImplementation(() => { throw new Error(); }); await view.submit(); expect(view.message()).toContain('could not be submitted');
  });
});
