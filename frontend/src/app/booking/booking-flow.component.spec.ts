import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BookingReviewContext, CustomerBookingStatus, BookingServiceSnapshotCurrencyEnum, CustomerBookingStatusStateEnum, CustomerBookingStatusAllowedActionsEnum, BookingPaymentSummaryStatusEnum, ServiceCurrencyEnum, provideApi } from '../shared/generated';
import { ConsultationSelection } from '../public/selection-model';
import { FrozenCommand } from '../shared/access/command-access';
import { AccessFailure } from '../shared/access/access-failure';
import { SessionAccess } from '../shared/access/session-access';
import { BookingFlowComponent } from './booking-flow.component';

const now = Date.parse('2026-10-26T10:00:00Z');
const choice: ConsultationSelection = { service: { id: '10000000-0000-0000-0000-000000000001', version: 1, name: 'Synthetic consultation', durationMinutes: 60, priceMinor: 10000, currency: ServiceCurrencyEnum.Eur, active: true },
  policyVersion: 1, displayZone: 'Europe/Brussels', slot: { businessDate: '2026-10-30', startInstant: '2026-10-30T09:00:00Z', sessionEndInstant: '2026-10-30T10:00:00Z', reservedEndInstant: '2026-10-30T11:00:00Z', displayLocalTime: '2026-10-30T10:00:00', offset: '+01:00' } };
const intake = { firstName: 'Synthetic', lastName: 'Customer', telephone: '+32400123456', birthDate: '1990-01-01', birthTime: '12:00', birthplace: 'Synthetic city', message: 'Private synthetic intake' };
const terms: BookingReviewContext = { service: choice.service, policy: { version: 1, gridMinutes: 30, dailyCapacity: 3, minimumNoticeSeconds: 86400, pendingLifetimeSeconds: 86400, expiryBufferSeconds: 86400, customerCutoffSeconds: 86400 }, notice: { version: 'local-v1', text: '<script>synthetic plain text</script>', synthetic: true }, serverNow: new Date(now).toISOString(), horizon: '2026-11-30', businessZone: 'Europe/Brussels', reservationMinutes: 120 };
const booking: CustomerBookingStatus = { id: '20000000-0000-0000-0000-000000000001', reference: 'B-synthetic', version: 1, state: CustomerBookingStatusStateEnum.PendingConfirmation, pendingExpired: false, service: { ...choice.service, currency: BookingServiceSnapshotCurrencyEnum.Eur }, startInstant: choice.slot.startInstant, sessionEndInstant: choice.slot.sessionEndInstant, displayZone: choice.displayZone, deliveryEmail: 'synthetic@example.test', pendingExpiresAt: '2026-10-27T10:00:00Z', customerChangesUntil: '2026-10-29T09:00:00Z', serverNow: new Date(now).toISOString(), meetingUrl: null,
  payment: { status: BookingPaymentSummaryStatusEnum.Unpaid, receivedMinor: 0, refundedMinor: 0, remainingMinor: 10000, refundDueByCurrency: {}, receivedByCurrency: {} }, allowedActions: [CustomerBookingStatusAllowedActionsEnum.Cancel, CustomerBookingStatusAllowedActionsEnum.Reschedule] };
const session = (role = 'GUEST') => ({ role, csrfToken: 'synthetic-csrf', idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() });
const settle = async () => { for (let i = 0; i < 16; i++) await Promise.resolve(); };

