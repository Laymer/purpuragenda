import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { EnrollmentRequestComponent } from './enrollment-request.component';
import { EnrollmentCompletionComponent } from './enrollment-completion.component';
import { VerifyEmailComponent } from '../booking/verify-email.component';
import { captureVerificationProof, VERIFICATION_PROOF } from '../booking/verification-proof';
import { SessionAccess } from '../shared/access/session-access';
import { provideApi } from '../shared/generated';
import { routes } from '../app.config';
const now = Date.parse('2026-10-28T10:00:00Z'), token = 'a'.repeat(43), password = ' exact synthetic account password ';
const reply = () => ({ role: 'ANONYMOUS', csrfToken: 'synthetic-proof', idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() });
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };

describe('Optional account proof request', () => {
  let http: HttpTestingController, view: EnrollmentRequestComponent;
  let fixture: ReturnType<typeof TestBed.createComponent<EnrollmentRequestComponent>>;
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now); TestBed.configureTestingModule({ imports: [EnrollmentRequestComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([])] });
    http = TestBed.inject(HttpTestingController); fixture = TestBed.createComponent(EnrollmentRequestComponent); view = fixture.componentInstance; fixture.detectChanges();
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });
  async function request() { view.form.controls.email.setValue(' Synthetic@Example.TEST '); const pending = view.send(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply()); await settle(); return { pending, call: http.expectOne('/api/v1/account/enrollment-proofs') }; }
  it('keeps enrollment optional, validates a real submit and exposes the lazy route', async () => {
    expect(await routes.find(route => route.path === 'account/create')!.loadComponent!()).toBe(EnrollmentRequestComponent); expect(fixture.nativeElement.querySelector('a[href="/book"]')).not.toBeNull();
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); fixture.detectChanges(); expect(view.message()).toContain('valid email'); expect(fixture.nativeElement.querySelector('input').getAttribute('aria-invalid')).toBe('true'); http.expectNone('/api/v1/session/bootstrap');
  });
  it('issues only an account proof with CSRF and shows a generic message and cooldown', async () => {
    const { pending, call } = await request(); await view.send(); expect(call.request.body).toEqual({ email: 'Synthetic@Example.TEST' }); expect(call.request.headers.get('X-CSRF-Token') === 'synthetic-proof').toBe(true); call.flush({ accepted: true }); await pending; expect(view.message()).toContain('Check your inbox'); expect(view.cooling()).toBe(true); await view.send(); vi.advanceTimersByTime(59999); expect(view.cooling()).toBe(true); vi.advanceTimersByTime(1); expect(view.cooling()).toBe(false); http.expectNone('/api/v1/guest/create-proofs');
  });
  it('respects a long Retry-After without overflowing the browser timer', async () => {
    const { pending, call } = await request(); call.flush({}, { status: 429, statusText: 'Limited', headers: { 'Retry-After': '3000000' } }); await pending; expect(view.message()).toContain('wait'); vi.advanceTimersByTime(2147483647); expect(view.cooling()).toBe(true); vi.advanceTimersByTime(3000000000 - 2147483647); expect(view.cooling()).toBe(false);
  });
  it.each([400, 401, 503])('handles %s safely without retrying or retaining invalidated email', async status => {
    const { pending, call } = await request(); call.flush({ detail: 'synthetic-provider' }, { status, statusText: 'Failed' }); await pending; expect(view.message()).not.toContain('provider'); expect(view.message()).toContain(status === 400 ? 'Check the email' : status === 401 ? 'expired' : 'could not be confirmed'); if (status === 401) expect(view.form.controls.email.value).toBe(''); if (status === 503) expect(view.cooling()).toBe(true);
  });
  it.each([false, true])('ignores late completion after destruction (%s)', async failed => {
    const { pending, call } = await request(); fixture.destroy(); if (failed) call.error(new ProgressEvent('error')); else call.flush({ accepted: true }); await pending; expect(view.form.controls.email.value).toBe(''); expect(view.message()).toBe('');
  });
});

