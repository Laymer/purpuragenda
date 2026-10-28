import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideApi } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { CustomersComponent } from './customers.component';

const id = '30000000-0000-4000-8000-000000000001', other = '30000000-0000-4000-8000-000000000002';
const path = '/api/v1/admin/customers', historyPath = path + '/' + id + '/bookings';
const customer = { id, email: '<b>synthetic</b>@example.test', enrolled: true };
const results = { entries: [customer, { id: other, email: 'guest@example.test', enrolled: false }], nextCursor: other, serverNow: '2026-10-28T12:00:00Z' };
const history = { customer, bookings: [{ id: other, reference: 'SYNTHETIC', state: 'PENDING_CONFIRMATION', startInstant: '2026-11-01T10:00:00Z', sessionEndInstant: '2026-11-01T11:00:00Z', pendingExpired: true, serviceName: '<b>Synthetic service</b>', priceMinor: 1250, currency: 'EUR' }], nextCursor: 'history_cursor', serverNow: results.serverNow };
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };
describe('Administrator customers', () => {
  let http: HttpTestingController, router: Router;
  let fixture: ReturnType<typeof TestBed.createComponent<CustomersComponent>>, view: CustomersComponent;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [CustomersComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([])] });
    http = TestBed.inject(HttpTestingController); router = TestBed.inject(Router); fixture = TestBed.createComponent(CustomersComponent); view = fixture.componentInstance; fixture.detectChanges();
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); } });
  async function search() { view.form.controls.query.setValue('Synthetic'); const p = view.search(); http.expectOne(r => r.url === path).flush(results); await p; fixture.detectChanges(); }
  async function open() { const p = view.open(id); http.expectOne(historyPath + '?limit=20').flush(history); await p; fixture.detectChanges(); }
  it('does not search on entry or typing and submits normalized text without persistent storage', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'); http.expectNone(r => r.url === path); view.form.controls.query.setValue('  Cafe\u0301  '); fixture.detectChanges(); http.expectNone(r => r.url === path);
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); const r = http.expectOne(q => q.url === path); expect(new URL(r.request.urlWithParams, 'https://synthetic.example.test').searchParams.get('query')).toBe('Café'); expect(r.request.params.get('limit')).toBe('20'); expect(r.request.params.has('cursor')).toBe(false); r.flush(results); await settle(); fixture.detectChanges();
    expect(view.customers()).toHaveLength(2); expect(fixture.nativeElement.textContent).toContain('<b>synthetic</b>@example.test'); expect(fixture.nativeElement.querySelector('li b')).toBeNull(); expect(fixture.nativeElement.textContent).toContain('Guest customer'); expect(fixture.nativeElement.textContent).toContain('Account enrolled'); expect(storage).not.toHaveBeenCalled(); expect(router.url).toBe('/');
  });
  it.each(['', 'x', '  ', 'x'.repeat(255), '😀'.repeat(255), 'bad\nquery', 'bad\u0085query', 'bad\ud800query'])('rejects invalid search text (%s)', async query => {
    view.form.controls.query.setValue(query); await view.search(); expect(view.message()).toContain('2–254'); http.expectNone(r => r.url === path); expect(document.activeElement?.id).toBe('customer-message');
  });
  it('accepts the maximum supplementary Unicode query and handles an empty page', async () => {
    view.form.controls.query.setValue('😀'.repeat(254)); const p = view.search(); const r = http.expectOne(q => q.url === path); expect([...(new URL(r.request.urlWithParams, 'https://synthetic.example.test').searchParams.get('query') ?? '')]).toHaveLength(254); r.flush({ ...results, entries: [], nextCursor: null }); await p; fixture.detectChanges(); expect(fixture.nativeElement.textContent).toContain('No customers on this page'); await view.moreCustomers(); await view.moreBookings(); await view.open(id);
  });
  it('uses the submitted query for pagination even after editing the form and clears previous selection', async () => {
    await search(); await open(); view.form.controls.query.setValue('Different draft'); const p = view.moreCustomers(); const r = http.expectOne(q => q.url === path); expect(r.request.params.get('query')).toBe('Synthetic'); expect(r.request.params.get('cursor')).toBe(other); r.flush({ ...results, entries: [], nextCursor: null }); await p;
    expect(view.selected()).toBeNull(); expect(view.bookings()).toEqual([]); expect(view.bookingCursor()).toBeNull(); await view.moreCustomers();
  });
  it('shows immutable booking summaries and passes history cursors unchanged', async () => {
    await search(); await open(); expect(fixture.nativeElement.textContent).toContain('Europe/Brussels'); expect(fixture.nativeElement.textContent).toContain('12.50 EUR'); expect(fixture.nativeElement.textContent).toContain('The hold has expired'); expect(fixture.nativeElement.querySelector('li b')).toBeNull();
    const links = [...fixture.nativeElement.querySelectorAll('a')].map((a: unknown) => (a as HTMLAnchorElement).getAttribute('href')); expect(links).toContain('/admin/bookings/' + other); expect(links).toContain('/admin/bookings/' + other + '/history');
    const p = view.moreBookings(); const r = http.expectOne(q => q.url === historyPath); expect(r.request.params.get('cursor')).toBe('history_cursor'); expect(r.request.params.get('limit')).toBe('20'); r.flush({ ...history, bookings: [], nextCursor: null }); await p; fixture.detectChanges(); expect(fixture.nativeElement.textContent).toContain('No bookings on this page'); await view.moreBookings();
    const refresh = view.open(id); const newest = http.expectOne(q => q.url === historyPath); expect(newest.request.params.has('cursor')).toBe(false); newest.flush(history); await refresh;
  });
  it('prevents overlapping searches and customer/history page requests', async () => {
    await search(); const p = view.open(id); await view.open(other); await view.search(); await view.moreCustomers(); await view.moreBookings(); http.expectOne(q => q.url === historyPath).flush(history); await p;
    const next = view.moreBookings(); await view.moreBookings(); await view.open(id); http.expectOne(q => q.url === historyPath).flush(history); await next;
  });
  it('clears old results and booking data before a new search', async () => {
    await search(); await open(); view.form.controls.query.setValue('Another'); const p = view.search(); expect(view.customers()).toEqual([]); expect(view.selected()).toBeNull(); expect(view.searched()).toBe(false); http.expectOne(q => q.url === path).flush({ ...results, entries: [], nextCursor: null }); await p;
  });
  it.each([400, 404, 429, 503])('handles safe read failure without provider details (%s)', async status => {
    view.form.controls.query.setValue('Synthetic'); const p = view.search(); http.expectOne(r => r.url === path).flush({ detail: 'private-provider' }, { status, statusText: 'x' }); await p;
    expect(view.searched()).toBe(false); expect(view.message()).not.toContain('provider'); expect(view.message()).toContain(status === 404 ? 'could not be found' : status === 429 ? 'Please wait' : 'could not be loaded'); expect(view.busy()).toBe(false);
  });
  it('retains each successful page and cursor when pagination fails for explicit retry', async () => {
    await search(); const p = view.moreCustomers(); http.expectOne(q => q.url === path).flush({}, { status: 503, statusText: 'x' }); await p; expect(view.customers()).toHaveLength(2); expect(view.customerCursor()).toBe(other);
    await open(); const b = view.moreBookings(); http.expectOne(q => q.url === historyPath).flush({}, { status: 503, statusText: 'x' }); await b; expect(view.bookings()).toHaveLength(1); expect(view.bookingCursor()).toBe('history_cursor');
  });
  it('clears private state on session failure and blocks further requests', async () => {
    const nav = vi.spyOn(router, 'navigateByUrl').mockRejectedValue(new Error('synthetic navigation')); await search(); await open(); const p = view.moreBookings(); http.expectOne(q => q.url === historyPath).flush({ code: 'SESSION_REQUIRED' }, { status: 401, statusText: 'x' }); await p;
    expect(nav).toHaveBeenCalledWith('/admin/login'); expect(view.customers()).toEqual([]); expect(view.selected()).toBeNull(); expect(view.bookings()).toEqual([]); expect(view.form.controls.query.value).toBe(''); await view.search(); await view.open(id); await view.moreCustomers(); await view.moreBookings();
  });
  it('returns only to the customer route after administrator reauthentication', async () => {
    const nav = vi.spyOn(router, 'navigate').mockResolvedValue(false); await search(); const p = view.open(id); http.expectOne(q => q.url === historyPath).flush({ code: 'AUTHENTICATION_REQUIRED' }, { status: 403, statusText: 'x' }); await p;
    expect(nav).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/customers' } }); expect(view.customers()).toEqual([]); expect(view.form.controls.query.value).toBe('');
  });
  it('ignores a late search response after session invalidation', async () => {
    view.form.controls.query.setValue('Synthetic'); const p = view.search(); const r = http.expectOne(q => q.url === path); TestBed.inject(SessionAccess).invalidate(); r.flush(results); await p; expect(view.customers()).toEqual([]); expect(view.searched()).toBe(false); expect(view.busy()).toBe(false);
  });
  it('ignores late search errors after destroy and cannot restart', async () => {
    view.form.controls.query.setValue('Synthetic'); const p = view.search(); const r = http.expectOne(q => q.url === path); fixture.destroy(); r.flush({}, { status: 503, statusText: 'x' }); await p; expect(view.message()).toBe(''); expect(view.form.controls.query.value).toBe(''); await view.search();
  });
  it('ignores late history success and failure after invalidation or destroy', async () => {
    await search(); const p = view.open(id); const r = http.expectOne(q => q.url === historyPath); TestBed.inject(SessionAccess).invalidate(); r.flush(history); await p; expect(view.selected()).toBeNull(); expect(view.bookings()).toEqual([]);
    fixture.destroy(); fixture = TestBed.createComponent(CustomersComponent); view = fixture.componentInstance; fixture.detectChanges(); await search(); const next = view.open(id); const failed = http.expectOne(q => q.url === historyPath); fixture.destroy(); failed.flush({}, { status: 503, statusText: 'x' }); await next; expect(view.message()).toBe('');
  });
});