describe('Real typed booking flow and private state', () => {
  let http: HttpTestingController, flow: BookingFlowComponent, sessions: SessionAccess;
  let fixture: ReturnType<typeof TestBed.createComponent<BookingFlowComponent>>;
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now);
    TestBed.configureTestingModule({ imports: [BookingFlowComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1')] });
    http = TestBed.inject(HttpTestingController); sessions = TestBed.inject(SessionAccess);
    fixture = TestBed.createComponent(BookingFlowComponent); flow = fixture.componentInstance; fixture.detectChanges();
    http.expectOne('/api/v1/services').flush([]); flow.choose(choice);
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });
  async function enter(role = 'GUEST') {
    const start = flow.begin(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(session(role)); await start;
  }
  async function review() {
    await enter(); flow.intake.setValue(intake); const pending = flow.reviewRequest();
    http.expectOne(request => request.url === '/api/v1/booking-context').flush(terms); await pending;
  }
  async function submitted() {
    await review(); flow.agreed.setValue(true); const pending = flow.submit(); await settle();
    const request = http.expectOne('/api/v1/bookings'); request.flush({ bookingId: booking.id, version: 1 }); await settle();
    http.expectOne('/api/v1/bookings/' + booking.id).flush(booking); await pending;
  }
  it('does not advance without a time, coalesces busy actions and refuses administrator customer creation', async () => {
    flow.choose(null); await flow.begin(); http.expectNone('/api/v1/session/bootstrap');
    flow.choose(choice); const pending = flow.begin(); await flow.begin(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(session('ADMIN')); await pending;
    expect(flow.phase()).toBe('choose'); expect(flow.message()).toContain('administrator');
  });
  it('requests a real email with CSRF, enforces cooldown, and refreshes a rotated browser session explicitly', async () => {
    await enter('ANONYMOUS'); expect(flow.phase()).toBe('email');
    await flow.requestEmail(); http.expectNone('/api/v1/guest/create-proofs');
    flow.email.setValue('synthetic@example.test'); const send = flow.requestEmail(); await flow.requestEmail(); await settle();
    const request = http.expectOne('/api/v1/guest/create-proofs'); expect(request.request.headers.has('X-CSRF-Token')).toBe(true); request.flush({ accepted: true }); await send;
    expect(flow.retrySeconds()).toBe(60); await flow.requestEmail(); http.expectNone('/api/v1/guest/create-proofs');
    await vi.advanceTimersByTimeAsync(60000); expect(flow.retrySeconds()).toBe(0);
    const refresh = flow.begin(true); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(session()); await refresh;
    expect(flow.phase()).toBe('intake'); expect(flow.email.value).toBe('');
  });
  it('preserves the explicit verification step for an unverified refresh and keeps provider errors out of the page', async () => {
    await enter('ANONYMOUS'); const refresh = flow.begin(true); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(session('ANONYMOUS')); await refresh;
    expect(flow.message()).toContain('Open the email');
    flow.email.setValue('synthetic@example.test'); const send = flow.requestEmail(); await settle();
    http.expectOne('/api/v1/guest/create-proofs').flush({ detail: 'synthetic-provider-detail' }, { status: 503, statusText: 'Unavailable' }); await send;
    expect(flow.message()).toContain('may already'); expect(flow.message()).not.toContain('synthetic-provider-detail');
  });
  it('validates intake before review and renders the notice as text with an explicit agreement', async () => {
    await enter(); await flow.reviewRequest(); http.expectNone(request => request.url === '/api/v1/booking-context');
    expect(flow.intake.controls.birthTime.touched).toBe(true); flow.intake.setValue(intake);
    const pending = flow.reviewRequest(); const request = http.expectOne(request => request.url === '/api/v1/booking-context');
    expect(request.request.params.get('serviceId')).toBe(choice.service.id); request.flush(terms); await pending; fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('script')).toBeNull(); expect(fixture.nativeElement.textContent).toContain(terms.notice.text);
    await flow.submit(); http.expectNone('/api/v1/bookings'); flow.edit(); expect(flow.phase()).toBe('intake'); expect(flow.agreed.value).toBe(false);
  });
  it.each(['service', 'policy'])('requires a new selection when the reviewed %s version changed', async changed => {
    await enter(); flow.intake.setValue(intake); const pending = flow.reviewRequest();
    http.expectOne(request => request.url === '/api/v1/booking-context').flush({ ...terms, [changed]: { ...terms[changed as 'service' | 'policy'], version: 2 } }); await pending;
    expect(flow.phase()).toBe('choose'); expect(flow.selection()).toBeNull(); expect(flow.review()).toBeNull();
  });
  it('ignores late review data after session invalidation and clears all private fields', async () => {
    await enter(); flow.intake.setValue(intake); const pending = flow.reviewRequest();
    const request = http.expectOne(request => request.url === '/api/v1/booking-context'); sessions.invalidate(); request.flush(terms); await pending;
    expect(flow.review()).toBeNull(); expect(flow.phase()).toBe('choose'); expect(flow.intake.controls.message.value).toBe('');
  });
  it('submits exact reviewed versions once, clears intake, and shows independent payment and pending state', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'); await review(); flow.agreed.setValue(true);
    const pending = flow.submit(); await flow.submit(); await settle(); const request = http.expectOne('/api/v1/bookings');
    const body = JSON.parse(request.request.body); expect(body.noticeVersion).toBe('local-v1'); expect(body.policyVersion).toBe(1); expect(body.serviceVersion).toBe(1);
    expect(body.intake.message === intake.message).toBe(true); expect(request.request.headers.has('Idempotency-Key')).toBe(true); expect(request.request.headers.has('X-CSRF-Token')).toBe(true);
    request.flush({ bookingId: booking.id, version: 1 }); await settle(); http.expectOne('/api/v1/bookings/' + booking.id).flush(booking); await pending; fixture.detectChanges();
    expect(flow.phase()).toBe('status'); expect(flow.intake.controls.message.value).toBe(''); expect(fixture.nativeElement.textContent).toContain('unpaid');
    expect(fixture.nativeElement.textContent).toContain('10:00'); expect(fixture.nativeElement.textContent).toContain('Customer changes close');
    expect(fixture.nativeElement.textContent).not.toContain(intake.message); expect(storage).not.toHaveBeenCalled(); storage.mockRestore();
  });
  it('keeps exact serialized body and key after an uncertain response and never retries automatically', async () => {
    await review(); flow.agreed.setValue(true); const first = flow.submit(); await settle(); const request = http.expectOne('/api/v1/bookings');
    const body = request.request.body, key = request.request.headers.get('Idempotency-Key'); request.flush({}, { status: 503, statusText: 'Unavailable' }); await first;
    expect(flow.phase()).toBe('uncertain'); await flow.submit(); http.expectNone('/api/v1/bookings');
    flow.intake.controls.message.setValue('Changed local form'); const retry = flow.retry(); await settle(); const repeated = http.expectOne('/api/v1/bookings');
    expect(repeated.request.body === body).toBe(true); expect(repeated.request.headers.get('Idempotency-Key') === key).toBe(true);
    repeated.flush({ bookingId: booking.id, version: 1 }); await settle(); http.expectOne('/api/v1/bookings/' + booking.id).flush(booking); await retry;
    expect(flow.phase()).toBe('status');
  });
  it('honours retry deadlines and retains the same mutation intent after throttling', async () => {
    await review(); flow.agreed.setValue(true); const first = flow.submit(); await settle();
    http.expectOne('/api/v1/bookings').flush({}, { status: 429, statusText: 'Too many', headers: { 'Retry-After': '2' } }); await first;
    await flow.retry(); http.expectNone('/api/v1/bookings'); await vi.advanceTimersByTimeAsync(2000);
    const retry = flow.retry(); await settle(); http.expectOne('/api/v1/bookings').flush({}, { status: 409, statusText: 'Conflict' }); await retry;
    expect(flow.phase()).toBe('choose'); expect(flow.selection()).toBeNull(); expect(flow.message()).toContain('current time');
  });
  it('requests a fresh creation proof when the current guest grant cannot create another booking', async () => {
    await review(); flow.agreed.setValue(true); const pending = flow.submit(); await settle();
    http.expectOne('/api/v1/bookings').flush({}, { status: 403, statusText: 'Forbidden' }); await pending;
    expect(flow.phase()).toBe('email'); expect(flow.selection()).toEqual(choice);
  });
  it('clears status and frozen intake when authority expires; an old request cannot be retried', async () => {
    await submitted(); sessions.invalidate(); expect(flow.status()).toBeNull(); expect(flow.phase()).toBe('choose');
    await flow.retry(); await flow.loadStatus(); http.expectNone('/api/v1/bookings/' + booking.id);
  });
  it('retains the committed receipt if a status read is unavailable and retries only the read', async () => {
    await review(); flow.agreed.setValue(true); const pending = flow.submit(); await settle();
    http.expectOne('/api/v1/bookings').flush({ bookingId: booking.id, version: 1 }); await settle();
    http.expectOne('/api/v1/bookings/' + booking.id).flush({}, { status: 503, statusText: 'Unavailable' }); await pending;
    expect(flow.phase()).toBe('status'); expect(flow.message()).toContain('recorded');
    const refresh = flow.loadStatus(); await settle(); http.expectOne('/api/v1/bookings/' + booking.id).flush({ ...booking, state: 'CONFIRMED', pendingExpiresAt: null, meetingUrl: 'https://meeting.example.invalid/synthetic' }); await refresh; fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a[target="_blank"]').getAttribute('rel')).toBe('noopener noreferrer'); http.expectNone('/api/v1/bookings');
  });
  it('moves keyboard focus to each new step without stealing focus during form edits', async () => {
    await enter(); fixture.detectChanges();
    expect(document.activeElement?.textContent).toBe('Your consultation details');
    const firstName = fixture.nativeElement.querySelector('[formControlName="firstName"]') as HTMLInputElement;
    firstName.focus(); firstName.value = 'Synthetic'; firstName.dispatchEvent(new Event('input')); fixture.detectChanges();
    expect(document.activeElement).toBe(firstName);
    flow.intake.setValue(intake); const pending = flow.reviewRequest();
    http.expectOne(request => request.url === '/api/v1/booking-context').flush(terms); await pending; fixture.detectChanges();
    expect(document.activeElement?.textContent).toBe('Review your request');
    flow.edit(); fixture.detectChanges(); expect(document.activeElement?.textContent).toBe('Your consultation details');
  });

  it('stays at selection when the user clears the time while session bootstrap is pending', async () => {
    const pending = flow.begin(); await settle(); flow.choose(null);
    http.expectOne('/api/v1/session/bootstrap').flush(session()); await pending;
    expect(flow.phase()).toBe('choose'); expect(flow.selection()).toBeNull();
  });
  it.each([false, true])('does not restore status or messages after invalidation at the read handoff (failure=%s)', async fail => {
    await submitted();
    vi.spyOn(sessions, 'perform').mockImplementationOnce(async () => {
      sessions.invalidate(); if (fail) throw new AccessFailure('UNCERTAIN'); return booking as never;
    });
    await flow.loadStatus(); expect(flow.status()).toBeNull(); expect(flow.phase()).toBe('choose');
    expect(flow.message()).toContain('session changed');
  });
  it.each([false, true])('does not restore a receipt or retry phase after invalidation at command handoff (failure=%s)', async fail => {
    await review(); flow.agreed.setValue(true);
    vi.spyOn(FrozenCommand.prototype, 'attempt').mockImplementationOnce(async () => {
      sessions.invalidate(); if (fail) throw new AccessFailure('UNCERTAIN'); return { bookingId: booking.id, version: 1 };
    });
    await flow.submit(); expect(flow.phase()).toBe('choose'); expect(flow.status()).toBeNull();
    expect(flow.message()).toContain('session changed'); http.expectNone('/api/v1/bookings/' + booking.id);
  });

  it('accepts an internationalized domain and leaves canonicalization to the backend', async () => {
    await enter('ANONYMOUS'); fixture.detectChanges(); flow.email.setValue('synthetic@bücher.example');
    expect(flow.email.valid).toBe(true);
    const input = fixture.nativeElement.querySelector('#booking-email') as HTMLInputElement;
    fixture.detectChanges(); expect(input.validity.typeMismatch).toBe(false);
    const pending = flow.requestEmail(); await settle();
    const request = http.expectOne('/api/v1/guest/create-proofs');
    expect(request.request.body.email).toBe('synthetic@bücher.example'); request.flush({ accepted: true }); await pending;
  });

  it('does not enter intake when the bootstrap context is invalidated at its consumer handoff', async () => {
    const bootstrap = sessions.bootstrap.bind(sessions);
    vi.spyOn(sessions, 'bootstrap').mockImplementationOnce(async () => {
      const context = await bootstrap(); sessions.invalidate(); return context;
    });
    await enter(); expect(flow.phase()).toBe('choose'); expect(flow.message()).toContain('session changed');
  });
  it.each([
    ['2026-10-25T00:30:00Z', '+02:00'], ['2026-10-25T01:30:00Z', '+01:00'],
  ])('keeps the repeated Brussels hour unambiguous in review and status formatting (%s)', async (instant, offset) => {
    flow.choose({ ...choice, slot: { ...choice.slot, startInstant: instant, offset } });
    await review(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('UTC' + offset);
    expect(flow.inZone(instant, 'Europe/Brussels')).toContain('UTC' + offset);
  });

  it.each([404, 503, 429])('recovers an unavailable review service without trapping intake (status=%s)', async status => {
    await enter(); flow.intake.setValue(intake); const pending = flow.reviewRequest();
    http.expectOne(request => request.url === '/api/v1/booking-context').flush({}, { status, statusText: 'Synthetic failure' }); await pending;
    expect(flow.phase()).toBe(status === 404 ? 'choose' : 'intake');
    expect(flow.selection()).toEqual(status === 404 ? null : choice);
    expect(flow.intake.getRawValue()).toEqual(intake);
  });

  it('submits the actual email form without a native page reload', async () => {
    await enter('ANONYMOUS'); flow.email.setValue('synthetic@example.test'); fixture.detectChanges();
    const event = new Event('submit', { bubbles: true, cancelable: true });
    fixture.nativeElement.querySelector('form').dispatchEvent(event); await settle();
    expect(event.defaultPrevented).toBe(true);
    http.expectOne('/api/v1/guest/create-proofs').flush({ accepted: true }); await settle();
    expect(flow.message()).toContain('Check your inbox');
  });

});
