import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideApi } from '../shared/generated';
import { CommandAccess } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { ManualBookingComponent } from './manual-booking.component';

const id = '10000000-0000-4000-8000-000000000001';
const now = Date.parse('2026-10-28T10:00:00Z');
const service = { id, version: 3, name: 'Synthetic consultation', durationMinutes: 60, priceMinor: 10000, currency: 'EUR', active: true };
const config = { observedAt: new Date(now).toISOString(), calendarVersion: 5, policyVersion: 7, weekly: {}, dateExceptions: [], policy: {}, services: [service, { ...service, id: 'archived', active: false }], blackouts: [
  { id, startInstant: '2026-11-02T08:00:00Z', endInstant: '2026-11-02T10:00:00Z', reason: 'Synthetic blackout' },
  { id: 'outside', startInstant: '2026-11-02T11:00:00Z', endInstant: '2026-11-02T12:00:00Z', reason: 'Outside reservation' },
] };
const context = { service, policy: { version: 7 }, notice: { version: 'local-v1', text: 'Synthetic notice text', synthetic: true }, serverNow: new Date(now).toISOString(), horizon: '2026-11-28', businessZone: 'Europe/Brussels', reservationMinutes: 120 };
const session = { role: 'ADMIN', csrfToken: 'x'.repeat(43), idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() };
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };

