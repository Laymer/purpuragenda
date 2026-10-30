import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { provideApi, AdministratorPrivacyItem } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { ErasureComponent } from './erasure.component';
const id = '30000000-0000-4000-8000-000000000001', other = '30000000-0000-4000-8000-000000000002';
const base = '/api/v1/admin/customers/' + id, read = '/api/v1/admin/erasures/' + other;
const now = Date.parse('2026-10-29T10:00:00Z');
const session = { role: 'ADMIN', csrfToken: 'x'.repeat(43), idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() };
const item = { scope: { kind: 'NOTE', id: other }, status: 'REVIEW_DUE', reviewOn: '2026-10-01', activityAt: '2025-10-01T10:00:00Z', effect: '<script>Selected synthetic note</script>', holds: [] } as unknown as AdministratorPrivacyItem;
const overview = { customerId: id, items: [item], observedAt: new Date(now).toISOString() };
const preview = { id: other, customerId: id, selected: [item], retained: [{ ...item, scope: { kind: 'FINANCIAL', id }, effect: 'Retain financial facts' }], reason: 'Synthetic erasure reason', digest: 'a'.repeat(64), expiresAt: new Date(now + 30000).toISOString(), version: 1 };
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };
describe('Reviewed erasure', () => {
  let http: HttpTestingController, router: Router, route: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let fixture: ReturnType<typeof TestBed.createComponent<ErasureComponent>>, view: ErasureComponent;
  beforeEach(async () => {
    vi.useFakeTimers(); vi.setSystemTime(now); route = new BehaviorSubject(convertToParamMap({ id }));
    TestBed.configureTestingModule({ imports: [ErasureComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([]), { provide: ActivatedRoute, useValue: { paramMap: route.asObservable() } }] });
    http = TestBed.inject(HttpTestingController); router = TestBed.inject(Router); fixture = TestBed.createComponent(ErasureComponent); view = fixture.componentInstance; fixture.detectChanges(); http.expectOne(base + '/privacy-review').flush(overview); await settle(); fixture.detectChanges();
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });
  async function post(url = base + '/erasures') { await settle(); http.match('/api/v1/session/bootstrap').forEach(r => r.flush(session)); await settle(); return http.expectOne(url); }
  async function prepared() { view.toggle(item); view.reason.setValue(preview.reason); const p = view.prepare(); (await post()).flush({ erasureId: other, version: 1 }); await settle(); http.expectOne(read).flush(preview); await p; fixture.detectChanges(); }
  it('requires selected records and renders selected and retained effects as escaped text', async () => {
    await view.prepare(); http.expectNone(base + '/erasures'); view.toggle(item); view.toggle(item); expect(view.selection()).toEqual([]); view.toggle(item);
    view.reason.setValue('bad'); await view.prepare(); expect(view.message()).toContain('10–500'); view.reason.setValue(' Synthetic reason ');
    const storage = vi.spyOn(Storage.prototype, 'setItem'); const p = view.prepare(); const request = await post(); expect(JSON.parse(request.request.body)).toEqual({ reason: 'Synthetic reason', scopes: [item.scope] }); request.flush({ erasureId: other, version: 1 }); await settle(); http.expectOne(read).flush(preview); await p; fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('script')).toBeNull(); expect(fixture.nativeElement.textContent).toContain('<script>Selected synthetic note</script>'); expect(fixture.nativeElement.textContent).toContain('Retain financial facts'); expect(storage).not.toHaveBeenCalled();
    await view.confirm(); http.expectNone(read + '/confirm'); view.toggle(item); expect(view.selection()).toHaveLength(1); await view.prepare();
  });
  it('confirms only the exact reviewed digest and version, then clears personal drafts', async () => {
    await prepared(); view.reviewed.setValue(true); const done = view.confirm(); const request = await post(read + '/confirm'); expect(request.request.headers.get('If-Match')).toBe('"1"'); expect(JSON.parse(request.request.body)).toEqual({ digest: preview.digest, confirmed: true }); request.flush({ erasureId: other, version: 2 }); await done;
    expect(view.preview()).toBeNull(); expect(view.overview()).toBeNull(); expect(view.reason.value).toBe(''); expect(view.message()).toContain('erasure applied'); await view.confirm(); await view.retry();
  });
  it('retains the same body and key after uncertainty, freezing other changes', async () => {
    view.toggle(item); view.reason.setValue(preview.reason); const p = view.prepare(); const first = await post(), body = first.request.body, key = first.request.headers.get('Idempotency-Key'); first.flush({}, { status: 503, statusText: 'x' }); await p; expect(view.uncertain()).toBe(true); view.reason.setValue('Changed synthetic reason'); view.toggle(item); await view.prepare(); await view.load(); await view.hold(item); await view.release(id); http.expectNone(base + '/erasures');
    const retry = view.retry(); const request = await post(); expect(request.request.body).toBe(body); expect(request.request.headers.get('Idempotency-Key')).toBe(key); request.flush({ erasureId: other, version: 1 }); await settle(); http.expectOne(read).flush(preview); await retry; expect(view.uncertain()).toBe(false);
  });
  it('requires exact-record hold review and reason before creating and releasing holds', async () => {
    await view.hold(item); await view.release(other); http.expectNone(base + '/privacy-holds'); view.holdReviewed.setValue(true); await view.hold(item); await view.release(other); view.reason.setValue(preview.reason); view.through.setValue('2026-11-01'); const hold = view.hold(item); const request = await post(base + '/privacy-holds'); expect(JSON.parse(request.request.body)).toEqual({ kind: 'NOTE', targetId: other, through: '2026-11-01', reason: preview.reason, confirmed: true }); request.flush({ erasureId: other, version: 1 }); await hold; expect(view.message()).toContain('hold updated');
    view.holdReviewed.setValue(true); view.reason.setValue(preview.reason); const release = view.release(other); const r = await post('/api/v1/admin/privacy-holds/' + other + '/release'); expect(JSON.parse(r.request.body)).toEqual({ reason: preview.reason, confirmed: true }); r.flush({ erasureId: other, version: 2 }); await release;
  });
  it('does not select held or financial records and supports an open-ended hold', async () => {
    view.toggle({ ...item, holds: [id] }); view.toggle({ ...item, scope: { ...item.scope, kind: 'FINANCIAL' as typeof item.scope.kind } }); expect(view.selection()).toEqual([]);
    view.holdReviewed.setValue(true); view.reason.setValue(preview.reason); const hold = view.hold(item); const request = await post(base + '/privacy-holds'); expect(JSON.parse(request.request.body).through).toBeNull(); request.flush({ erasureId: other, version: 1 }); await hold;
  });
  it('clears one-hour previews on expiry and permits revising before confirmation', async () => { await prepared(); view.edit(); expect(view.preview()).toBeNull(); expect(view.reviewed.value).toBe(false); view.selection.set([]); await prepared(); await vi.advanceTimersByTimeAsync(30000); expect(view.preview()).toBeNull(); expect(view.selection()).toEqual([]); expect(view.reason.value).toBe(''); expect(view.message()).toContain('expired'); });
  it.each(['route', 'session', 'destroy'])('discards late mutations on %s invalidation', async event => {
    view.toggle(item); view.reason.setValue(preview.reason); const p = view.prepare(); const request = await post(); if (event === 'route') route.next(convertToParamMap({ id: 'bad' })); else if (event === 'session') TestBed.inject(SessionAccess).invalidate(); else fixture.destroy(); request.flush({ erasureId: other, version: 1 }); await p; http.expectNone(read); expect(view.preview()).toBeNull(); await view.retry();
  });
  it.each([400, 404, 412])('discards rejected review %i and sanitizes provider diagnostics', async status => { await prepared(); view.reviewed.setValue(true); const done = view.confirm(); (await post(read + '/confirm')).flush({ detail: 'synthetic provider error' }, { status, statusText: 'x' }); await done; expect(view.preview()).toBeNull(); expect(view.uncertain()).toBe(false); expect(view.message()).not.toContain('provider'); });
  it('does not replay preparation when the committed preview cannot be read', async () => {
    view.toggle(item); view.reason.setValue(preview.reason); const p = view.prepare(); (await post()).flush({ erasureId: other, version: 1 }); await settle(); http.expectOne(read).flush({}, { status: 503, statusText: 'x' }); await p; expect(view.uncertain()).toBe(false); await view.retry(); http.expectNone(base + '/erasures');
  });
  it('returns through fresh MFA and drops drafts on session expiry', async () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true); view.toggle(item); view.reason.setValue(preview.reason); const p = view.prepare(); (await post()).flush({}, { status: 403, statusText: 'x' }); await p; expect(navigate).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/customers/' + id + '/erasure' } }); expect(view.reason.value).toBe('');
    const url = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true); route.next(convertToParamMap({ id })); http.expectOne(base + '/privacy-review').flush({}, { status: 401, statusText: 'x' }); await settle(); expect(url).toHaveBeenCalledWith('/admin/login');
  });
});
