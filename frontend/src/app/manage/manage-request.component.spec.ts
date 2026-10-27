import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideApi } from '../shared/generated';
import { routes } from '../app.config';
import { ManageRequestComponent } from './manage-request.component';
import { ManageBookingComponent } from './manage-booking.component';

const now = Date.parse('2026-10-26T10:00:00Z');
const session = { role: 'ANONYMOUS', csrfToken: 'x'.repeat(43), idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() };
const settle = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
const NEUTRAL = 'If those details match a booking';

describe('Management link request', () => {
  let http: HttpTestingController, view: ManageRequestComponent;
  let fixture: ReturnType<typeof TestBed.createComponent<ManageRequestComponent>>;
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now);
    TestBed.configureTestingModule({ imports: [ManageRequestComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1')] });
    http = TestBed.inject(HttpTestingController); fixture = TestBed.createComponent(ManageRequestComponent); view = fixture.componentInstance; fixture.detectChanges();
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });
  async function submit(reference = ' B-synthetic ', email = ' synthetic@example.test ') {
    view.form.setValue({ reference, email }); const pending = view.send(); await settle();
    const boot = http.match('/api/v1/session/bootstrap'); boot.forEach(request => request.flush(session)); await settle();
    return { pending, request: http.expectOne('/api/v1/guest/management-proofs') };
  }

  it('registers /manage and /manage/booking lazily before the wildcard', async () => {
    const paths = routes.map(route => route.path);
    expect(paths.indexOf('manage')).toBeLessThan(paths.indexOf('**')); expect(paths.indexOf('manage/booking')).toBeLessThan(paths.indexOf('**'));
    expect(await routes.find(route => route.path === 'manage')!.loadComponent!()).toBe(ManageRequestComponent);
    expect(await routes.find(route => route.path === 'manage/booking')!.loadComponent!()).toBe(ManageBookingComponent);
  });
  it('validates locally without any request', async () => {
    await view.send(); fixture.detectChanges(); expect(view.message()).toContain('valid email'); http.expectNone('/api/v1/session/bootstrap');
    view.form.setValue({ reference: 'B-1', email: 'not-an-email' }); await view.send(); http.expectNone('/api/v1/session/bootstrap');
    expect(fixture.nativeElement.querySelector('#manage-email').getAttribute('aria-describedby')).toBe('manage-message');
  });
  it('sends trimmed values with CSRF and answers neutrally, then enforces a cooldown', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'); const { pending, request } = await submit();
    expect(request.request.body).toEqual({ reference: 'B-synthetic', email: 'synthetic@example.test' }); expect(request.request.headers.get('X-CSRF-Token')).toBe(session.csrfToken);
    request.flush({}, { status: 202, statusText: 'Accepted' }); await pending; expect(view.message()).toContain(NEUTRAL); expect(view.cooling()).toBe(true);
    await view.send(); http.expectNone('/api/v1/guest/management-proofs'); await vi.advanceTimersByTimeAsync(60000); expect(view.cooling()).toBe(false); expect(storage).not.toHaveBeenCalled();
  });
  it('shows safe, non-revealing messages for rejected, throttled, expired and unavailable results', async () => {
    let r = await submit(); r.request.flush({ code: 'INVALID_REQUEST', detail: 'synthetic-provider-detail' }, { status: 400, statusText: 'Bad' }); await r.pending;
    expect(view.message()).toContain('Check the booking reference'); expect(view.message()).not.toContain('provider'); expect(view.message()).not.toContain('No booking');
    r = await submit(); r.request.flush({}, { status: 429, statusText: 'Many', headers: { 'Retry-After': '5' } }); await r.pending;
    expect(view.message()).toContain('wait'); expect(view.cooling()).toBe(true); await vi.advanceTimersByTimeAsync(5000); expect(view.cooling()).toBe(false);
    r = await submit(); r.request.flush({ code: 'SESSION_REQUIRED' }, { status: 401, statusText: 'No' }); await r.pending; expect(view.message()).toContain('session expired');
    r = await submit(); r.request.flush({}, { status: 503, statusText: 'Unavailable' }); await r.pending; expect(view.message()).toContain('could not send'); expect(view.busy()).toBe(false);
  });
  it('coalesces a second submit while busy and ignores a late reply after destruction', async () => {
    const { pending, request } = await submit(); await view.send(); fixture.destroy(); request.flush({}, { status: 202, statusText: 'Accepted' }); await pending;
    expect(view.message()).toBe('');
  });
});