describe('Administrator manual booking creation', () => {
  let http: HttpTestingController, router: Router;
  let fixture: ReturnType<typeof TestBed.createComponent<ManualBookingComponent>>, view: ManualBookingComponent;
  function create() { fixture = TestBed.createComponent(ManualBookingComponent); view = fixture.componentInstance; fixture.detectChanges(); }
  async function loaded() { create(); http.expectOne('/api/v1/admin/configuration').flush(config); await settle(); fixture.detectChanges(); }
  function fill() { view.form.setValue({ serviceId: id, day: '2026-11-02', time: '10:00', startInstant: '', customerEmail: 'synthetic@example.test', firstName: 'Synthetic', lastName: 'Customer', telephone: '+32400123456', birthDate: '1990-01-01', birthTime: '12:00', birthplace: 'Synthetic city', message: '<b>private</b>', mode: 'pending', meetingUrl: '' }); }
  async function reviewed() { await loaded(); fill(); const p = view.review(); http.expectOne(r => r.url === '/api/v1/booking-context').flush(context); await p; fixture.detectChanges(); }
  async function post() { await settle(); http.match('/api/v1/session/bootstrap').forEach(r => r.flush(session)); await settle(); return http.expectOne('/api/v1/admin/bookings'); }
  async function reject(code: string, status = 409) { const p = view.submit(); (await post()).flush({ code, detail: 'synthetic-provider-error' }, { status, statusText: 'x' }); await p; fixture.detectChanges(); }
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now);
    TestBed.configureTestingModule({ imports: [ManualBookingComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([])] });
    http = TestBed.inject(HttpTestingController); router = TestBed.inject(Router);
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });

  it('reviews exact versions, shows terms and submits a pending booking without payment or overrides', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'); const nav = vi.spyOn(router, 'navigateByUrl'); await reviewed();
    expect(fixture.nativeElement.textContent).toContain('Synthetic notice text'); expect(fixture.nativeElement.textContent).toContain('synthetic');
    expect(fixture.nativeElement.querySelector('option[value=archived]')).toBeNull();
    const p = view.submit(); const r = await post(); const body = JSON.parse(r.request.body);
    expect(body).toMatchObject({ serviceId: id, serviceVersion: 3, policyVersion: 7, calendarVersion: 5, noticeVersion: 'local-v1', startInstant: '2026-11-02T09:00:00.000Z', displayZone: 'Europe/Brussels', meetingUrl: null, override: { rules: [], reason: null, blackoutIds: [] } });
    expect(body.intake.message).toBe('<b>private</b>'); expect(r.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    r.flush({ bookingId: id, version: 1 }); await p; expect(nav).toHaveBeenCalledWith('/admin/bookings/' + id);
    expect(view.form.controls.customerEmail.value).toBe(''); expect(view.context()).toBeNull(); expect(storage).not.toHaveBeenCalled();
  });
  it('requires a full review and valid intake, and disallows spring gaps and unselected autumn occurrences', async () => {
    await loaded(); await view.submit(); await view.review(); expect(view.message()).toContain('Complete');
    fill(); view.form.patchValue({ day: '2027-03-28', time: '02:30' }); await view.review(); expect(view.instants()).toEqual([]);
    view.form.patchValue({ day: '2027-10-31', time: '02:30' }); fixture.detectChanges(); expect(view.instants()).toHaveLength(2); expect(fixture.nativeElement.querySelector('#occurrence')).not.toBeNull();
    await view.review(); http.expectNone('/api/v1/booking-context');
    view.form.controls.startInstant.setValue(view.instants()[1]); const p = view.review(); http.expectOne(r => r.url === '/api/v1/booking-context').flush(context); await p; expect(view.context()).not.toBeNull();
  });
  it('requires and sends a Zoom link only for explicit manual confirmation', async () => {
    await loaded(); fill(); view.form.controls.mode.setValue('confirmed'); await view.review(); expect(view.message()).toContain('Zoom');
    view.form.controls.meetingUrl.setValue(' https://zoom.us/j/123456789 '); const p = view.review(); http.expectOne(r => r.url === '/api/v1/booking-context').flush(context); await p;
    const send = view.submit(); const r = await post(); expect(JSON.parse(r.request.body).meetingUrl).toBe('https://zoom.us/j/123456789'); r.flush({ bookingId: id, version: 1 }); await send;
  });
  it('rejects a changed policy during review and requires a fresh configuration', async () => {
    await loaded(); fill(); const p = view.review(); http.expectOne(r => r.url === '/api/v1/booking-context').flush({ ...context, policy: { version: 8 } }); await p;
    expect(view.context()).toBeNull(); expect(view.configuration()).toBeNull(); expect(view.message()).toContain('policy changed');
    const reload = view.load(); http.expectOne('/api/v1/admin/configuration').flush(config); await reload; expect(view.configuration()).not.toBeNull();
  });
  it('offers only server-reported violations, explicit selection and an audited reason', async () => {
    await reviewed(); await reject('MINIMUM_NOTICE'); expect(view.violations().map(v => v.rule)).toEqual(['NOTICE']); expect(view.selected.size).toBe(0);
    const event = (checked: boolean) => ({ target: { checked } } as unknown as Event);
    view.toggle(view.selected, 'NOTICE', event(true)); await view.submit(); expect(view.message()).toContain('reason');
    view.reason.setValue('Synthetic exception reason'); const p = view.submit(); const r = await post(); expect(JSON.parse(r.request.body).override).toEqual({ rules: ['NOTICE'], reason: 'Synthetic exception reason', blackoutIds: [] });
    r.flush({ code: 'HARD_BLACKOUT' }, { status: 409, statusText: 'x' }); await p;
    view.toggle(view.selected, 'HARD_BLACKOUT', event(true)); fixture.detectChanges(); expect(view.blackouts().map(b => b.id)).toEqual([id]);
    view.toggle(view.blackoutIds, id, event(true)); const next = view.submit(); const n = await post(); expect(JSON.parse(n.request.body).override.blackoutIds).toEqual([id]); n.flush({ code: 'BLACKOUT_OVERRIDE_REQUIRED' }, { status: 409, statusText: 'x' }); await next; expect(view.message()).toContain('every intersecting');
    view.toggle(view.selected, 'HARD_BLACKOUT', event(false)); expect(view.selected.has('HARD_BLACKOUT')).toBe(false);
    view.form.controls.customerEmail.setValue('changed@example.test'); expect(view.context()).toBeNull(); expect(view.violations()).toEqual([]); expect(view.reason.value).toBe('');
  });
  it('deduplicates repeated violations and resets exceptions that become unused', async () => {
    await reviewed(); await reject('DAILY_CAPACITY_REACHED'); await reject('DAILY_CAPACITY_REACHED'); expect(view.violations()).toHaveLength(1);
    await reject('OVERRIDE_UNUSED'); expect(view.violations()).toEqual([]); expect(view.message()).toContain('no longer needed');
  });
  it.each(['START_NOT_AVAILABLE', 'OUTSIDE_HORIZON', 'ACTIVE_LIMIT_REACHED', 'REQUEST_LIMIT_REACHED'])('allows explicit review of %s', async code => {
    await reviewed(); await reject(code); expect(view.violations()).toHaveLength(1); expect(view.selected.size).toBe(0);
  });
  it.each([['SLOT_UNAVAILABLE', 'overlap cannot'], ['MEETING_IN_USE', 'already in use'], ['SOMETHING', 'could not be created']])('safely reports %s without an override', async (code, message) => {
    await reviewed(); await reject(code); expect(view.violations()).toEqual([]); expect(view.message()).toContain(message); expect(view.message()).not.toContain('provider');
  });
  it.each([['STALE_VERSION', 412], ['MISSING_VERSION', 428], ['CALENDAR_CHANGED', 409], ['SERVICE_CHANGED', 409], ['POLICY_CHANGED', 409], ['NOTICE_CHANGED', 409]])('reloads reviewed configuration after %s', async (code, status) => {
    await reviewed(); await reject(code as string, status as number); expect(view.context()).toBeNull(); expect(view.configuration()).toBeNull(); expect(view.message()).toContain('Reload');
  });
  it('retries an uncertain response with the exact body and key, suppressing competing submissions', async () => {
    await reviewed(); const p = view.submit(); const r = await post(); const body = r.request.body, key = r.request.headers.get('Idempotency-Key');
    await view.submit(); await view.review(); await view.load(); await view.retry();
    r.flush({}, { status: 503, statusText: 'x' }); await p; expect(view.uncertain()).toBe(true);
    await view.submit(); await view.review(); await view.load(); fixture.detectChanges(); expect(fixture.nativeElement.querySelector('fieldset').disabled).toBe(true);
    const retry = view.retry(); const second = await post(); expect(second.request.body).toBe(body); expect(second.request.headers.get('Idempotency-Key')).toBe(key); second.flush({ bookingId: id, version: 1 }); await retry;
    await view.retry(); expect(view.message()).toBe('Booking created.');
  });
  it('clears private data on fresh-factor expiry and returns to creation after sign-in', async () => {
    const nav = vi.spyOn(router, 'navigate'); await reviewed(); await reject('AUTHENTICATION_REQUIRED', 403);
    expect(nav).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/bookings/new' } }); expect(view.form.controls.customerEmail.value).toBe('');
  });
  it('clears private data on session expiry', async () => {
    await reviewed(); await reject('SESSION_REQUIRED', 401);
    expect(TestBed.inject(SessionAccess).state()).toBeNull(); expect(view.configuration()).toBeNull(); expect(view.form.controls.customerEmail.value).toBe('');
  });
  it('shows safe read failures and tolerates destruction during a read', async () => {
    create(); await view.load(); http.expectOne('/api/v1/admin/configuration').flush({ detail: 'synthetic-provider-error' }, { status: 503, statusText: 'x' }); await settle(); expect(view.message()).toContain('could not be loaded');
    const reload = view.load(); const r = http.expectOne('/api/v1/admin/configuration'); fixture.destroy(); r.flush(config); await reload; expect(view.configuration()).toBeNull();
  });
  it('ignores a late review after invalidation', async () => {
    await loaded(); fill(); const p = view.review(); const r = http.expectOne(r => r.url === '/api/v1/booking-context'); TestBed.inject(SessionAccess).invalidate(); r.flush(context); await p; expect(view.context()).toBeNull();
  });
  it('ignores a late mutation after destruction', async () => {
    await reviewed(); const p = view.submit(); const r = await post(); fixture.destroy(); r.flush({ bookingId: id, version: 1 }); await p; expect(view.context()).toBeNull();
  });
  it('handles review failures, throttling and inability to prepare safely', async () => {
    await loaded(); fill(); const p = view.review(); http.expectOne(r => r.url === '/api/v1/booking-context').flush({}, { status: 404, statusText: 'x' }); await p; expect(view.message()).toContain('could not be loaded');
    const next = view.review(); http.expectOne(r => r.url === '/api/v1/booking-context').flush(context); await next;
    await reject('THROTTLED', 429); expect(view.message()).toContain('wait');
    vi.spyOn(TestBed.inject(CommandAccess), 'prepare').mockImplementation(() => { throw new Error(); }); await view.submit(); expect(view.message()).toContain('could not be submitted');
  });
});
