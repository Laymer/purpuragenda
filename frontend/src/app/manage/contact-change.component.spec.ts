import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideApi } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { CommandAccess } from '../shared/access/command-access';
import { captureVerificationProof, VERIFICATION_PROOF } from '../booking/verification-proof';
import { VerifyEmailComponent } from '../booking/verify-email.component';
import { ContactRequestComponent } from './contact-request.component';
import { ContactConfirmationComponent } from './contact-confirmation.component';
const now = Date.parse('2026-10-28T10:00:00Z'), id = '20000000-0000-4000-8000-000000000001', other = '20000000-0000-4000-8000-000000000002';
const token = 's'.repeat(43), base = '/api/v1/bookings/' + id;
const session = (role = 'GUEST') => ({ role, csrfToken: 'synthetic-csrf', idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() });
const booking = (version = 1) => ({ id, reference: 'B-synthetic', version, service: { name: 'Synthetic consultation' }, startInstant: '2026-10-30T09:00:00Z', displayZone: 'Europe/Brussels' });
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };
function providers() { return [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([])]; }
describe('Contact proof request', () => {
  let http: HttpTestingController, view: ContactRequestComponent, fixture: ReturnType<typeof TestBed.createComponent<ContactRequestComponent>>;
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(now); TestBed.configureTestingModule({ imports: [ContactRequestComponent], providers: providers() }); http = TestBed.inject(HttpTestingController); fixture = TestBed.createComponent(ContactRequestComponent); fixture.componentRef.setInput('bookingId', id); view = fixture.componentInstance; fixture.detectChanges(); });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });
  async function bootstrap() { await settle(); http.expectOne('/api/v1/session/bootstrap').flush(session()); await settle(); }
  it('requires a valid email and respects parent mutation blocking', async () => { await view.send(); expect(view.email.touched).toBe(true); expect(view.message()).toContain('valid email'); view.email.setValue('next@example.test'); fixture.componentRef.setInput('disabled', true); await view.send(); http.expectNone('/api/v1/session/bootstrap'); });
  it('requests only a proof, with CSRF and a cooldown', async () => {
    view.email.setValue(' next@example.test '); const send = view.send(); await view.send(); await bootstrap(); const request = http.expectOne(base + '/contact-proofs'); expect(request.request.body).toEqual({ email: 'next@example.test' }); expect(request.request.headers.has('X-CSRF-Token')).toBe(true); request.flush({ accepted: true }); await send;
    expect(view.message()).toContain('explicitly confirm'); expect(view.cooling()).toBe(true); await view.send(); await vi.advanceTimersByTimeAsync(60000); expect(view.cooling()).toBe(false);
  });
  it('submits the rendered reactive form through its native submit event', async () => {
    view.email.setValue('next@example.test'); fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await bootstrap();
    http.expectOne(base + '/contact-proofs').flush({ accepted: true }); await settle(); expect(view.message()).toContain('Check the new inbox');
  });
  it.each([400, 404, 403, 401, 429, 503])('contains proof request failure %s', async status => {
    view.email.setValue('next@example.test'); const send = view.send(); await bootstrap(); http.expectOne(base + '/contact-proofs').flush({ detail: 'synthetic-private' }, { status, statusText: 'Failure', headers: { 'Retry-After': '2' } }); await send;
    expect(view.message()).not.toContain('synthetic'); expect(view.busy()).toBe(false);
    if ([401, 403, 404].includes(status)) { expect(view.lost()).toBe(true); expect(view.email.value).toBe(''); await view.send(); }
    else if (status === 400) expect(view.message()).toContain('Check the new email'); else { expect(view.cooling()).toBe(true); if (status === 429) { await vi.advanceTimersByTimeAsync(2000); expect(view.cooling()).toBe(false); } }
  });
  it.each(['route', 'session', 'destroy'])('ignores pending delivery after %s change', async kind => {
    view.email.setValue('next@example.test'); const send = view.send(); await bootstrap(); const request = http.expectOne(base + '/contact-proofs');
    if (kind === 'route') { fixture.componentRef.setInput('bookingId', other); fixture.detectChanges(); } else if (kind === 'session') TestBed.inject(SessionAccess).invalidate(); else fixture.destroy();
    request.flush({ accepted: true }); await send; expect(view.email.value).toBe(''); expect(view.cooling()).toBe(false); expect(view.message()).not.toContain('Check the new inbox');
  });
  it('does not overflow long Retry-After timers and clears them when the session expires', async () => { view.email.setValue('next@example.test'); const send = view.send(); await bootstrap(); http.expectOne(base + '/contact-proofs').flush({}, { status: 429, statusText: 'Wait', headers: { 'Retry-After': '2147484' } }); await send; await vi.advanceTimersByTimeAsync(1); expect(view.cooling()).toBe(true); await vi.advanceTimersByTimeAsync(600000); expect(view.cooling()).toBe(false); expect(view.lost()).toBe(true); await view.send(); });
});
describe('Explicit contact confirmation', () => {
  let http: HttpTestingController, view: ContactConfirmationComponent, fixture: ReturnType<typeof TestBed.createComponent<ContactConfirmationComponent>>;
  const read = () => http.expectOne(base);
  function setup(fragment = `#token=${token}&purpose=CONTACT&bookingId=${id}`) {
    TestBed.configureTestingModule({ imports: [ContactConfirmationComponent, VerifyEmailComponent], providers: [...providers(), { provide: VERIFICATION_PROOF, useValue: captureVerificationProof('/verify', fragment, () => {}) }] });
    http = TestBed.inject(HttpTestingController); fixture = TestBed.createComponent(ContactConfirmationComponent); view = fixture.componentInstance; fixture.detectChanges();
  }
  async function loaded(tag?: string) { read().flush(booking(), { headers: tag ? { ETag: tag } : {} }); await settle(); fixture.detectChanges(); }
  async function bootstrap(role = 'GUEST') { await settle(); http.expectOne('/api/v1/session/bootstrap').flush(session(role)); await settle(); }
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(now); });
  afterEach(() => { try { http?.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });
  it('reads scope but never mutates on landing, then confirms once and clears its proof', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'); setup(); await loaded('"7"'); expect(fixture.nativeElement.textContent).toContain('B-synthetic'); expect(fixture.nativeElement.textContent).not.toContain(token); http.expectNone(base + '/contact');
    const send = view.confirm(); await view.confirm(); await view.load(); await bootstrap('ACCOUNT'); const request = http.expectOne(base + '/contact'); expect(request.request.headers.get('If-Match')).toBe('"7"'); expect(JSON.parse(request.request.body).token === token).toBe(true); request.flush({ bookingId: id, version: 8 }); await send; fixture.detectChanges();
    expect(view.done()).toBe(true); expect(view.available()).toBe(false); expect(view.bookingLink).toBe('/account/bookings/' + id); await view.confirm(); await view.load(); await view.retry(); expect(storage).not.toHaveBeenCalled(); storage.mockRestore();
  });
  it('renders CONTACT separately from guest redemption and enrollment', async () => {
    TestBed.configureTestingModule({ imports: [VerifyEmailComponent], providers: [...providers(), { provide: VERIFICATION_PROOF, useValue: captureVerificationProof('/verify', `#token=${token}&purpose=CONTACT&bookingId=${id}`, () => {}) }] }); http = TestBed.inject(HttpTestingController);
    const page = TestBed.createComponent(VerifyEmailComponent); page.detectChanges(); read().flush(booking()); await settle(); page.detectChanges(); expect(page.nativeElement.textContent).toContain('Change booking delivery email'); await page.componentInstance.verify(); http.expectNone('/api/v1/guest/redemptions'); expect(page.nativeElement.textContent).not.toContain(token);
  });
  it('retains exact command/token/version through uncertain retry and Retry-After', async () => {
    setup(); await loaded(); const send = view.confirm(); await bootstrap(); const first = http.expectOne(base + '/contact'), key = first.request.headers.get('Idempotency-Key'), body = first.request.body; first.error(new ProgressEvent('error')); await send;
    expect(view.uncertain()).toBe(true); await view.confirm(); await view.load(); const retry = view.retry(); await settle(); const second = http.expectOne(base + '/contact'); expect(second.request.body).toBe(body); expect(second.request.headers.get('Idempotency-Key')).toBe(key); second.flush({}, { status: 429, statusText: 'Wait', headers: { 'Retry-After': '2' } }); await retry;
    await view.retry(); await vi.advanceTimersByTimeAsync(2000); const again = view.retry(); await settle(); const third = http.expectOne(base + '/contact'); expect(third.request.headers.get('If-Match')).toBe('"1"'); expect(third.request.headers.get('Idempotency-Key')).toBe(key); third.flush({}); await again; expect(view.done()).toBe(true); expect(view.bookingLink).toBe('/manage/booking');
  });
  it.each([412, 428])('reloads %s then requires an explicit new versioned confirmation', async status => {
    setup(); await loaded('W/"1"'); const send = view.confirm(); await bootstrap(); const first = http.expectOne(base + '/contact'), key = first.request.headers.get('Idempotency-Key'); first.flush({}, { status, statusText: 'Version' }); await settle(); read().flush(booking(3), { headers: { ETag: '"3"' } }); await send; expect(view.message()).toContain('explicitly confirm again');
    const retry = view.confirm(); await settle(); const next = http.expectOne(base + '/contact'); expect(next.request.headers.get('If-Match')).toBe('"3"'); expect(next.request.headers.get('Idempotency-Key')).not.toBe(key); next.flush({}); await retry;
  });
  it.each([400, 409, 401, 403, 404])('clears proof state for a definite %s rejection', async status => { setup(); await loaded(); const send = view.confirm(); await bootstrap(); http.expectOne(base + '/contact').flush({ detail: 'synthetic-private' }, { status, statusText: 'Rejected' }); await send; expect(view.available()).toBe(false); expect(view.booking()).toBeNull(); expect(view.message()).not.toContain('synthetic'); await view.confirm(); await view.retry(); });
  it.each([401, 403, 404, 503])('handles booking read failure %s before confirmation', async status => { setup(); read().flush({}, { status, statusText: 'Unavailable' }); await settle(); expect(view.booking()).toBeNull(); if (status === 503) { const retry = view.load(); read().flush(booking()); await retry; expect(view.booking()).not.toBeNull(); } else { expect(view.lost()).toBe(true); await view.load(); } });
  it('handles a null booking and missing proof without mutation', async () => { setup(''); await view.confirm(); await view.load(); expect(view.message()).toContain('missing or invalid'); });
  it('contains null read bodies and command preparation failures', async () => { setup(); read().flush(null); await settle(); expect(view.message()).toContain('could not be loaded'); const reload = view.load(); read().flush(booking()); await reload; vi.spyOn(TestBed.inject(CommandAccess), 'prepare').mockImplementation(() => { throw Error('synthetic'); }); await view.confirm(); expect(view.message()).toContain('could not be prepared'); });
  it.each(['read', 'send', 'conflict'])('ignores late %s completion after invalidation', async phase => {
    setup(); if (phase === 'read') { const request = read(); TestBed.inject(SessionAccess).invalidate(); request.flush(booking()); await settle(); }
    else { await loaded(); const send = view.confirm(); await bootstrap(); const request = http.expectOne(base + '/contact'); if (phase === 'send') { fixture.destroy(); request.flush({}); } else { request.flush({}, { status: 412, statusText: 'Stale' }); await settle(); const refresh = read(); TestBed.inject(SessionAccess).invalidate(); refresh.flush(booking(2)); } await send; }
    expect(view.booking()).toBeNull(); expect(view.available()).toBe(false); expect(view.done()).toBe(false);
  });
  it('retains a proof when conflict refresh fails without silently resubmitting', async () => { setup(); await loaded(); const send = view.confirm(); await bootstrap(); http.expectOne(base + '/contact').flush({}, { status: 412, statusText: 'Stale' }); await settle(); read().flush({}, { status: 503, statusText: 'Unavailable' }); await send; expect(view.message()).toContain('Reload the latest'); expect(view.available()).toBe(true); await view.confirm(); });
});
