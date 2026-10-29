import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { provideApi } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { CorrectionsComponent } from './corrections.component';
const id = '30000000-0000-4000-8000-000000000001', other = '30000000-0000-4000-8000-000000000002';
const path = '/api/v1/admin/customers/' + id + '/corrections/profile/' + id, read = '/api/v1/admin/corrections/' + other;
const now = Date.parse('2026-10-29T10:00:00Z');
const session = { role: 'ADMIN', csrfToken: 'x'.repeat(43), idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() };
const target = { kind: 'PROFILE', targetId: id, version: 1, values: { firstName: 'Original', lastName: 'Synthetic', telephone: '+32400123456' } };
const preview = { id: other, customerId: id, before: target, after: { ...target.values, firstName: '<script>Corrected</script>' }, reason: 'Synthetic correction reason', digest: 'a'.repeat(64), expiresAt: new Date(now + 30000).toISOString(), version: 1 };
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };
describe('Reviewed customer corrections', () => {
  let http: HttpTestingController, router: Router, route: BehaviorSubject<ReturnType<typeof convertToParamMap>>, query: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let fixture: ReturnType<typeof TestBed.createComponent<CorrectionsComponent>>, view: CorrectionsComponent;
  beforeEach(async () => {
    vi.useFakeTimers(); vi.setSystemTime(now); route = new BehaviorSubject(convertToParamMap({ id })); query = new BehaviorSubject(convertToParamMap({}));
    TestBed.configureTestingModule({ imports: [CorrectionsComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([]), { provide: ActivatedRoute, useValue: { paramMap: route.asObservable(), queryParamMap: query.asObservable() } }] });
    http = TestBed.inject(HttpTestingController); router = TestBed.inject(Router); fixture = TestBed.createComponent(CorrectionsComponent); view = fixture.componentInstance; fixture.detectChanges(); http.expectOne(path).flush(target); await settle(); fixture.detectChanges();
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });
  async function post(url = path) { await settle(); http.match('/api/v1/session/bootstrap').forEach(r => r.flush(session)); await settle(); return http.expectOne(url); }
  async function prepared() { view.form.controls.firstName.setValue('Corrected'); view.form.controls.reason.setValue(preview.reason); const p = view.prepare(); (await post()).flush({ correctionId: other, version: 1 }); await settle(); http.expectOne(read).flush(preview); await p; fixture.detectChanges(); }
  it('normalizes a typed proposal and renders an escaped before/after review without persistence', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'); view.form.controls.firstName.setValue(' Cafe\u0301 '); view.form.controls.reason.setValue(' Synthetic reason ');
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); const request = await post(); expect(JSON.parse(request.request.body)).toEqual({ reason: 'Synthetic reason', values: { firstName: 'Café', lastName: 'Synthetic', telephone: '+32400123456' } }); expect(request.request.headers.has('X-CSRF-Token')).toBe(true); expect(request.request.headers.has('If-Match')).toBe(false); request.flush({ correctionId: other, version: 1 }); await settle(); http.expectOne(read).flush(preview); await settle(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('script')).toBeNull(); expect(fixture.nativeElement.querySelector('table').textContent).toContain('<script>Corrected</script>'); expect(storage).not.toHaveBeenCalled(); await view.confirm(); http.expectNone(read + '/confirm'); await view.prepare(); http.expectNone(path);
  });
  it('requires explicit review and exact preview version then clears all private content', async () => {
    await prepared(); view.reviewed.setValue(true); const done = view.confirm(); const request = await post(read + '/confirm'); expect(request.request.headers.get('If-Match')).toBe('"1"'); expect(JSON.parse(request.request.body)).toEqual({ digest: preview.digest, confirmed: true }); request.flush({ correctionId: other, version: 2 }); await done; expect(view.preview()).toBeNull(); expect(view.target()).toBeNull(); expect(view.form.controls.firstName.value).toBe(''); expect(view.message()).toContain('revoked'); await view.confirm(); await view.prepare(); await view.retry();
  });
  it('retains the exact command after uncertainty and throttle even if fields change', async () => {
    view.form.controls.reason.setValue(preview.reason); const p = view.prepare(); const first = await post(), body = first.request.body, key = first.request.headers.get('Idempotency-Key'); first.flush({}, { status: 503, statusText: 'x' }); await p; expect(view.uncertain()).toBe(true); view.form.controls.firstName.setValue('Changed'); await view.prepare(); await view.select(other); await view.loadNotes(false); http.expectNone(path);
    const retry = view.retry(); const throttled = await post(); expect(throttled.request.body).toBe(body); expect(throttled.request.headers.get('Idempotency-Key')).toBe(key); throttled.flush({}, { status: 429, statusText: 'x', headers: { 'Retry-After': '2' } }); await retry; await view.retry(); http.expectNone(path); await vi.advanceTimersByTimeAsync(2001);
    const last = view.retry(); (await post()).flush({ correctionId: other, version: 1 }); await settle(); http.expectOne(read).flush(preview); await last; expect(view.uncertain()).toBe(false);
  });
  it('clears expired previews, draft values and review confirmation', async () => { await prepared(); view.reviewed.setValue(true); await vi.advanceTimersByTimeAsync(30000); expect(view.preview()).toBeNull(); expect(view.reviewed.value).toBe(false); expect(view.form.controls.reason.value).toBe(''); expect(view.message()).toContain('expired'); });
  it('can revise a proposal without applying the old preview', async () => { await prepared(); view.edit(); expect(view.preview()).toBeNull(); expect(view.form.controls.firstName.value).toBe('Corrected'); expect(view.reviewed.value).toBe(false); await view.confirm(); });
  it.each(['route', 'session', 'destroy'])('ignores late command results after %s invalidation', async event => {
    view.form.controls.reason.setValue(preview.reason); const p = view.prepare(); const request = await post();
    if (event === 'route') route.next(convertToParamMap({ id: 'bad' })); else if (event === 'session') TestBed.inject(SessionAccess).invalidate(); else fixture.destroy();
    request.flush({ correctionId: other, version: 1 }); await p; http.expectNone(read); expect(view.preview()).toBeNull(); expect(view.form.controls.reason.value).toBe(''); await view.retry();
  });
  it('ignores a late preview and target response after navigation', async () => {
    view.form.controls.reason.setValue(preview.reason); const p = view.prepare(); (await post()).flush({ correctionId: other, version: 1 }); await settle(); const late = http.expectOne(read); route.next(convertToParamMap({ id: 'bad' })); late.flush(preview); await p; expect(view.preview()).toBeNull();
    route.next(convertToParamMap({ id })); const targetRead = http.expectOne(path); route.next(convertToParamMap({ id: 'bad' })); targetRead.flush(target); await settle(); expect(view.target()).toBeNull();
  });
  it('does not replay a committed preparation when its preview read fails', async () => {
    view.form.controls.reason.setValue(preview.reason); const p = view.prepare(); (await post()).flush({ correctionId: other, version: 1 }); await settle(); http.expectOne(read).flush({ detail: 'synthetic private provider' }, { status: 503, statusText: 'x' }); await p; expect(view.uncertain()).toBe(false); expect(view.preview()).toBeNull(); expect(view.message()).not.toContain('provider');
  });
  it.each([404, 412, 400])('safely handles rejected confirmation %i', async status => { await prepared(); view.reviewed.setValue(true); const p = view.confirm(); (await post(read + '/confirm')).flush({ detail: 'synthetic private provider' }, { status, statusText: 'x' }); await p; expect(view.preview()).toBeNull(); expect(view.uncertain()).toBe(false); expect(view.message()).not.toContain('provider'); await view.retry(); });
  it('requires fresh MFA and clears drafts when the session expires', async () => {
    const navigate = vi.spyOn(router, 'navigate'); view.form.controls.reason.setValue(preview.reason); const p = view.prepare(); (await post()).flush({ code: 'AUTHENTICATION_REQUIRED' }, { status: 403, statusText: 'x' }); await p; expect(navigate).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/customers/' + id + '/corrections' } }); expect(view.form.controls.reason.value).toBe('');
    route.next(convertToParamMap({ id })); const login = vi.spyOn(router, 'navigateByUrl'); http.expectOne(path).flush({}, { status: 401, statusText: 'x' }); await settle(); expect(login).toHaveBeenCalledWith('/admin/login'); await view.select(id); await view.prepare(); await view.loadNotes(false);
  });
  it.each(['short', 'x'.repeat(501), 'bad\nreason', 'bad\ud800reason'])('rejects invalid reasons before sending (%s)', async reason => { view.form.controls.reason.setValue(reason); await view.prepare(); http.expectNone(path); expect(view.message()).toContain('10–500'); });
  it('rejects invalid correction characters and invalid route selectors', async () => {
    view.form.controls.reason.setValue(preview.reason); view.form.controls.firstName.setValue('bad\u0001'); await view.prepare(); expect(view.message()).toContain('valid correction');
    query.next(convertToParamMap({ kind: 'wrong' })); expect(view.target()).toBeNull(); expect(view.message()).toContain('Choose'); query.next(convertToParamMap({ kind: 'INTAKE', booking: 'bad' })); http.expectNone(path);
  });
  it('loads current intake into the typed date and time controls', async () => {
    query.next(convertToParamMap({ kind: 'INTAKE', booking: other })); const values = { ...target.values, birthDate: '1990-01-01', birthTime: '10:30', birthplace: 'Synthetic city', message: 'Synthetic intake' }; http.expectOne('/api/v1/admin/customers/' + id + '/corrections/intake/' + other).flush({ kind: 'INTAKE', targetId: other, version: 2, values }); await settle(); fixture.detectChanges(); expect(fixture.nativeElement.querySelector('#correction-birthDate').type).toBe('date'); expect(fixture.nativeElement.querySelector('#correction-birthTime').type).toBe('time'); expect(view.form.controls.message.value).toBe('Synthetic intake'); expect(view.fields()).toHaveLength(7);
  });
  it('pages private notes, selects one for correction and ignores late note pages', async () => {
    query.next(convertToParamMap({ kind: 'NOTE', booking: other })); const notesPath = '/api/v1/admin/bookings/' + other + '/notes'; http.expectOne(notesPath + '?limit=20').flush({ entries: [{ id: other, text: '<b>Synthetic note</b>', createdAt: new Date(now).toISOString() }], nextCursor: 'older' }); await settle(); fixture.detectChanges(); expect(fixture.nativeElement.querySelector('article b')).toBeNull();
    const select = view.select(other); const notePath = '/api/v1/admin/customers/' + id + '/corrections/note/' + other; http.expectOne(notePath).flush({ kind: 'NOTE', targetId: other, version: 1, values: { text: '<b>Synthetic note</b>' } }); await select; fixture.detectChanges(); expect(view.fields()).toEqual(['text']); expect(view.form.controls.text.value).toContain('Synthetic');
    const older = view.loadNotes(true); http.expectOne(notesPath + '?limit=20&cursor=older').flush({ entries: [], nextCursor: null }); await older; expect(view.message()).toContain('No notes'); await view.loadNotes(true);
    const refresh = view.loadNotes(false); const late = http.expectOne(notesPath + '?limit=20'); route.next(convertToParamMap({ id: 'bad' })); late.flush({ entries: [{ id: other, text: 'Late', createdAt: new Date(now).toISOString() }], nextCursor: null }); await refresh; expect(view.notes()).toEqual([]);
  });
  it.each(['NOTE', 'INTAKE'])('preserves the selected %s correction across fresh MFA', async kind => {
    const navigate = vi.spyOn(router, 'navigate'); query.next(convertToParamMap({ kind, booking: other }));
    http.expectOne(kind === 'NOTE' ? '/api/v1/admin/bookings/' + other + '/notes?limit=20' : '/api/v1/admin/customers/' + id + '/corrections/intake/' + other).flush({ code: 'AUTHENTICATION_REQUIRED' }, { status: 403, statusText: 'x' }); await settle();
    expect(navigate).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/customers/' + id + '/corrections?kind=' + kind + '&booking=' + other } });
  });
  it('handles note-page failures without exposing provider details', async () => { query.next(convertToParamMap({ kind: 'NOTE', booking: other })); http.expectOne('/api/v1/admin/bookings/' + other + '/notes?limit=20').flush({ detail: 'synthetic provider' }, { status: 503, statusText: 'x' }); await settle(); expect(view.busy()).toBe(false); expect(view.message()).not.toContain('provider'); });
});