describe('Explicit account enrollment from a scrubbed fragment', () => {
  let http: HttpTestingController, view: EnrollmentCompletionComponent;
  let fixture: ReturnType<typeof TestBed.createComponent<VerifyEmailComponent>>;
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now); TestBed.configureTestingModule({ imports: [VerifyEmailComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([]), { provide: VERIFICATION_PROOF, useValue: captureVerificationProof('/verify', `#token=${token}&purpose=ACCOUNT`, () => {}) }] });
    http = TestBed.inject(HttpTestingController); fixture = TestBed.createComponent(VerifyEmailComponent); fixture.detectChanges(); view = fixture.debugElement.query(By.directive(EnrollmentCompletionComponent)).componentInstance;
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });
  function fill() { view.form.setValue({ password, confirm: password }); }
  async function enroll() { fill(); const pending = view.enroll(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply()); await settle(); return { pending, call: http.expectOne('/api/v1/account/enrollments') }; }
  it('never consumes ACCOUNT as guest proof or enrolls on navigation and renders no bearer', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'); await fixture.componentInstance.verify(); expect(TestBed.inject(VERIFICATION_PROOF).available).toBe(true); http.expectNone('/api/v1/guest/redemptions'); http.expectNone('/api/v1/account/enrollments'); expect(fixture.nativeElement.textContent.includes(token)).toBe(false); expect(fixture.nativeElement.querySelectorAll('h1').length).toBe(1); expect(fixture.nativeElement.textContent).toContain('opening the link does not'); expect(storage).not.toHaveBeenCalled(); storage.mockRestore();
  });
  it('validates password length and matching confirmation without consuming the proof', async () => {
    document.body.appendChild(fixture.nativeElement);
    try { for (const [first, second] of [['short', 'short'], ['x'.repeat(129), 'x'.repeat(129)], [password, 'different']]) { view.form.setValue({ password: first, confirm: second }); fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); fixture.detectChanges(); expect(view.message()).toContain('15–128'); expect(document.activeElement).toBe(fixture.nativeElement.querySelector('#complete-account-message')); expect(TestBed.inject(VERIFICATION_PROOF).available).toBe(true); } }
    finally { fixture.nativeElement.remove(); }
    expect(view.invalid('confirm')).toBe(true); http.expectNone('/api/v1/session/bootstrap');
  });
  it('rejects eight emoji without consuming the proof and submits65 emoji exactly', async () => {
    view.form.setValue({ password: '😀'.repeat(8), confirm: '😀'.repeat(8) }); await view.enroll(); expect(view.available()).toBe(true); expect(TestBed.inject(VERIFICATION_PROOF).available).toBe(true); http.expectNone('/api/v1/session/bootstrap');
    const unicode = '😀'.repeat(65); view.form.setValue({ password: unicode, confirm: unicode }); const pending = view.enroll(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply()); await settle(); const request = http.expectOne('/api/v1/account/enrollments'); expect(request.request.body.password === unicode).toBe(true); request.flush(null); await pending; expect(view.message()).toContain('was created');
  });
  it('submits exact chosen credentials once and requires separate login after204', async () => {
    const { pending, call } = await enroll(); await view.enroll(); expect(call.request.body.token === token && call.request.body.password === password).toBe(true); expect(call.request.headers.has('X-CSRF-Token')).toBe(true); expect(view.form.controls.password.value).toBe(''); expect(view.available()).toBe(false); call.flush(null, { status: 204, statusText: 'No Content' }); await pending; fixture.detectChanges(); expect(view.message()).toContain('was created'); expect(TestBed.inject(SessionAccess).state()?.role).toBe('ANONYMOUS'); expect(fixture.nativeElement.querySelector('a[href="/account/login"]')).not.toBeNull(); expect(TestBed.inject(VERIFICATION_PROOF).available).toBe(false); await view.enroll(); http.expectNone('/api/v1/account/login');
  });
  it.each([400, 401, 429, 503])('handles enrollment %s without exposing detail or repeating the proof', async status => {
    const { pending, call } = await enroll(); call.flush({ detail: 'synthetic-provider' }, { status, statusText: 'Failed', headers: { 'Retry-After': '60' } }); await pending; expect(view.message()).not.toContain('provider'); expect(view.message()).toContain(status === 429 ? 'Too many' : status === 503 ? 'could not be confirmed' : 'could not be used'); expect(view.form.getRawValue()).toEqual({ password: '', confirm: '' }); await view.enroll(); http.expectNone('/api/v1/account/enrollments');
  });
  it('clears unused proof and credentials on invalidation', async () => {
    fill(); TestBed.inject(SessionAccess).invalidate(); expect(view.form.controls.password.value).toBe(''); expect(view.available()).toBe(false); expect(view.message()).toContain('session changed'); await view.enroll(); http.expectNone('/api/v1/session/bootstrap');
  });
  it.each([false, true])('ignores late enrollment after destruction (%s)', async failed => {
    const { pending, call } = await enroll(); fixture.destroy(); if (failed) call.error(new ProgressEvent('error')); else call.flush(null); await pending; expect(view.message()).toBe(''); expect(TestBed.inject(VERIFICATION_PROOF).available).toBe(false); expect(view.form.controls.password.value).toBe('');
  });
  it('fails closed when the proof was cleared before submission', async () => {
    fill(); TestBed.inject(VERIFICATION_PROOF).clear(); await view.enroll(); expect(view.available()).toBe(false); http.expectNone('/api/v1/session/bootstrap');
  });
});
