import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { provideApi } from '../shared/generated';
import { CommandAccess } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { BookingFinanceComponent } from './booking-finance.component';

const id = '30000000-0000-4000-8000-000000000001', receipt = '40000000-0000-4000-8000-000000000001', path = '/api/v1/admin/bookings/' + id;
const now = Date.parse('2026-10-28T10:00:00Z');
const detail = { id, reference: 'B-synthetic', state: 'CANCELLED', payment: { status: 'REFUND_DUE', receivedMinor: 10000, refundedMinor: 2000, remainingMinor: 0, receivedByCurrency: { EUR: 10000, JPY: 1000 }, refundDueByCurrency: { EUR: 8000, KWD: 1234 }, entries: [
  { id: receipt, kind: 'RECEIPT', amountMinor: 10000, currency: 'EUR', method: 'Synthetic transfer', reference: '<b>synthetic-ref</b>', verifiedAt: '2026-10-27T10:00:00Z', receiptId: null },
  { id: 'refund', kind: 'REFUND', amountMinor: 2000, currency: 'EUR', method: 'Synthetic transfer', reference: 'synthetic-refund', verifiedAt: '2026-10-27T11:00:00Z', receiptId: receipt },
], refundObligations: [{ receiptId: receipt, dueMinor: 10000, reason: 'Synthetic refund requirement' }] } };
const session = { role: 'ADMIN', csrfToken: 'x'.repeat(43), idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() };
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };

