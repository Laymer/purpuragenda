import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideApi } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { ManageBookingComponent } from './manage-booking.component';

const now = Date.parse('2026-10-26T10:00:00Z');
const id = '20000000-0000-4000-8000-000000000001';
const session = { role: 'GUEST', csrfToken: 'x'.repeat(43), idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() };
const service = { id: '10000000-0000-0000-0000-000000000001', version: 1, name: 'Synthetic consultation', durationMinutes: 60, priceMinor: 10000, currency: 'EUR', active: true };
const status = (over: Record<string, unknown> = {}) => ({
  id, reference: 'B-synthetic', version: 3, state: 'CONFIRMED', pendingExpired: false, service, startInstant: '2026-10-30T09:00:00Z', sessionEndInstant: '2026-10-30T10:00:00Z', displayZone: 'Europe/Brussels',
  deliveryEmail: 'synthetic@example.test', pendingExpiresAt: null, customerChangesUntil: '2026-10-29T09:00:00Z', serverNow: new Date(now).toISOString(), meetingUrl: 'https://zoom.us/j/123456789',
  payment: { status: 'UNPAID', receivedMinor: 0, refundedMinor: 0, remainingMinor: 10000, refundDueByCurrency: {}, receivedByCurrency: {} }, allowedActions: ['CANCEL', 'RESCHEDULE'], ...over,
});
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };

