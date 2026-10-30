import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { AdministratorBookingDetailStateEnum, provideApi } from '../shared/generated';
import { CommandAccess } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { BookingDetailComponent } from './booking-detail.component';

const now = Date.parse('2026-10-26T10:00:00Z');
const id = '30000000-0000-4000-8000-000000000001';
const session = { role: 'ADMIN', csrfToken: 'x'.repeat(43), idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() };
const service = { id: '10000000-0000-0000-0000-000000000001', version: 1, name: 'Synthetic consultation', durationMinutes: 60, priceMinor: 10000, currency: 'EUR', active: true };
const detail = (over: Record<string, unknown> = {}) => ({
  id, reference: 'B-synthetic', version: 7, state: 'PENDING_CONFIRMATION', origin: 'CUSTOMER', createdAt: '2026-10-26T08:00:00Z', startInstant: '2026-10-30T09:00:00Z', sessionEndInstant: '2026-10-30T10:00:00Z', reservationEndInstant: '2026-10-30T11:00:00Z',
  pendingExpiresAt: '2026-10-27T10:00:00Z', pendingExpired: false, deliveryEmail: 'synthetic@example.test', meetingUrl: null, calendarRevision: 0, confirmationBasis: null, serverNow: new Date(now).toISOString(),
  snapshot: { creationEmail: 'synthetic@example.test', displayZone: 'Europe/Brussels', noticeVersion: 'local-v1', service, policy: { version: 1, gridMinutes: 30, dailyCapacity: 3, minimumNoticeSeconds: 0, pendingLifetimeSeconds: 86400, expiryBufferSeconds: 0, customerCutoffSeconds: 0 },
    intake: { firstName: 'Synthetic', lastName: 'Customer', telephone: '+32400123456', birthDate: '1990-01-01', birthTime: '12:00', birthplace: 'Synthetic city', message: '<b>Private synthetic message</b>' } },
  effectiveIntake: { firstName: 'Synthetic', lastName: 'Customer', telephone: '+32400123456', birthDate: '1990-01-01', birthTime: '12:00', birthplace: 'Synthetic city', message: '<b>Private synthetic message</b>' },
  payment: { status: 'UNPAID', receivedMinor: 0, refundedMinor: 0, remainingMinor: 10000, refundDueByCurrency: {}, receivedByCurrency: {}, entries: [], refundObligations: [] }, ...over,
});
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };
const link = 'https://zoom.us/j/123456789';

