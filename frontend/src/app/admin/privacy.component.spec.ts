import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { provideApi } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { PrivacyComponent } from './privacy.component';

const id = '30000000-0000-4000-8000-000000000001', exported = '30000000-0000-4000-8000-000000000002';
const path = '/api/v1/admin/customers/' + id + '/exports', read = '/api/v1/admin/exports/' + exported;
const now = Date.parse('2026-10-29T10:00:00Z');
const session = { role: 'ADMIN', csrfToken: 'x'.repeat(43), idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() };
const preview = { id: exported, customerId: id, expiresAt: new Date(now + 3600000).toISOString(), digest: 'a'.repeat(64), released: false, document: '{"note":"<script>synthetic text</script>"}' };
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };

describe('Reviewed customer privacy exports', () => {
  let http: HttpTestingController, router: Router, route: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let fixture: ReturnType<typeof TestBed.createComponent<PrivacyComponent>>, view: PrivacyComponent;
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now); route = new BehaviorSubject(convertToParamMap({ id }));
    vi.stubGlobal('URL', class extends URL { static override createObjectURL = vi.fn(() => 'blob:synthetic'); static override revokeObjectURL = vi.fn(); });
    TestBed.configureTestingModule({ imports: [PrivacyComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([]), { provide: ActivatedRoute, useValue: { paramMap: route.asObservable() } }] });
    http = TestBed.inject(HttpTestingController); router = TestBed.inject(Router); fixture = TestBed.createComponent(PrivacyComponent); view = fixture.componentInstance; fixture.detectChanges();
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); vi.unstubAllGlobals(); } });
  async function post(url = path) { await settle(); http.match('/api/v1/session/bootstrap').forEach(r => r.flush(session)); await settle(); return http.expectOne(url); }
  async function prepared(released = false, expiresAt = preview.expiresAt) { view.reason.setValue('Synthetic reason'); const p = view.prepare(); (await post()).flush({ exportId: exported, version: 1 }); await settle(); http.expectOne(read).flush({ ...preview, released, expiresAt }); await p; fixture.detectChanges(); }
  it('requires a reason, renders private preview as text, and never persists it', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'); await view.prepare(); http.expectNone(path); expect(view.message()).toContain('10–500');
    view.reason.setValue(' Synthetic cafe\u0301 reason '); const p = view.prepare(); const request = await post(); expect(JSON.parse(request.request.body)).toEqual({ reason: 'Synthetic café reason' }); expect(request.request.headers.has('Idempotency-Key')).toBe(true); expect(request.request.headers.has('X-CSRF-Token')).toBe(true); request.flush({ exportId: exported, version: 1 }); await settle(); http.expectOne(read).flush(preview); await p; fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('script')).toBeNull(); expect(fixture.nativeElement.querySelector('pre').textContent).toContain('<script>synthetic text</script>'); expect(view.reviewed.value).toBe(false); expect(storage).not.toHaveBeenCalled();
    await view.release(); await view.download(); http.expectNone(read + '/release'); http.expectNone(read + '/download');
  });
  it('binds explicit review to the preview digest before offering a download', async () => {
    await prepared(); view.reviewed.setValue(true); const release = view.release(); const request = await post(read + '/release'); expect(JSON.parse(request.request.body)).toEqual({ digest: preview.digest, reviewedThirdPartyInformation: true }); request.flush({ exportId: exported, version: 2 }); await settle(); http.expectOne(read).flush({ ...preview, released: true }); await release;
    expect(view.preview()?.released).toBe(true); await view.release();
    const download = view.download(); http.expectOne(read + '/download').flush(new Blob(['{}'])); await download; fixture.detectChanges(); expect(view.url()).toBe('blob:synthetic'); expect(fixture.nativeElement.querySelector('a[download]').getAttribute('download')).toBe('customer-export.json');
    const second = view.download(); http.expectOne(read + '/download').flush(new Blob(['{}'])); await second; expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:synthetic');
  });
  it('retains original body and key on uncertain and throttled retries', async () => {
    view.reason.setValue('Synthetic original reason'); const p = view.prepare(); const request = await post(); const body = request.request.body, key = request.request.headers.get('Idempotency-Key'); request.flush({}, { status: 503, statusText: 'x' }); await p;
    expect(view.uncertain()).toBe(true); view.reason.setValue('Changed reason'); await view.prepare(); await view.release(); await view.download(); http.expectNone(path);
    const retry = view.retry(); const limited = await post(); expect(limited.request.body).toBe(body); expect(limited.request.headers.get('Idempotency-Key')).toBe(key); limited.flush({}, { status: 429, statusText: 'x', headers: { 'Retry-After': '2' } }); await retry; await view.retry(); http.expectNone(path);
    await vi.advanceTimersByTimeAsync(2001); const done = view.retry(); const success = await post(); expect(success.request.body).toBe(body); success.flush({ exportId: exported, version: 1 }); await settle(); http.expectOne(read).flush(preview); await done; expect(view.uncertain()).toBe(false); await view.retry();
  });
  it('coalesces busy actions and clears expired preview and Blob URLs', async () => {
    await prepared(true, new Date(now + 30000).toISOString()); const download = view.download(); await view.download(); await view.prepare(); await view.retry(); http.expectOne(read + '/download').flush(new Blob(['{}'])); await download;
    await vi.advanceTimersByTimeAsync(30000); expect(view.preview()).toBeNull(); expect(view.url()).toBeNull(); expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:synthetic'); expect(view.message()).toContain('expired');
  });
  it.each(['route', 'session', 'destroy'])('ignores late command and preview after %s invalidation', async event => {
    view.reason.setValue('Synthetic reason'); const p = view.prepare(); const request = await post();
    if (event === 'route') route.next(convertToParamMap({ id: exported })); else if (event === 'session') TestBed.inject(SessionAccess).invalidate(); else fixture.destroy();
    request.flush({ exportId: exported, version: 1 }); await p; http.expectNone(read); expect(view.preview()).toBeNull(); expect(view.reason.value).toBe('');
  });
  it('ignores late preview and download responses after route/session changes', async () => {
    view.reason.setValue('Synthetic reason'); const p = view.prepare(); (await post()).flush({ exportId: exported, version: 1 }); await settle(); const old = http.expectOne(read); route.next(convertToParamMap({ id })); old.flush(preview); await p; expect(view.preview()).toBeNull();
    await prepared(true); const download = view.download(); const file = http.expectOne(read + '/download'); TestBed.inject(SessionAccess).invalidate(); file.flush(new Blob(['{}'])); await download; expect(URL.createObjectURL).not.toHaveBeenCalled(); await view.prepare(); await view.download(); await view.retry();
  });
  it('clears a committed preview if its follow-up read fails instead of offering an old release', async () => {
    await prepared(); view.reviewed.setValue(true); const p = view.release(); (await post(read + '/release')).flush({ exportId: exported, version: 2 }); await settle(); http.expectOne(read).flush({ detail: 'synthetic-private-provider' }, { status: 503, statusText: 'x' }); await p; expect(view.preview()).toBeNull(); expect(view.uncertain()).toBe(false); expect(view.message()).not.toContain('provider');
  });
  it.each([404, 412, 400])('handles safe rejection %i without retaining a retry', async status => {
    await prepared(); view.reviewed.setValue(true); const p = view.release(); (await post(read + '/release')).flush({ detail: 'synthetic-private-provider' }, { status, statusText: 'x' }); await p; expect(view.uncertain()).toBe(false); expect(view.message()).not.toContain('provider'); if (status !== 400) expect(view.preview()).toBeNull(); await view.retry();
  });
  it('requires fresh MFA and returns only to the validated customer privacy route', async () => {
    const navigate = vi.spyOn(router, 'navigate'); view.reason.setValue('Synthetic reason'); const p = view.prepare(); (await post()).flush({ code: 'AUTHENTICATION_REQUIRED' }, { status: 403, statusText: 'x' }); await p; expect(view.preview()).toBeNull(); expect(navigate).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/customers/' + id + '/privacy' } });
  });
  it('clears private data and redirects after an expired session', async () => {
    const navigate = vi.spyOn(router, 'navigateByUrl'); await prepared(true); const p = view.download(); http.expectOne(read + '/download').flush(new Blob(['{}']), { status: 401, statusText: 'x' }); await p; expect(view.preview()).toBeNull(); expect(navigate).toHaveBeenCalledWith('/admin/login');
  });
  it.each(['short', 'x'.repeat(501), 'bad\nreason', 'bad\ud800reason'])('rejects invalid reasons before any network call', async reason => { view.reason.setValue(reason); await view.prepare(); http.expectNone(path); });
  it('rejects invalid customer scope and clears a Blob on destroy', async () => {
    route.next(convertToParamMap({ id: 'bad' })); view.reason.setValue('Synthetic reason'); await view.prepare(); http.expectNone(path);
    route.next(convertToParamMap({ id })); await prepared(true); const p = view.download(); http.expectOne(read + '/download').flush(new Blob(['{}'])); await p; fixture.destroy(); expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:synthetic');
  });
});