describe('Customer booking management', () => {
  let http: HttpTestingController;
  let fixture: ReturnType<typeof TestBed.createComponent<ManageBookingComponent>>, view: ManageBookingComponent;
  const get = () => http.expectOne('/api/v1/guest/booking');
  const start = async (body: object = status(), etag: string | null = '"3"') => {
    fixture = TestBed.createComponent(ManageBookingComponent); view = fixture.componentInstance; fixture.detectChanges(); await settle();
    http.expectOne('/api/v1/session/bootstrap').flush(session); await settle();
    get().flush(body, etag === null ? {} : { headers: { ETag: etag } }); await settle(); fixture.detectChanges();
  };
  async function cancelRequest(): Promise<TestRequest> { await settle(); return http.expectOne('/api/v1/bookings/' + id + '/cancel'); }
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now);
    TestBed.configureTestingModule({ imports: [ManageBookingComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });

  it('shows the bound booking with zone and offset, hold and cutoff times, payment and meeting link as text', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'); await start(status({ state: 'PENDING_CONFIRMATION', pendingExpiresAt: '2026-10-27T10:00:00Z', meetingUrl: null }));
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('B-synthetic'); expect(text).toContain('Europe/Brussels'); expect(text).toContain('UTC+01:00'); expect(text).toContain('Hold expires'); expect(text).toContain('Customer changes close');
    expect(text).toContain('Payment: unpaid'); expect(fixture.nativeElement.querySelector('a[target="_blank"]')).toBeNull(); expect(storage).not.toHaveBeenCalled();
  });
  it('links to the meeting when present and hides cancellation when it is not allowed', async () => {
    await start(status({ allowedActions: [] })); expect(fixture.nativeElement.querySelector('a[target="_blank"]').getAttribute('rel')).toContain('noopener');
    expect(view.canCancel()).toBe(false); expect(fixture.nativeElement.textContent).not.toContain('Cancel this booking');
  });
  it.each([[404, 'could not find'], [401, 'expired'], [503, 'could not be loaded']])('shows a safe message and a way back for a %s read', async (code, text) => {
    fixture = TestBed.createComponent(ManageBookingComponent); view = fixture.componentInstance; fixture.detectChanges(); await settle();
    http.expectOne('/api/v1/session/bootstrap').flush(session); await settle(); get().flush({ detail: 'synthetic-provider-detail' }, { status: code, statusText: 'x' }); await settle(); fixture.detectChanges();
    expect(view.message()).toContain(text); expect(view.message()).not.toContain('provider'); expect(view.booking()).toBeNull();
    expect(fixture.nativeElement.querySelector('a[href="/manage"]')).not.toBeNull();
  });
  it('requires an explicit two-step confirmation that explains the cutoff before anything is sent', async () => {
    await start(); const button = [...fixture.nativeElement.querySelectorAll('button')].find((b: HTMLElement) => b.textContent!.includes('Cancel this booking')) as HTMLElement;
    button.click(); await settle(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('You can cancel until'); expect(fixture.nativeElement.textContent).toContain('UTC+01:00'); http.expectNone('/api/v1/bookings/' + id + '/cancel');
    view.keep(); fixture.detectChanges(); expect(view.confirming()).toBe(false); expect(fixture.nativeElement.textContent).toContain('Cancel this booking');
  });
  it('cancels with the ETag as If-Match and a UUID Idempotency-Key, then reloads the status', async () => {
    await start(); view.askCancel(); const pending = view.cancel(); const request = await cancelRequest();
    expect(request.request.headers.get('If-Match')).toBe('"3"'); expect(request.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/); expect(request.request.headers.get('X-CSRF-Token')).toBe(session.csrfToken); expect(request.request.body).toBe('{}');
    request.flush({ bookingId: id, version: 4 }); await settle(); get().flush(status({ state: 'CANCELLED', version: 4, allowedActions: [] }), { headers: { ETag: '"4"' } }); await pending; await settle(); fixture.detectChanges();
    expect(view.message()).toContain('received'); expect(view.booking()?.state).toBe('CANCELLED'); expect(view.canCancel()).toBe(false); expect(view.confirming()).toBe(false);
  });
  it('falls back to the version when the ETag is weak, and does nothing while busy or without a booking', async () => {
    await start(status({ version: 9 }), 'W/"9"'); view.askCancel(); const pending = view.cancel(); await view.cancel(); const request = await cancelRequest();
    expect(request.request.headers.get('If-Match')).toBe('"9"'); request.flush({}, { status: 400, statusText: 'Bad' }); await pending; await settle();
    expect(view.message()).toContain('could not be cancelled'); expect(view.confirming()).toBe(false);
  });
  it('retries an uncertain cancellation with the same idempotency key only on an explicit click', async () => {
    await start(); view.askCancel(); const first = view.cancel(); const request = await cancelRequest(); const key = request.request.headers.get('Idempotency-Key');
    request.error(new ProgressEvent('error')); await first; await settle(); fixture.detectChanges();
    expect(view.uncertain()).toBe(true); expect(fixture.nativeElement.textContent).toContain('may have succeeded'); expect(fixture.nativeElement.textContent).not.toContain('Yes, cancel this booking');
    const retry = view.retry(); const again = await cancelRequest(); expect(again.request.headers.get('Idempotency-Key')).toBe(key);
    again.flush({ bookingId: id, version: 4 }); await settle(); get().flush(status({ state: 'CANCELLED', allowedActions: [] })); await retry; await settle();
    expect(view.uncertain()).toBe(false); await view.retry(); http.expectNone('/api/v1/bookings/' + id + '/cancel');
  });
  it.each([[412, 'changed'], [428, 'changed'], [409, 'no longer possible']])('reloads the latest booking after a %s result', async (code, text) => {
    await start(); view.askCancel(); const pending = view.cancel(); (await cancelRequest()).flush({ code: 'X' }, { status: code, statusText: 'x' }); await settle();
    get().flush(status({ version: 5 }), { headers: { ETag: '"5"' } }); await pending; await settle();
    expect(view.message()).toContain(text); expect(view.booking()?.version).toBe(5); expect(view.confirming()).toBe(false);
  });
  it('maps 403, 404, 429, 401 and server failures to safe messages without provider text', async () => {
    await start(); view.askCancel(); let pending = view.cancel(); (await cancelRequest()).flush({ detail: 'synthetic-provider-detail' }, { status: 403, statusText: 'No' }); await settle();
    get().flush(status()); await pending; await settle(); expect(view.message()).toContain('can no longer be changed'); expect(view.message()).not.toContain('provider');
    view.askCancel(); pending = view.cancel(); (await cancelRequest()).flush({}, { status: 429, statusText: 'Many', headers: { 'Retry-After': '5' } }); await pending; await settle(); expect(view.message()).toContain('wait');
    await vi.advanceTimersByTimeAsync(6000); view.askCancel(); pending = view.cancel(); (await cancelRequest()).flush({}, { status: 404, statusText: 'No' }); await pending; await settle();
    expect(view.booking()).toBeNull(); expect(view.lost()).toBe(true); expect(view.message()).toContain('could not find');
  });
  it('clears private state and offers a new link when the session ends or is invalidated', async () => {
    await start(); view.askCancel(); TestBed.inject(SessionAccess).invalidate(); fixture.detectChanges();
    expect(view.booking()).toBeNull(); expect(view.confirming()).toBe(false); expect(view.lost()).toBe(true); expect(view.message()).toContain('expired');
  });
  it('reports an expired session during cancellation', async () => {
    await start(); view.askCancel(); const pending = view.cancel(); (await cancelRequest()).flush({ code: 'SESSION_REQUIRED' }, { status: 401, statusText: 'No' }); await pending; await settle();
    expect(view.lost()).toBe(true); expect(view.booking()).toBeNull();
  });
  it('ignores a late booking after destruction and refuses to cancel when not allowed', async () => {
    fixture = TestBed.createComponent(ManageBookingComponent); view = fixture.componentInstance; fixture.detectChanges(); await settle();
    http.expectOne('/api/v1/session/bootstrap').flush(session); await settle(); const request = get(); fixture.destroy(); request.flush(status()); await settle();
    expect(view.booking()).toBeNull(); await view.cancel(); await view.retry();
  });
});