describe('Administrator booking detail and approval', () => {
  let http: HttpTestingController, router: Router;
  let fixture: ReturnType<typeof TestBed.createComponent<BookingDetailComponent>>, view: BookingDetailComponent;
  let routeId = id;
  const create = (param = id) => {
    routeId = param;
    fixture = TestBed.createComponent(BookingDetailComponent); view = fixture.componentInstance; fixture.detectChanges();
  };
  const get = () => http.expectOne('/api/v1/admin/bookings/' + id);
  const loaded = async (body = detail(), etag: string | null = '"7"') => {
    create(); get().flush(body, etag === null ? {} : { headers: { ETag: etag } }); await settle(); fixture.detectChanges();
  };
  async function post(): Promise<TestRequest> {
    await settle(); const boot = http.match('/api/v1/session/bootstrap'); boot.forEach(request => request.flush(session)); await settle();
    return http.expectOne('/api/v1/admin/bookings/' + id + '/approve');
  }
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now);
    TestBed.configureTestingModule({ imports: [BookingDetailComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([]), { provide: ActivatedRoute, useFactory: () => ({ snapshot: { paramMap: convertToParamMap({ id: routeId }) } }) }] });
    http = TestBed.inject(HttpTestingController); router = TestBed.inject(Router);
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });

  it('shows erased intake explicitly while preserving service and payment details', async () => {
    await loaded(detail({ identityErased: true, effectiveIntake: null, snapshot: { ...detail().snapshot, intake: null } }));
    expect(fixture.nativeElement.textContent).toContain('Personal intake fields erased after administrator review.');
    expect(fixture.nativeElement.textContent).toContain('Payment');
    expect(fixture.nativeElement.textContent).toContain('Erased after administrator review');
  });
  it('renders detail, finance and intake as text only, never as markup or storage', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'); await loaded();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('B-synthetic'); expect(text).toContain('UTC+1'); expect(text).toContain('remaining 100.00 (EUR)'); expect(text).toContain('Approval does not imply payment');
    expect(text).toContain('<b>Private synthetic message</b>'); expect(fixture.nativeElement.querySelector('dd b')).toBeNull(); expect(text).toContain('Request expires');
    expect(storage).not.toHaveBeenCalled(); expect(fixture.nativeElement.querySelector('#meeting-url')).not.toBeNull();
  });
  it('shows reviewed effective intake while explaining the retained original', async () => {
    await loaded(detail({ effectiveIntake: { ...detail().effectiveIntake, firstName: 'Reviewed', message: '<b>Corrected</b>' } }));
    expect(fixture.nativeElement.textContent).toContain('Reviewed Customer'); expect(fixture.nativeElement.textContent).toContain('<b>Corrected</b>'); expect(fixture.nativeElement.textContent).toContain('original intake remains'); expect(fixture.nativeElement.querySelector('dd b')).toBeNull();
  });
  it('rejects a malformed identifier without any request and reports a missing booking', async () => {
    create('not-a-uuid'); expect(view.message()).toContain('could not be found'); http.expectNone(r => r.url.includes('/admin/bookings'));
    TestBed.resetTestingModule();
  });
  it.each([[404, 'could not be found'], [503, 'could not be loaded']])('shows a safe message for a %s read', async (status, text) => {
    create(); get().flush({ detail: 'synthetic-provider-detail' }, { status, statusText: 'x' }); await settle();
    expect(view.message()).toContain(text); expect(view.message()).not.toContain('provider'); expect(view.booking()).toBeNull();
  });
  it('sends a 401 detail read to sign in', async () => {
    const spy = vi.spyOn(router, 'navigateByUrl'); create(); get().flush({}, { status: 401, statusText: 'No' }); await settle(); expect(spy).toHaveBeenCalledWith('/admin/login');
  });
  it('re-authenticates directly after a 403 detail read and returns to the same booking', async () => {
    const spy = vi.spyOn(router, 'navigate'); create(); get().flush({}, { status: 403, statusText: 'No' }); await settle();
    expect(spy).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/bookings/' + id } }); expect(TestBed.inject(SessionAccess).state()).toBeNull();
  });
  it('does not claim fresh details after a failed reload following a version problem', async () => {
    await loaded(); view.meeting.setValue(link); const pending = view.approve(); (await post()).flush({}, { status: 412, statusText: 'x' }); await settle();
    get().flush({}, { status: 503, statusText: 'Unavailable' }); await pending; await settle();
    expect(view.message()).toContain('could not be loaded'); expect(view.message()).not.toContain('latest details are shown');
  });
  it('does not claim fresh details after a failed reload following a generic conflict', async () => {
    await loaded(); view.meeting.setValue(link); const pending = view.approve(); (await post()).flush({ code: 'SOMETHING' }, { status: 409, statusText: 'x' }); await settle();
    get().flush({}, { status: 503, statusText: 'Unavailable' }); await pending; await settle();
    expect(view.message()).toContain('could not be loaded'); expect(view.message()).not.toContain('latest details are shown');
  });
  it('says approval succeeded even when the follow-up reload fails', async () => {
    await loaded(); view.meeting.setValue(link); const pending = view.approve(); (await post()).flush({ bookingId: id, version: 8 }); await settle();
    get().flush({}, { status: 503, statusText: 'Unavailable' }); await pending; await settle();
    expect(view.message()).toContain('Booking approved, but');
  });
  it('hides approval for settled or expired bookings', async () => {
    await loaded(detail({ state: 'CONFIRMED', meetingUrl: 'https://zoom.us/j/123456789' })); expect(fixture.nativeElement.querySelector('#meeting-url')).toBeNull();
    expect(view.canApprove()).toBe(false);
  });
  it('hides approval while the pending hold is expired', async () => {
    await loaded(detail({ pendingExpired: true })); expect(view.canApprove()).toBe(false); expect(fixture.nativeElement.textContent).toContain('hold expired');
  });
  it('validates the Zoom link as a hint without sending anything', async () => {
    await loaded(); view.meeting.setValue('http://example.test/j/1'); await view.approve(); fixture.detectChanges();
    expect(view.hint()).toContain('zoom.us'); expect(fixture.nativeElement.querySelector('#meeting-hint')).not.toBeNull(); http.expectNone('/api/v1/session/bootstrap');
  });
  it('approves with the ETag as If-Match and a UUID Idempotency-Key, then reloads the detail', async () => {
    await loaded(); view.meeting.setValue(' ' + link + ' '); const pending = view.approve(); const request = await post();
    expect(request.request.headers.get('If-Match')).toBe('"7"'); expect(request.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/); expect(request.request.headers.get('X-CSRF-Token')).toBe(session.csrfToken);
    expect(JSON.parse(request.request.body)).toEqual({ meetingUrl: link });
    request.flush({ bookingId: id, version: 8 }); await settle(); get().flush(detail({ state: 'CONFIRMED', version: 8, meetingUrl: link }), { headers: { ETag: '"8"' } }); await pending; await settle(); fixture.detectChanges();
    expect(view.message()).toContain('approved'); expect(view.booking()?.state).toBe('CONFIRMED'); expect(view.meeting.value).toBe(''); expect(fixture.nativeElement.querySelector('#meeting-url')).toBeNull();
  });
  it('falls back to the version for If-Match when the ETag is missing or weak', async () => {
    await loaded(detail({ version: 9 }), 'W/"9"'); view.meeting.setValue(link); const pending = view.approve(); const request = await post();
    expect(request.request.headers.get('If-Match')).toBe('"9"'); request.flush({}, { status: 400, statusText: 'Bad' }); await pending; await settle();
    expect(view.message()).toContain('could not be approved'); expect(view.uncertain()).toBe(false);
  });
  it('keeps the same idempotency key on an explicit retry after an uncertain result', async () => {
    await loaded(); view.meeting.setValue(link); const first = view.approve(); const request = await post(); const key = request.request.headers.get('Idempotency-Key');
    request.error(new ProgressEvent('error')); await first; await settle(); fixture.detectChanges();
    expect(view.uncertain()).toBe(true); expect(view.message()).toContain('may have succeeded'); await view.approve(); http.expectNone('/api/v1/admin/bookings/' + id + '/approve');
    const retry = view.retry(); await settle(); const again = http.expectOne('/api/v1/admin/bookings/' + id + '/approve');
    expect(again.request.headers.get('Idempotency-Key')).toBe(key); again.flush({ bookingId: id, version: 8 }); await settle(); get().flush(detail({ state: 'CONFIRMED' })); await retry; await settle();
    expect(view.uncertain()).toBe(false); await view.retry(); http.expectNone('/api/v1/admin/bookings/' + id + '/approve');
  });
  it.each([[412, {}], [428, {}]])('reloads the detail after a %s version problem', async (status, body) => {
    await loaded(); view.meeting.setValue(link); const pending = view.approve(); (await post()).flush(body, { status, statusText: 'x' }); await settle();
    get().flush(detail({ version: 12 }), { headers: { ETag: '"12"' } }); await pending; await settle();
    expect(view.message()).toContain('changed'); expect(view.booking()?.version).toBe(12);
  });
  it('explains a meeting link already in use and keeps the form for another link', async () => {
    await loaded(); view.meeting.setValue(link); const pending = view.approve(); (await post()).flush({ code: 'MEETING_IN_USE', detail: 'synthetic-provider-detail' }, { status: 409, statusText: 'Conflict' }); await pending; await settle();
    expect(view.message()).toContain('already used'); expect(view.message()).not.toContain('provider'); expect(view.canApprove()).toBe(true); expect(view.uncertain()).toBe(false);
  });
  it('reloads and explains an expired hold, and a generic conflict', async () => {
    await loaded(); view.meeting.setValue(link); const expired = view.approve(); (await post()).flush({ code: 'HOLD_EXPIRED' }, { status: 409, statusText: 'Conflict' }); await settle();
    get().flush(detail({ pendingExpired: true })); await expired; await settle(); expect(view.message()).toContain('expired'); expect(view.canApprove()).toBe(false);
    TestBed.resetTestingModule();
  });
  it('reloads on an unclassified conflict', async () => {
    await loaded(); view.meeting.setValue(link); const pending = view.approve(); (await post()).flush({ code: 'SOMETHING' }, { status: 409, statusText: 'Conflict' }); await settle();
    get().flush(detail()); await pending; await settle(); expect(view.message()).toContain('conflicts');
  });
  it('re-authenticates directly when approval returns 403 and drops the stale session and frozen command', async () => {
    await loaded(); view.meeting.setValue(link); const nav = vi.spyOn(router, 'navigate');
    const pending = view.approve(); (await post()).flush({ code: 'AUTHENTICATION_REQUIRED' }, { status: 403, statusText: 'No' }); await pending; await settle();
    expect(nav).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/bookings/' + id } });
    expect(TestBed.inject(SessionAccess).state()).toBeNull(); expect(view.booking()).toBeNull(); expect(view.meeting.value).toBe('');
  });
  it('backs off on 429', async () => {
    await loaded(); view.meeting.setValue(link);
    const pending = view.approve(); (await post()).flush({}, { status: 429, statusText: 'Many', headers: { 'Retry-After': '5' } }); await pending; await settle();
    expect(view.message()).toContain('wait');
  });
  it('clears private state on 401', async () => {
    await loaded(); view.meeting.setValue(link);
    const pending = view.approve(); (await post()).flush({ code: 'SESSION_REQUIRED' }, { status: 401, statusText: 'No' }); await pending; await settle();
    expect(view.booking()).toBeNull(); expect(TestBed.inject(SessionAccess).state()).toBeNull();
  });
  it('clears private detail and the frozen command when the session is invalidated', async () => {
    await loaded(); view.meeting.setValue(link); TestBed.inject(SessionAccess).invalidate();
    expect(view.booking()).toBeNull(); expect(view.meeting.value).toBe(''); expect(view.canApprove()).toBe(false);
  });
  it('ignores a late detail response after destruction', async () => {
    create(); const request = get(); fixture.destroy(); request.flush(detail()); await settle(); expect(view.booking()).toBeNull();
  });
  async function actionPost(action: string): Promise<TestRequest> {
    await settle(); http.match('/api/v1/session/bootstrap').forEach(request => request.flush(session)); await settle();
    return http.expectOne('/api/v1/admin/bookings/' + id + '/' + action);
  }
  const reason = 'Synthetic administrative reason';
  it.each(['confirm-external-payment', 'reject', 'cancel', 'meeting'] as const)('submits %s with the reviewed version, reason and immutable command', async action => {
    await loaded(action === 'meeting' ? detail({ state: 'CONFIRMED', meetingUrl: link }) : detail());
    view.reason.setValue(' ' + reason + ' '); view.classification.setValue('CUSTOMER'); view.replacementMeeting.setValue(' https://zoom.us/j/987654321 '); view.meeting.setValue(link);
    const pending = action === 'confirm-external-payment' ? view.approve(action) : view.change(action);
    const request = await actionPost(action); expect(request.request.headers.get('If-Match')).toBe('"7"'); expect(request.request.headers.get('X-CSRF-Token')).toBe(session.csrfToken);
    expect(request.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(JSON.parse(request.request.body)).toEqual(action === 'confirm-external-payment' ? { meetingUrl: link } : action === 'cancel' ? { reason, classification: 'CUSTOMER' } : action === 'meeting' ? { reason, meetingUrl: 'https://zoom.us/j/987654321' } : { reason });
    request.flush({ bookingId: id, version: 8 }); await settle(); get().flush(detail({ version: 8, state: action === 'cancel' ? 'CANCELLED' : action === 'reject' ? 'REJECTED' : 'CONFIRMED' }));
    await pending; fixture.detectChanges(); expect(view.message()).toMatch(/confirmed|rejected|cancelled|changed/); expect(view.reason.value).toBe(''); expect(view.classification.value).toBe(''); expect(view.replacementMeeting.value).toBe('');
    if (action === 'cancel' || action === 'reject') expect(fixture.nativeElement.textContent).not.toContain('Manage booking');
  });
  it('validates reasons, explicit cancellation classification and replacement links before sending', async () => {
    await loaded(detail({ state: 'CONFIRMED', meetingUrl: link }));
    for (const invalid of ['', 'short', 'a'.repeat(501), 'Synthetic\u0001reason']) {
      view.reason.setValue(invalid); await view.change('cancel'); expect(view.changeHint()).toContain('10–500');
    }
    view.reason.setValue(reason); await view.change('cancel'); expect(view.changeHint()).toContain('Choose');
    view.replacementMeeting.setValue('http://example.test/j/1'); await view.change('meeting'); expect(view.changeHint()).toContain('zoom.us');
    await view.change('reject'); http.expectNone('/api/v1/session/bootstrap');
  });
  it('shows action controls only for applicable booking states and requires an explicit cancellation choice', async () => {
    await loaded(); expect(fixture.nativeElement.textContent).toContain('Confirm external payment'); expect(fixture.nativeElement.textContent).toContain('Reject booking');
    expect(fixture.nativeElement.querySelector('#cancellation-classification').value).toBe(''); expect(fixture.nativeElement.querySelector('#replacement-meeting')).toBeNull();
    view.reason.setValue(reason); await view.change('meeting'); http.expectNone('/api/v1/session/bootstrap');
    view.booking.set({ ...view.booking()!, state: AdministratorBookingDetailStateEnum.Cancelled }); fixture.detectChanges();
    await view.change('cancel'); expect(view.canManage()).toBe(false); expect(view.canChangeMeeting()).toBe(false); http.expectNone('/api/v1/session/bootstrap');
  });
  it.each([412, 428])('reloads after a %s change response', async status => {
    await loaded(); view.reason.setValue(reason); const pending = view.change('reject'); (await actionPost('reject')).flush({}, { status, statusText: 'Version' }); await settle();
    get().flush(detail({ version: 9 })); await pending; expect(view.message()).toContain('latest details');
  });
  it.each(['PAYMENT_INSUFFICIENT', 'MEETING_IN_USE', 'INVALID_TRANSITION'])('handles %s without displaying provider details', async code => {
    await loaded(); view.meeting.setValue(link); const pending = view.approve('confirm-external-payment');
    (await actionPost('confirm-external-payment')).flush({ code, detail: 'synthetic-provider-detail' }, { status: 409, statusText: 'Conflict' }); await settle(); get().flush(detail()); await pending;
    expect(view.message()).not.toContain('provider-detail'); expect(view.message()).toMatch(/receipts|already used|conflicts/);
  });
  it.each([409, 412])('does not claim a successful reload after a failed change and %s', async status => {
    await loaded(); view.reason.setValue(reason); const pending = view.change('reject');
    (await actionPost('reject')).flush({}, { status, statusText: 'Conflict' }); await settle(); get().flush({}, { status: 503, statusText: 'Unavailable' }); await pending;
    expect(view.message()).toContain('could not be loaded'); expect(view.message()).not.toContain('Review the latest');
  });
  it('keeps the exact cancellation on uncertain retry and blocks other changes', async () => {
    await loaded(); view.reason.setValue(reason); view.classification.setValue('PROVIDER');
    const pending = view.change('cancel'); const request = await actionPost('cancel'); const key = request.request.headers.get('Idempotency-Key');
    view.classification.setValue('CUSTOMER'); view.reason.setValue('Changed synthetic reason'); request.error(new ProgressEvent('error')); await pending;
    expect(view.uncertain()).toBe(true); expect(view.retryLabel()).toContain('same change'); await view.change('reject'); http.expectNone('/api/v1/admin/bookings/' + id + '/reject');
    const retry = view.retry(); const second = await actionPost('cancel'); expect(second.request.headers.get('Idempotency-Key')).toBe(key); expect(JSON.parse(second.request.body)).toEqual({ reason, classification: 'PROVIDER' });
    second.flush({}); await settle(); get().flush(detail({ state: 'CANCELLED' })); await retry;
  });
  it('re-authenticates after a stale-MFA change response and clears private forms', async () => {
    await loaded(); const nav = vi.spyOn(router, 'navigate'); view.reason.setValue(reason); view.classification.setValue('PROVIDER'); view.replacementMeeting.setValue(link);
    const pending = view.change('cancel'); (await actionPost('cancel')).flush({ code: 'AUTHENTICATION_REQUIRED' }, { status: 403, statusText: 'MFA' }); await pending;
    expect(nav).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/bookings/' + id } }); expect(view.reason.value).toBe(''); expect(view.replacementMeeting.value).toBe(''); expect(view.booking()).toBeNull();
  });
  it.each([400, 429])('handles a %s change failure safely', async status => {
    await loaded(); view.reason.setValue(reason); const pending = view.change('reject');
    (await actionPost('reject')).flush({ detail: 'synthetic-provider-detail' }, { status, statusText: 'Rejected' }); await pending;
    expect(view.message()).toMatch(/wait|Check the fields/); expect(view.message()).not.toContain('provider');
  });
  it('retains an honest success message when change reload fails', async () => {
    await loaded(); view.reason.setValue(reason); const pending = view.change('reject'); (await actionPost('reject')).flush({}); await settle();
    get().flush({}, { status: 503, statusText: 'Unavailable' }); await pending; expect(view.message()).toContain('Booking rejected, but');
  });
  it('fails safely if the command cannot be prepared and clears reason on invalidation', async () => {
    await loaded(); view.reason.setValue(reason); vi.spyOn(TestBed.inject(CommandAccess), 'prepare').mockImplementation(() => { throw new Error('synthetic'); });
    await view.change('reject'); expect(view.message()).toContain('could not be submitted'); TestBed.inject(SessionAccess).invalidate(); expect(view.reason.value).toBe('');
  });

  it.each(['COMPLETED', 'NO_SHOW'] as const)('records %s after session start with a reason and reviewed version', async outcome => {
    await loaded(detail({ state: 'CONFIRMED', meetingUrl: link, startInstant: new Date(now).toISOString(), sessionEndInstant: new Date(now + 3600000).toISOString() }));
    expect(view.canRecordOutcome()).toBe(true); expect(view.outcomeReasonRequired()).toBe(true); expect(fixture.nativeElement.textContent).toContain('has not ended');
    await view.recordOutcome(outcome); expect(view.changeHint()).toContain('10–500'); http.expectNone('/api/v1/session/bootstrap');
    view.reason.setValue(reason); const pending = view.recordOutcome(outcome); const request = await actionPost('outcome');
    expect(JSON.parse(request.request.body)).toEqual({ outcome, reason }); expect(request.request.headers.get('If-Match')).toBe('"7"');
    request.flush({}); await settle(); get().flush(detail({ state: outcome, version: 8 })); await pending; fixture.detectChanges();
    expect(view.message()).toBe('Outcome recorded.'); expect(view.canRecordOutcome()).toBe(false); expect(fixture.nativeElement.textContent).not.toContain('Mark completed');
  });
  it('permits an omitted reason at exact session end and rejects invalid nonempty reasons', async () => {
    await loaded(detail({ state: 'CONFIRMED', startInstant: new Date(now - 3600000).toISOString(), sessionEndInstant: new Date(now).toISOString() }));
    expect(view.outcomeReasonRequired()).toBe(false);
    for (const invalid of ['short', 'a'.repeat(501), 'Synthetic\u0001reason']) { view.reason.setValue(invalid); await view.recordOutcome('NO_SHOW'); expect(view.changeHint()).toContain('10–500'); }
    view.reason.setValue(' '); const pending = view.recordOutcome('COMPLETED'); const request = await actionPost('outcome'); expect(JSON.parse(request.request.body)).toEqual({ outcome: 'COMPLETED', reason: null });
    request.flush({}); await settle(); get().flush(detail({ state: 'COMPLETED' })); await pending;
  });
  it('uses refreshed authoritative time for outcome eligibility and never offers outcomes before start', async () => {
    await loaded(detail({ state: 'CONFIRMED', startInstant: new Date(now + 1).toISOString() }));
    expect(view.canRecordOutcome()).toBe(false); await view.recordOutcome('COMPLETED'); http.expectNone('/api/v1/session/bootstrap');
    const button = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(b => b.textContent?.trim() === 'Refresh details')!;
    button.click(); await settle(); get().flush(detail({ state: 'CONFIRMED', startInstant: new Date(now + 1).toISOString(), serverNow: new Date(now + 1).toISOString() })); await settle();
    expect(view.canRecordOutcome()).toBe(true);
    TestBed.inject(SessionAccess).invalidate(); expect(view.outcomeReasonRequired()).toBe(false); expect(view.canRecordOutcome()).toBe(false);
  });
  it('retries exactly the same outcome after an uncertain response and reloads a server boundary rejection', async () => {
    await loaded(detail({ state: 'CONFIRMED', startInstant: new Date(now - 3600000).toISOString(), sessionEndInstant: new Date(now).toISOString() }));
    const pending = view.recordOutcome('COMPLETED'); const first = await actionPost('outcome'); const key = first.request.headers.get('Idempotency-Key'); first.error(new ProgressEvent('error')); await pending;
    await view.recordOutcome('NO_SHOW'); http.expectNone('/api/v1/admin/bookings/' + id + '/outcome');
    const retry = view.retry(); const second = await actionPost('outcome'); expect(second.request.headers.get('Idempotency-Key')).toBe(key); expect(JSON.parse(second.request.body).outcome).toBe('COMPLETED');
    second.flush({ code: 'SESSION_NOT_STARTED' }, { status: 409, statusText: 'Conflict' }); await settle(); get().flush(detail({ state: 'CONFIRMED', startInstant: new Date(now + 1).toISOString() })); await retry;
    expect(view.message()).toContain('conflicts'); expect(view.canRecordOutcome()).toBe(false);
  });
  it('fails safely if an outcome cannot be prepared', async () => {
    await loaded(detail({ state: 'CONFIRMED', startInstant: new Date(now - 3600000).toISOString(), sessionEndInstant: new Date(now).toISOString() }));
    vi.spyOn(TestBed.inject(CommandAccess), 'prepare').mockImplementation(() => { throw new Error('synthetic'); });
    await view.recordOutcome('COMPLETED'); expect(view.message()).toContain('outcome could not be submitted');
  });

});
