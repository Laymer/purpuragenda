import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideApi } from '../shared/generated';
import { CommandAccess } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { ConfigurationReviewComponent } from './configuration-review.component';
import { PolicyComponent } from './policy.component';

const now = Date.parse('2026-10-28T10:00:00Z'), id = '30000000-0000-4000-8000-000000000001';
const policy = { gridMinutes: 30, dailyCapacity: 3, minimumNotice: 'PT24H', pendingLifetime: 'PT24H', expiryBuffer: 'PT1H', customerCutoff: 'PT24H' };
const config = { calendarVersion: 5, policyVersion: 7, policy };
const conflict = { bookingId: id, reference: '<b>B-synthetic</b>', startInstant: '2026-11-02T09:00:00Z', state: 'CONFIRMED', reasons: ['START_RULES', 'HARD_BLACKOUT', 'DAILY_CAPACITY', 'UNKNOWN'], blackoutIds: [] };
const review = { assessedAt: new Date(now).toISOString(), calendarVersion: 5, policyVersion: 7, conflicts: [conflict] };
const session = { role: 'ADMIN', csrfToken: 'x'.repeat(43), idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() };
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };

describe('Administrator policy conflict review', () => {
  let http: HttpTestingController, router: Router;
  let fixture: ReturnType<typeof TestBed.createComponent<PolicyComponent>>, view: PolicyComponent;
  const create = () => { fixture = TestBed.createComponent(PolicyComponent); view = fixture.componentInstance; fixture.detectChanges(); };
  async function loaded() { create(); http.expectOne('/api/v1/admin/configuration').flush(config); await settle(); fixture.detectChanges(); }
  async function request(path: string) { await settle(); http.match('/api/v1/session/bootstrap').forEach(r => r.flush(session)); await settle(); return http.expectOne('/api/v1/admin/configuration/' + path); }
  async function reviewed(result = review) { await loaded(); const p = view.preview(); (await request('conflicts')).flush(result); await p; fixture.detectChanges(); }
  function acknowledge() { fixture.detectChanges(); const checkbox = fixture.nativeElement.querySelector('app-configuration-review input') as HTMLInputElement; checkbox.checked = true; checkbox.dispatchEvent(new Event('change')); view.reason.setValue('Synthetic policy reason'); }
  async function reject(code: string, status = 409) { const p = view.save(); (await request('policy')).flush({ code, detail: 'synthetic-provider-error' }, { status, statusText: 'x' }); await p; }
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now);
    TestBed.configureTestingModule({ imports: [PolicyComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([])] });
    http = TestBed.inject(HttpTestingController); router = TestBed.inject(Router);
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });

  it('previews the complete policy with CSRF and requires explicit acknowledgement and reason', async () => {
    await loaded(); expect(view.form.controls.minimumNotice.value).toBe('24h'); await view.save();
    view.form.patchValue({ dailyCapacity: 0, minimumNotice: ' 1h 30m ', pendingLifetime: '0.000000001s', expiryBuffer: '0s' }); const p = view.preview(); const r = await request('conflicts');
    expect(r.request.headers.get('X-CSRF-Token')).toBe(session.csrfToken); expect(r.request.headers.has('If-Match')).toBe(false); expect(r.request.body).toEqual({ proposal: { ...policy, dailyCapacity: 0, minimumNotice: 'PT1H30M', pendingLifetime: 'PT0.000000001S', expiryBuffer: 'PT0S' } }); r.flush(review); await p; fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Outside the proposed start schedule'); expect(fixture.nativeElement.textContent).toContain('Intersects a proposed blackout'); expect(fixture.nativeElement.textContent).toContain('Exceeds the proposed daily capacity'); expect(fixture.nativeElement.textContent).toContain('Requires review'); expect(fixture.nativeElement.querySelector('li a b')).toBeNull();
    await view.save(); http.expectNone('/api/v1/admin/configuration/policy'); view.acknowledged.set(true); await view.save(); expect(view.message()).toContain('reason');
    acknowledge(); const save = view.save(); const command = await request('policy'); expect(command.request.headers.get('If-Match')).toBe('"7"'); expect(JSON.parse(command.request.body).acknowledgedBookingIds).toEqual([id]); expect(JSON.parse(command.request.body).policy.dailyCapacity).toBe(0);
    command.flush({ policyVersion: 8 }); await settle(); http.expectOne('/api/v1/admin/configuration').flush({ ...config, policyVersion: 8 }); await save; expect(view.message()).toContain('Policy saved'); expect(view.review()).toBeNull(); expect(view.reason.value).toBe('');
  });
  it('also requires acknowledgement for an empty conflict set and sends exactly an empty array', async () => {
    await reviewed({ ...review, conflicts: [] }); expect(fixture.nativeElement.textContent).toContain('No new conflicts'); acknowledge(); const p = view.save(); const r = await request('policy'); expect(JSON.parse(r.request.body).acknowledgedBookingIds).toEqual([]); r.flush({}, { status: 400, statusText: 'x' }); await p;
  });
  it('invalidates a reviewed proposal and acknowledgement when any policy value changes', async () => {
    await reviewed(); acknowledge(); view.form.controls.dailyCapacity.setValue(4); expect(view.review()).toBeNull(); expect(view.acknowledged()).toBe(false); await view.save(); http.expectNone('/api/v1/admin/configuration/policy');
  });
  it.each([{ gridMinutes: 0 }, { gridMinutes: 121 }, { gridMinutes: 1.5 }, { dailyCapacity: -1 }, { dailyCapacity: 2147483648 }, { pendingLifetime: '0h0m0s' }, { minimumNotice: '-1h' }, { minimumNotice: 'PT1H' }, { customerCutoff: '1.1234567890s' }])('rejects invalid policy values %j', async values => {
    await loaded(); view.form.patchValue(values); await view.preview(); expect(view.message()).toContain('valid durations'); http.expectNone('/api/v1/admin/configuration/conflicts');
  });
  it.each([{ policyVersion: 8 }, { calendarVersion: 6 }])('rejects a configuration version change during preview %j', async versions => {
    await reviewed({ ...review, ...versions }); expect(view.review()).toBeNull(); expect(view.configuration()).toBeNull(); expect(view.message()).toContain('Reload'); await view.preview();
  });
  it('automatically re-previews a changed conflict set and requires a new acknowledgement', async () => {
    await reviewed(); acknowledge(); const p = view.save(); (await request('policy')).flush({ code: 'CONFLICT_REVIEW_REQUIRED' }, { status: 409, statusText: 'x' });
    (await request('conflicts')).flush({ ...review, conflicts: [{ ...conflict, bookingId: '30000000-0000-4000-8000-000000000002' }] }); await p; expect(view.acknowledged()).toBe(false); expect(view.message()).toContain('acknowledge them again'); await view.save();
    acknowledge(); const next = view.save(); const r = await request('policy'); expect(JSON.parse(r.request.body).acknowledgedBookingIds).toEqual(['30000000-0000-4000-8000-000000000002']); r.flush({}, { status: 400, statusText: 'x' }); await next;
  });
  it('does not claim updated conflicts if automatic re-preview fails', async () => {
    await reviewed(); acknowledge(); const p = view.save(); (await request('policy')).flush({ code: 'CONFLICT_REVIEW_REQUIRED' }, { status: 409, statusText: 'x' });
    (await request('conflicts')).flush({}, { status: 503, statusText: 'x' }); await p; expect(view.review()).toBeNull(); expect(view.uncertain()).toBe(false); expect(view.message()).toContain('could not be loaded');
  });
  it.each([412, 428])('requires reloading after HTTP%s', async status => {
    await reviewed(); acknowledge(); await reject('VERSION', status); expect(view.configuration()).toBeNull(); expect(view.message()).toContain('Reload');
  });
  it('retains uncertain mutations through throttling but never treats uncertain previews as a committed change', async () => {
    await reviewed(); acknowledge(); const p = view.save(); const r = await request('policy'); const body = r.request.body, key = r.request.headers.get('Idempotency-Key');
    await view.save(); await view.preview(); await view.load(); await view.retry(); r.flush({}, { status: 503, statusText: 'x' }); await p; expect(view.uncertain()).toBe(true);
    await view.save(); await view.preview(); await view.load(); const retry = view.retry(); const limited = await request('policy'); limited.flush({}, { status: 429, statusText: 'x', headers: { 'Retry-After': '2' } }); await retry; await view.retry(); expect(view.message()).toContain('still unknown');
    await vi.advanceTimersByTimeAsync(2001); const last = view.retry(); const success = await request('policy'); expect(success.request.body).toBe(body); expect(success.request.headers.get('Idempotency-Key')).toBe(key); success.flush({ policyVersion: 8 }); await settle(); http.expectOne('/api/v1/admin/configuration').flush(config); await last; await view.retry();
  });
  it('recovers from a lost preview response without freezing a command', async () => {
    await loaded(); const p = view.preview(); (await request('conflicts')).flush({}, { status: 503, statusText: 'x' }); await p; expect(view.uncertain()).toBe(false); expect(view.review()).toBeNull();
  });
  it('clears private reasons and returns to policy after fresh-factor expiry', async () => {
    const nav = vi.spyOn(router, 'navigate'); await reviewed(); acknowledge(); await reject('AUTHENTICATION_REQUIRED', 403);
    expect(nav).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/policy' } }); expect(view.configuration()).toBeNull(); expect(view.reason.value).toBe('');
  });
  it('clears state after a session-expired command and redirects expired reads', async () => {
    await reviewed(); acknowledge(); await reject('SESSION_REQUIRED', 401); expect(view.configuration()).toBeNull();
    const nav = vi.spyOn(router, 'navigateByUrl'); const p = view.load(); http.expectOne('/api/v1/admin/configuration').flush({}, { status: 401, statusText: 'x' }); await p; expect(nav).toHaveBeenCalledWith('/admin/login');
  });
  it('keeps success evidence when reloading after a committed change fails', async () => {
    await reviewed(); acknowledge(); const p = view.save(); (await request('policy')).flush({ policyVersion: 8 }); await settle(); http.expectOne('/api/v1/admin/configuration').flush({}, { status: 503, statusText: 'x' }); await p; expect(view.message()).toContain('Policy saved, but');
  });
  it('handles preparation failure and throttling safely', async () => {
    await reviewed(); acknowledge(); const prepare = vi.spyOn(TestBed.inject(CommandAccess), 'prepare').mockImplementation(() => { throw new Error(); }); await view.save(); expect(view.message()).toContain('could not be submitted'); prepare.mockRestore();
    await reject('THROTTLED', 429); expect(view.message()).toContain('wait'); expect(view.review()).toBeNull();
  });
  it('ignores reads completed after destruction', async () => {
    create(); const r = http.expectOne('/api/v1/admin/configuration'); fixture.destroy(); r.flush(config); await settle(); expect(view.configuration()).toBeNull();
  });
  it('ignores previews completed after invalidation', async () => {
    await loaded(); const p = view.preview(); const r = await request('conflicts'); TestBed.inject(SessionAccess).invalidate(); r.flush(review); await p; expect(view.review()).toBeNull();
  });
  it('ignores mutation responses after destruction', async () => {
    await reviewed(); acknowledge(); const p = view.save(); const r = await request('policy'); fixture.destroy(); r.flush({ policyVersion: 8 }); await p; expect(view.configuration()).toBeNull();
  });
});
