import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { provideApi } from '../shared/generated';
import { CommandAccess } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { HistoryComponent } from './history.component';

const id = '30000000-0000-4000-8000-000000000001', other = '30000000-0000-4000-8000-000000000002';
const path = '/api/v1/admin/bookings/' + id + '/notes', auditPath = '/api/v1/admin/audit';
const now = Date.parse('2026-10-28T10:00:00Z');
const session = { role: 'ADMIN', csrfToken: 'x'.repeat(43), idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() };
const notes = { entries: [{ id: 'note', text: '<b>Synthetic private note</b>\nSecond line', createdAt: new Date(now).toISOString() }], nextCursor: 'note_cursor', serverNow: new Date(now).toISOString() };
const audit = { entries: [{ id: 'event', bookingId: id, actorKind: 'ADMIN', type: 'NOTE_ADDED', reason: '<b>Synthetic reason</b>', occurredAt: new Date(now).toISOString(), version: 1 }], nextCursor: 'audit_cursor', serverNow: new Date(now).toISOString() };
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };
describe('Administrator private history', () => {
  let http: HttpTestingController, router: Router, route: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let fixture: ReturnType<typeof TestBed.createComponent<HistoryComponent>>, view: HistoryComponent;
  const create = () => { fixture = TestBed.createComponent(HistoryComponent); view = fixture.componentInstance; fixture.detectChanges(); };
  async function flushPages(booking: string | null = id) { if (booking) http.expectOne(r => r.url === '/api/v1/admin/bookings/' + booking + '/notes').flush(notes); http.expectOne(r => r.url === auditPath).flush(audit); await settle(); fixture.detectChanges(); }
  async function loaded(booking: string | null = id) { route.next(convertToParamMap(booking ? { id: booking } : {})); create(); await flushPages(booking); }
  async function post() { await settle(); http.match('/api/v1/session/bootstrap').forEach(r => r.flush(session)); await settle(); return http.expectOne(path); }
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now); route = new BehaviorSubject(convertToParamMap({ id }));
    TestBed.configureTestingModule({ imports: [HistoryComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([]), { provide: ActivatedRoute, useValue: { paramMap: route.asObservable() } }] });
    http = TestBed.inject(HttpTestingController); router = TestBed.inject(Router);
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });
  it('associates the free-text field with non-medical privacy guidance before entry', async () => {
    await loaded();
    const field = fixture.nativeElement.querySelector('textarea#private-note');
    expect(field).not.toBeNull(); expect(field.getAttribute('aria-describedby')).toBe('note-privacy-guidance');
    const guidance = fixture.nativeElement.querySelector('#note-privacy-guidance');
    expect(guidance.textContent).toContain('Do not enter medical information or other sensitive personal information');
    expect(guidance.textContent).toContain('only details needed');
  });
  it('renders private text safely and pages each bounded stream with its unchanged cursor', async () => {
    await loaded(); expect(fixture.nativeElement.textContent).toContain('<b>Synthetic private note</b>'); expect(fixture.nativeElement.querySelector('article b')).toBeNull();
    const p = view.older('notes'); const r = http.expectOne(q => q.url === path); expect(r.request.params.get('cursor')).toBe('note_cursor'); expect(r.request.params.get('limit')).toBe('20'); r.flush({ ...notes, entries: [], nextCursor: null }); await p;
    expect(view.notes()).toEqual([]); await view.older('notes');
    const a = view.older('audit'); const ar = http.expectOne(q => q.url === auditPath); expect(ar.request.params.get('bookingId')).toBe(id); expect(ar.request.params.get('cursor')).toBe('audit_cursor'); ar.flush({ ...audit, entries: [], nextCursor: null }); await a; fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No notes on this page'); expect(fixture.nativeElement.textContent).toContain('No audit events'); await view.older('audit');
  });
  it('global audit omits booking filter and never exposes a note form', async () => {
    route.next(convertToParamMap({})); create(); const r = http.expectOne(q => q.url === auditPath); expect(r.request.params.has('bookingId')).toBe(false); r.flush(audit); await settle(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('textarea')).toBeNull(); expect(fixture.nativeElement.textContent).toContain('Open booking history'); await view.submit(); await view.older('notes');
    const p = view.older('audit'); const next = http.expectOne(q => q.url === auditPath); expect(next.request.params.has('bookingId')).toBe(false); next.flush({ ...audit, nextCursor: null }); await p;
  });
  it('submits a normalized multiline note with its own key and no booking version then refreshes', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'); await loaded(); view.form.controls.text.setValue(' Synthetic cafe\u0301\nprivate note ');
    const p = view.submit(); const r = await post(); expect(JSON.parse(r.request.body)).toEqual({ text: 'Synthetic café\nprivate note' }); expect(r.request.headers.has('If-Match')).toBe(false); expect(r.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    await view.submit(); await view.load(); await view.older('notes'); await view.retry(); r.flush({ noteId: 'note', version: 1 }); await settle(); await flushPages(); await p;
    expect(view.form.controls.text.value).toBe(''); expect(view.message()).toContain('Private note added'); expect(storage).not.toHaveBeenCalled();
  });
  it.each(['', ' \n ', 'x'.repeat(4001), '😀'.repeat(4001), 'bad\u0000note', 'bad\u0085note', 'bad\ud800note'])('rejects invalid private text without sending it', async text => {
    await loaded(); view.form.controls.text.setValue(text); await view.submit(); expect(view.message()).toContain('1–4000'); http.expectNone(path);
  });
  it('accepts 4000 supplementary characters and handles safe validation rejection', async () => {
    await loaded(); view.form.controls.text.setValue('😀'.repeat(4000)); const p = view.submit(); const r = await post(); expect([...JSON.parse(r.request.body).text]).toHaveLength(4000); r.flush({ code: 'INVALID_REQUEST', detail: 'private-provider-error' }, { status: 400, statusText: 'x' }); await p; expect(view.message()).toContain('could not be added'); expect(view.message()).not.toContain('provider');
  });
  it('freezes original body and key through uncertainty and throttling until explicit retry succeeds', async () => {
    await loaded(); view.form.controls.text.setValue('Synthetic original'); const p = view.submit(); const r = await post(); const body = r.request.body, key = r.request.headers.get('Idempotency-Key'); r.flush({}, { status: 503, statusText: 'x' }); await p;
    expect(view.uncertain()).toBe(true); view.form.controls.text.setValue('Changed draft'); await view.submit(); await view.load(); await view.older('audit');
    const retry = view.retry(); const limited = await post(); limited.flush({}, { status: 429, statusText: 'x', headers: { 'Retry-After': '2' } }); await retry; await view.retry(); http.expectNone(path); expect(view.uncertain()).toBe(true);
    await vi.advanceTimersByTimeAsync(2001); const final = view.retry(); const success = await post(); expect(success.request.body).toBe(body); expect(success.request.headers.get('Idempotency-Key')).toBe(key); success.flush({ noteId: 'note', version: 1 }); await settle(); await flushPages(); await final; expect(view.uncertain()).toBe(false); await view.retry();
  });
  it('retains a first throttled command and handles a definite conflict without leaking details', async () => {
    await loaded(); view.form.controls.text.setValue('Synthetic note'); const p = view.submit(); (await post()).flush({}, { status: 429, statusText: 'x', headers: { 'Retry-After': '1' } }); await p; expect(view.uncertain()).toBe(true);
    await vi.advanceTimersByTimeAsync(1001); const retry = view.retry(); (await post()).flush({ code: 'IDEMPOTENCY_KEY_REUSED' }, { status: 409, statusText: 'x' }); await retry; expect(view.uncertain()).toBe(false); expect(view.message()).toContain('Review it');
  });
  it('preserves successful evidence if refresh fails and prevents another note until history reloads', async () => {
    await loaded(); view.form.controls.text.setValue('Synthetic note'); const p = view.submit(); (await post()).flush({ noteId: 'note', version: 1 }); await settle(); http.expectOne(q => q.url === path).flush({}, { status: 503, statusText: 'x' }); http.expectOne(q => q.url === auditPath).flush(audit); await p;
    expect(view.message()).toContain('Private note added, but'); expect(view.loaded()).toBe(false); expect(view.form.controls.text.value).toBe(''); await view.submit();
    const refresh = view.load(); await flushPages(); await refresh; expect(view.loaded()).toBe(true);
  });
  it('clears private state and returns to this booking after fresh MFA is required', async () => {
    const nav = vi.spyOn(router, 'navigate'); await loaded(); view.form.controls.text.setValue('Synthetic note'); const p = view.submit(); (await post()).flush({ code: 'AUTHENTICATION_REQUIRED' }, { status: 403, statusText: 'x' }); await p;
    expect(view.notes()).toEqual([]); expect(view.events()).toEqual([]); expect(view.form.controls.text.value).toBe(''); expect(nav).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/bookings/' + id + '/history' } });
  });
  it.each([401, 403, 404, 503])('handles failed global reads safely (%s)', async status => {
    const nav = vi.spyOn(router, 'navigate'); vi.spyOn(router, 'navigateByUrl').mockResolvedValue(false); route.next(convertToParamMap({})); create(); http.expectOne(q => q.url === auditPath).flush({ code: status === 401 ? 'SESSION_REQUIRED' : 'BAD', detail: 'private-provider' }, { status, statusText: 'x' }); await settle();
    expect(view.loaded()).toBe(false); expect(view.message()).not.toContain('provider'); if (status === 403) expect(nav).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/audit' } });
  });
  it('keeps the current page after a pagination failure so it can be retried', async () => {
    await loaded(); const p = view.older('notes'); http.expectOne(q => q.url === path).flush({}, { status: 503, statusText: 'x' }); await p; expect(view.notes()).toHaveLength(1); expect(view.noteCursor()).toBe('note_cursor'); expect(view.message()).toContain('Try again');
  });
  it('ignores old route results and clears drafts when a reused component changes booking', async () => {
    create(); const oldNotes = http.expectOne(q => q.url === path), oldAudit = http.expectOne(q => q.url === auditPath); view.form.controls.text.setValue('Old private draft'); route.next(convertToParamMap({ id: other }));
    oldNotes.flush(notes); oldAudit.flush(audit); await settle(); expect(view.notes()).toEqual([]); expect(view.form.controls.text.value).toBe(''); expect(view.busy()).toBe(true); await flushPages(other); expect(view.id()).toBe(other);
  });
  it('ignores an old command and its refresh after a route change', async () => {
    await loaded(); view.form.controls.text.setValue('Old private draft'); const p = view.submit(); const old = await post(); route.next(convertToParamMap({ id: other })); old.flush({ noteId: 'old', version: 1 }); await p; await flushPages(other); expect(view.message()).toBe(''); expect(view.form.controls.text.value).toBe('');
  });
  it('ignores the previous booking refresh after a successful command when the route changes', async () => {
    await loaded(); view.form.controls.text.setValue('Old private note'); const p = view.submit(); (await post()).flush({ noteId: 'old', version: 1 }); await settle();
    const oldNotes = http.expectOne(q => q.url === path), oldAudit = http.expectOne(q => q.url === auditPath); route.next(convertToParamMap({ id: other }));
    oldNotes.flush(notes); oldAudit.flush(audit); await p; expect(view.message()).toBe(''); expect(view.busy()).toBe(true); await flushPages(other); expect(view.id()).toBe(other);
  });
  it('session invalidation discards private pages and pending callbacks without repopulation', async () => {
    await loaded(); const p = view.older('audit'); const old = http.expectOne(q => q.url === auditPath); TestBed.inject(SessionAccess).invalidate(); old.flush(audit); await p; expect(view.events()).toEqual([]); expect(view.notes()).toEqual([]); await view.load(); await view.older('audit'); await view.submit(); await view.retry();
  });
  it('ignores late read results after destruction and rejects malformed booking IDs', async () => {
    create(); const n = http.expectOne(q => q.url === path), a = http.expectOne(q => q.url === auditPath); fixture.destroy(); n.flush(notes); a.flush(audit); await settle(); expect(view.notes()).toEqual([]); await view.load();
    route.next(convertToParamMap({ id: 'invalid' })); create(); expect(view.message()).toContain('could not be found'); await view.submit();
  });
  it('handles preparation failure without sending a private note', async () => {
    await loaded(); vi.spyOn(TestBed.inject(CommandAccess), 'prepare').mockImplementation(() => { throw new Error('Synthetic private'); }); view.form.controls.text.setValue('Synthetic note'); await view.submit(); expect(view.message()).toContain('could not be submitted');
  });
  it('submits through the native form binding', async () => {
    await loaded(); const field = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement; field.value = 'Synthetic native note'; field.dispatchEvent(new Event('input')); fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); const r = await post(); expect(JSON.parse(r.request.body).text).toBe('Synthetic native note'); r.flush({}, { status: 400, statusText: 'x' }); await settle();
  });
});