describe('Administrator external money', () => {
  let http: HttpTestingController, router: Router, routeId: string;
  let fixture: ReturnType<typeof TestBed.createComponent<BookingFinanceComponent>>, view: BookingFinanceComponent;
  const create = () => { fixture = TestBed.createComponent(BookingFinanceComponent); view = fixture.componentInstance; fixture.detectChanges(); };
  async function loaded(body: Record<string, unknown> = detail) { create(); http.expectOne(path).flush(body); await settle(); fixture.detectChanges(); }
  const fill = () => view.form.patchValue({ amount: '0.29', currency: ' eur ', method: 'Synthetic transfer', reference: 'synthetic-new', day: '2026-10-27', time: '10:00' });
  async function post(action = 'receipts') { await settle(); http.match('/api/v1/session/bootstrap').forEach(r => r.flush(session)); await settle(); return http.expectOne(path + '/' + action); }
  async function reject(code: string, status = 409, action = 'receipts') { const p = view.submit(); (await post(action)).flush({ code, detail: 'synthetic-provider-error' }, { status, statusText: 'x' }); await p; }
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now); routeId = id;
    TestBed.configureTestingModule({ imports: [BookingFinanceComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([]), { provide: ActivatedRoute, useFactory: () => ({ snapshot: { paramMap: convertToParamMap({ id: routeId }) } }) }] });
    http = TestBed.inject(HttpTestingController); router = TestBed.inject(Router);
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });

  it('shows immutable entries and outstanding obligations in their original currencies as text only', async () => {
    await loaded(); const text = fixture.nativeElement.textContent;
    expect(text).toContain('EUR'); expect(text).toContain('JPY'); expect(text).toContain('KWD'); expect(text).toContain('80.00'); expect(text).toContain('1.234'); expect(text).toContain('Original receipt');
    expect(text).toContain('<b>synthetic-ref</b>'); expect(fixture.nativeElement.querySelector('dd b')).toBeNull(); expect(text).toContain('full original receipt amount'); expect(text).toContain('CANCELLED');
  });
  it('records actual money on a terminal booking without an If-Match or implied approval', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'); await loaded(); fill(); const p = view.submit(); const r = await post();
    expect(JSON.parse(r.request.body)).toEqual({ amountMinor: 29, currency: 'EUR', method: 'Synthetic transfer', reference: 'synthetic-new', verifiedAt: '2026-10-27T09:00:00.000Z' });
    expect(r.request.headers.has('If-Match')).toBe(false); expect(r.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    r.flush({ paymentEntryId: receipt }); await settle(); http.expectOne(path).flush(detail); await p;
    expect(view.message()).toContain('Entry recorded'); expect(view.booking()?.state).toBe('CANCELLED'); expect(view.form.controls.reference.value).toBe(''); expect(storage).not.toHaveBeenCalled();
  });
  it('requires exact amounts, reference and unambiguous verified time', async () => {
    await loaded(); await view.submit(); expect(view.message()).toContain('exact positive'); fill(); view.form.controls.amount.setValue('1.001'); await view.submit(); expect(view.message()).toContain('exact positive');
    fill(); view.form.controls.reference.setValue(''); await view.submit(); expect(view.message()).toContain('reference');
    fill(); view.form.patchValue({ day: '2026-10-25', time: '02:30' }); fixture.detectChanges(); expect(fixture.nativeElement.querySelector('#finance-occurrence')).not.toBeNull(); await view.submit(); expect(view.message()).toContain('unambiguous');
    view.form.controls.verifiedAt.setValue(view.instants()[1]); const p = view.submit(); const r = await post(); expect(JSON.parse(r.request.body).verifiedAt).toBe('2026-10-25T01:30:00.000Z'); r.flush({}, { status: 400, statusText: 'x' }); await p;
  });
  it('requires an original receipt and refunds only in its currency', async () => {
    await loaded(); fill(); view.form.patchValue({ action: 'refunds', currency: 'JPY' }); fixture.detectChanges(); expect(view.currency()).toBe(''); await view.submit(); expect(view.message()).toContain('original receipt');
    view.form.controls.receiptId.setValue(receipt); const p = view.submit(); const r = await post('refunds'); expect(JSON.parse(r.request.body)).toMatchObject({ receiptId: receipt, amountMinor: 29, currency: 'EUR' }); expect(r.request.headers.has('If-Match')).toBe(false);
    r.flush({ code: 'REFUND_EXCEEDS_RECEIPT' }, { status: 409, statusText: 'x' }); await p; expect(view.message()).toContain('money remaining');
  });
  it('records a refund requirement independently with its full original receipt and reason', async () => {
    await loaded(); view.form.patchValue({ action: 'refund-requirements', receiptId: receipt }); fixture.detectChanges(); await view.submit(); expect(view.message()).toContain('refund reason');
    view.form.controls.reason.setValue('Synthetic refund reason'); const p = view.submit(); const r = await post('refund-requirements'); expect(JSON.parse(r.request.body)).toEqual({ receiptId: receipt, reason: 'Synthetic refund reason' }); expect(r.request.headers.has('If-Match')).toBe(false);
    r.flush({ receiptId: receipt }); await settle(); http.expectOne(path).flush(detail); await p; expect(view.message()).toContain('Entry recorded');
  });
  it('preserves the original command across uncertainty, throttling and cooldown', async () => {
    await loaded(); fill(); const p = view.submit(); const r = await post(); const body = r.request.body, key = r.request.headers.get('Idempotency-Key');
    await view.submit(); await view.load(); await view.retry(); r.flush({}, { status: 503, statusText: 'x' }); await p; expect(view.uncertain()).toBe(true);
    await view.submit(); await view.load(); const retry = view.retry(); const limited = await post(); limited.flush({}, { status: 429, statusText: 'x', headers: { 'Retry-After': '2' } }); await retry;
    await view.retry(); expect(view.uncertain()).toBe(true); expect(view.message()).toContain('still unknown'); http.expectNone(path + '/receipts');
    await vi.advanceTimersByTimeAsync(2001); const last = view.retry(); const success = await post(); expect(success.request.body).toBe(body); expect(success.request.headers.get('Idempotency-Key')).toBe(key);
    success.flush({ paymentEntryId: receipt }); await settle(); http.expectOne(path).flush(detail); await last; await view.retry();
  });
  it.each([['DUPLICATE_PAYMENT', 409, 'already exist'], ['BAD', 400, 'could not be recorded'], ['THROTTLED', 429, 'wait']])('reports %s safely', async (code, status, hint) => {
    await loaded(); fill(); await reject(code as string, status as number); expect(view.message()).toContain(hint); expect(view.message()).not.toContain('provider');
  });
  it('retains successful entry evidence when refresh fails', async () => {
    await loaded(); fill(); const p = view.submit(); (await post()).flush({ paymentEntryId: receipt }); await settle(); http.expectOne(path).flush({}, { status: 503, statusText: 'x' }); await p;
    expect(view.message()).toContain('Entry recorded, but'); expect(view.booking()).toBeNull(); expect(view.form.controls.reference.value).toBe('');
  });
  it('clears private data on fresh-factor expiry and returns to finance', async () => {
    const nav = vi.spyOn(router, 'navigate'); await loaded(); fill(); await reject('AUTHENTICATION_REQUIRED', 403);
    expect(nav).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/bookings/' + id + '/finance' } }); expect(view.booking()).toBeNull(); expect(view.form.controls.reference.value).toBe('');
  });
  it('clears private data on session expiry and handles expired reads', async () => {
    await loaded(); fill(); await reject('SESSION_REQUIRED', 401); expect(view.booking()).toBeNull(); expect(TestBed.inject(SessionAccess).state()).toBeNull();
    const nav = vi.spyOn(router, 'navigateByUrl'); const p = view.load(); http.expectOne(path).flush({}, { status: 401, statusText: 'x' }); await p; expect(nav).toHaveBeenCalledWith('/admin/login');
  });
  it('rejects malformed IDs and ignores submit without a booking', async () => {
    routeId = 'bad'; create(); await view.submit(); expect(view.message()).toContain('could not be found'); http.expectNone(path);
  });
  it('ignores reads and commands completed after destruction', async () => {
    create(); const r = http.expectOne(path); fixture.destroy(); r.flush(detail); await settle(); expect(view.booking()).toBeNull();
  });
  it('ignores command completion after invalidation', async () => {
    await loaded(); fill(); const p = view.submit(); const r = await post(); TestBed.inject(SessionAccess).invalidate(); r.flush({ paymentEntryId: receipt }); await p; expect(view.booking()).toBeNull();
  });
  it('renders no-money state and handles preparation failure', async () => {
    await loaded({ ...detail, payment: { ...detail.payment, entries: [], refundObligations: [], receivedByCurrency: {}, refundDueByCurrency: {} } }); expect(fixture.nativeElement.textContent).toContain('No actual money');
    fill(); vi.spyOn(TestBed.inject(CommandAccess), 'prepare').mockImplementation(() => { throw new Error(); }); await view.submit(); expect(view.message()).toContain('could not be submitted');
  });
});
