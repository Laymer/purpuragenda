import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideApi } from './generated';
import { SessionAccess } from './access/session-access';
import { CalendarActionsComponent } from './calendar-actions.component';

const id = '30000000-0000-4000-8000-000000000001';
const booking = { id, state: 'CONFIRMED', startInstant: '2026-03-29T00:45:00Z', sessionEndInstant: '2026-03-29T01:15:00Z',
  reference: 'synthetic-private-reference', meetingUrl: 'https://zoom.us/j/123456789?pwd=synthetic', intake: 'synthetic-private-intake', email: 'synthetic@example.test' };
const file = () => new Blob(['BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n'], { type: 'text/calendar;charset=UTF-8' });

describe('Private calendar actions', () => {
  let http: HttpTestingController;
  let fixture: ReturnType<typeof TestBed.createComponent<CalendarActionsComponent>>, view: CalendarActionsComponent;
  const create = (owner = false) => {
    fixture = TestBed.createComponent(CalendarActionsComponent); view = fixture.componentInstance;
    fixture.componentRef.setInput('booking', booking); fixture.componentRef.setInput('owner', owner); fixture.detectChanges();
  };
  const route = (owner = false) => '/api/v1/' + (owner ? 'admin/' : '') + 'bookings/' + id + '/calendar';
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [CalendarActionsComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1')] });
    http = TestBed.inject(HttpTestingController);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:synthetic-calendar'); vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
  });
  afterEach(() => { http.verify(); TestBed.resetTestingModule(); vi.restoreAllMocks(); vi.useRealTimers(); });

  it.each([false, true])('downloads only after explicit action using the fixed audience (%s)', async owner => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'); create(owner); http.expectNone(() => true);
    const result = view.download(); await view.download();
    const request = http.expectOne(route(owner)); expect(request.request.method).toBe('GET'); expect(request.request.params.keys()).toEqual([]); expect(request.request.responseType).toBe('blob');
    request.flush(file()); await result; fixture.detectChanges();
    const anchor = fixture.nativeElement.querySelector('a[download]') as HTMLAnchorElement;
    expect(anchor.download).toBe('consultation.ics'); expect(anchor.href).toBe('blob:synthetic-calendar');
    expect(view.message()).toContain('import'); expect(storage).not.toHaveBeenCalled(); expect(view.busy()).toBe(false);
    const again = view.download(); expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:synthetic-calendar'); expect(view.url()).toBeNull();
    http.expectOne(route(owner)).flush(file()); await again;
    fixture.destroy(); expect(URL.revokeObjectURL).toHaveBeenCalledTimes(2);
  });
  it.each([
    ['2026-03-29T01:45:00+01:00', '2026-03-29T03:15:00+02:00', '20260329T004500Z/20260329T011500Z'],
    ['2026-10-25T02:30:00+02:00', '2026-10-25T02:30:00+01:00', '20261025T003000Z/20261025T013000Z'],
  ])('prefills only generic title and actual UTC session times across DST (%s)', (startInstant, sessionEndInstant, dates) => {
    create(); fixture.componentRef.setInput('booking', { ...booking, startInstant, sessionEndInstant }); fixture.detectChanges();
    const anchor = fixture.nativeElement.querySelector('a[target]') as HTMLAnchorElement, url = new URL(anchor.href);
    expect(url.origin + url.pathname).toBe('https://calendar.google.com/calendar/r/eventedit');
    expect([...url.searchParams.entries()]).toEqual([['action', 'TEMPLATE'], ['text', 'Consultation'], ['dates', dates]]);
    expect(anchor.rel).toBe('noopener noreferrer'); expect(anchor.getAttribute('referrerpolicy')).toBe('no-referrer');
    expect(fixture.nativeElement.textContent).toContain('do not update automatically'); expect(fixture.nativeElement.textContent).toContain('duplicates');
    for (const value of [id, booking.reference, booking.meetingUrl, booking.intake, booking.email]) expect(fixture.nativeElement.innerHTML).not.toContain(value);
    http.expectNone(() => true);
  });
  it.each(['PENDING_CONFIRMATION', 'CANCELLED', 'EXPIRED', 'REJECTED', 'COMPLETED', 'NO_SHOW'])('retains ICS fallback without a Google creation action for %s', async state => {
    create(); fixture.componentRef.setInput('booking', { ...booking, state }); fixture.detectChanges();
    expect(view.google()).toBeNull(); expect(fixture.nativeElement.querySelector('a[target]')).toBeNull();
    const result = view.download(); http.expectOne(route()).flush(file()); await result; expect(view.url()).not.toBeNull();
  });
  it.each([['invalid', booking.sessionEndInstant], [booking.startInstant, 'invalid'], [booking.startInstant, booking.startInstant], [booking.sessionEndInstant, booking.startInstant]])('omits invalid Google times (%s, %s)', (startInstant, sessionEndInstant) => {
    create(); fixture.componentRef.setInput('booking', { ...booking, startInstant, sessionEndInstant }); fixture.detectChanges(); expect(view.google()).toBeNull();
  });
  it('clears a prepared copy when the booking or disabled state changes', async () => {
    create(); let result = view.download(); http.expectOne(route()).flush(file()); await result;
    fixture.componentRef.setInput('booking', { ...booking, state: 'CANCELLED' }); fixture.detectChanges(); expect(view.url()).toBeNull(); expect(URL.revokeObjectURL).toHaveBeenCalledTimes(1);
    result = view.download(); http.expectOne(route()).flush(file()); await result;
    fixture.componentRef.setInput('disabled', true); fixture.detectChanges(); await view.download(); http.expectNone(() => true);
    expect(view.url()).toBeNull(); expect(view.google()).toBeNull(); expect(fixture.nativeElement.querySelector('button').disabled).toBe(true);
  });
  it.each(['booking', 'disabled', 'invalidation', 'destroy'])('ignores late downloads after %s', async change => {
    create(); const result = view.download(), request = http.expectOne(route());
    if (change === 'booking') fixture.componentRef.setInput('booking', { ...booking, id: '30000000-0000-4000-8000-000000000002' });
    if (change === 'disabled') fixture.componentRef.setInput('disabled', true);
    if (change === 'invalidation') TestBed.inject(SessionAccess).invalidate();
    if (change === 'destroy') fixture.destroy(); else fixture.detectChanges();
    request.flush(file()); await result; expect(view.url()).toBeNull(); expect(URL.createObjectURL).not.toHaveBeenCalled();
    if (change === 'invalidation' || change === 'destroy') { await view.download(); http.expectNone(() => true); }
  });
  it('revokes a prepared file and hides Google on session invalidation', async () => {
    create(); const result = view.download(); http.expectOne(route()).flush(file()); await result;
    TestBed.inject(SessionAccess).invalidate(); fixture.detectChanges();
    expect(URL.revokeObjectURL).toHaveBeenCalled(); expect(view.url()).toBeNull(); expect(view.google()).toBeNull(); expect(view.message()).toContain('session ended');
    expect(fixture.nativeElement.querySelector('button').disabled).toBe(true);
  });
  it.each([401, 403, 404, 503])('contains download failures without exposing provider text (%s)', async status => {
    create(); const result = view.download(); http.expectOne(route()).flush(new Blob(['synthetic-provider-detail']), { status, statusText: 'x' }); await result;
    expect(view.url()).toBeNull(); expect(view.busy()).toBe(false); expect(view.message()).not.toContain('synthetic-provider-detail');
    expect(view.message()).toContain(status === 401 ? 'session ended' : status === 503 ? 'try again' : 'unavailable');
    expect(view.blocked()).toBe(status === 401);
  });
  it.each([['empty', new Blob([], { type: 'text/calendar' })], ['unexpected', new Blob(['synthetic'], { type: 'application/json' })]] as const)('rejects an %s successful response', async (_label, blob) => {
    create(); const result = view.download(); http.expectOne(route()).flush(blob); await result;
    expect(view.url()).toBeNull(); expect(view.message()).toContain('try again'); expect(URL.createObjectURL).not.toHaveBeenCalled();
  });
  it('times out without a file or indefinite busy state', async () => {
    vi.useFakeTimers(); create(); const result = view.download(), request = http.expectOne(route());
    await vi.advanceTimersByTimeAsync(25000); await result; expect(request.cancelled).toBe(true); expect(view.busy()).toBe(false); expect(view.message()).toContain('try again');
  });
  it('rejects malformed identifiers before making a request', async () => {
    create(); fixture.componentRef.setInput('booking', { ...booking, id: '../other?token=synthetic' }); fixture.detectChanges(); await view.download();
    http.expectNone(() => true); expect(view.message()).toContain('unavailable');
  });
});
