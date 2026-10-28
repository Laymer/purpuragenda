import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { FormArray } from '@angular/forms';
import { AdministratorDateExceptionModeEnum as Mode, provideApi } from '../shared/generated';
import { CommandAccess } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { CalendarComponent } from './calendar.component';
import { TimeWindowsComponent, validWindows, windowControls } from './time-windows.component';

const now = Date.parse('2026-10-28T10:00:00Z'), id = '30000000-0000-4000-8000-000000000001';
const weekly = Object.fromEntries(['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'].map(day => [day, [{ start: '09:00', end: '20:00' }]]));
const exception = { date: '2026-11-02', mode: Mode.Add, windows: [{ start: '21:00', end: '24:00' }] };
const blackout = { id, startInstant: '2026-11-03T09:00:00Z', endInstant: '2026-11-03T12:00:00Z', reason: 'Synthetic blackout' };
const config = { calendarVersion: 5, policyVersion: 7, weekly, dateExceptions: [exception], blackouts: [blackout] };
const review = { assessedAt: new Date(now).toISOString(), calendarVersion: 5, policyVersion: 7, conflicts: [{ bookingId: id, reference: 'B-synthetic', startInstant: '2026-11-02T09:00:00Z', state: 'CONFIRMED', reasons: ['START_RULES'], blackoutIds: [] }] };
const session = { role: 'ADMIN', csrfToken: 'x'.repeat(43), idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() };
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };

describe('Administrator calendar changes', () => {
  let http: HttpTestingController, router: Router;
  let fixture: ReturnType<typeof TestBed.createComponent<CalendarComponent>>, view: CalendarComponent;
  const create = () => { fixture = TestBed.createComponent(CalendarComponent); view = fixture.componentInstance; fixture.detectChanges(); };
  async function loaded() { create(); http.expectOne('/api/v1/admin/configuration').flush(config); await settle(); fixture.detectChanges(); view.form.controls.reason.setValue('Synthetic calendar reason'); }
  async function request(path: string) { await settle(); http.match('/api/v1/session/bootstrap').forEach(r => r.flush(session)); await settle(); return http.expectOne('/api/v1/admin/configuration/' + path); }
  async function previewed() { const p = view.preview(); const r = await request('conflicts'); const body = r.request.body; r.flush(review); await p; fixture.detectChanges(); return body.proposal; }
  async function saveRejected(code: string, status = 409) { view.acknowledged.set(true); const p = view.save(); (await request('weekly')).flush({ code, detail: 'synthetic-provider-error' }, { status, statusText: 'x' }); await p; }
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now);
    TestBed.configureTestingModule({ imports: [CalendarComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([])] });
    http = TestBed.inject(HttpTestingController); router = TestBed.inject(Router);
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });

  it('previews all seven weekly days and retains exceptions/blackouts; saves only exact acknowledged IDs', async () => {
    await loaded(); view.weekly.controls['MONDAY'].clear(); view.weekly.controls['TUESDAY'].push(windowControls({ start: '21:00', end: '24:00' }));
    const proposal = await previewed(); expect(proposal.weekly.MONDAY).toEqual([]); expect(proposal.weekly.TUESDAY).toHaveLength(2); expect(proposal.dateExceptions).toEqual([exception]); expect(proposal.blackouts).toEqual([blackout]);
    await view.save(); http.expectNone('/api/v1/admin/configuration/weekly'); const checkbox = fixture.nativeElement.querySelector('app-configuration-review input') as HTMLInputElement; checkbox.checked = true; checkbox.dispatchEvent(new Event('change'));
    const p = view.save(); const r = await request('weekly'); expect(r.request.headers.get('If-Match')).toBe('"5"'); expect(JSON.parse(r.request.body)).toEqual({ weekly: proposal.weekly, reason: 'Synthetic calendar reason', acknowledgedBookingIds: [id] });
    r.flush({ calendarVersion: 6 }); await settle(); http.expectOne('/api/v1/admin/configuration').flush(config); await p; expect(view.message()).toContain('Calendar change saved'); expect(view.form.controls.reason.value).toBe('');
  });
  it.each([Mode.Add, Mode.Replace, Mode.Closed])('previews and writes %s date exceptions while retaining other calendar parts', async mode => {
    await loaded(); view.editException(exception); view.form.controls.mode.setValue(mode); fixture.detectChanges();
    const proposal = await previewed(); expect(proposal.dateExceptions).toEqual([{ ...exception, mode, windows: mode === Mode.Closed ? [] : exception.windows }]); expect(proposal.weekly).toEqual(weekly); expect(proposal.blackouts).toEqual([blackout]);
    view.acknowledged.set(true); const p = view.save(); const r = await request('date-exceptions'); expect(JSON.parse(r.request.body).dateException).toEqual(proposal.dateExceptions[0]); r.flush({}, { status: 400, statusText: 'x' }); await p;
  });
  it('allows closing a date even if a previously edited hidden window was invalid', async () => {
    await loaded(); view.editException(exception); view.exceptionWindows.at(0).controls.end.setValue('bad'); view.form.controls.mode.setValue(Mode.Closed); const proposal = await previewed(); expect(proposal.dateExceptions[0].windows).toEqual([]);
  });
  it('removes an exception from the proposal and submits only the review fields', async () => {
    await loaded(); view.form.patchValue({ action: 'remove-exception', date: exception.date }); fixture.detectChanges(); const proposal = await previewed(); expect(proposal.dateExceptions).toEqual([]);
    view.acknowledged.set(true); const p = view.save(); const r = await request('date-exceptions/' + exception.date + '/removal'); expect(JSON.parse(r.request.body)).toEqual({ reason: 'Synthetic calendar reason', acknowledgedBookingIds: [id] }); r.flush({}, { status: 400, statusText: 'x' }); await p;
  });
  it('previews and removes the exact selected blackout', async () => {
    await loaded(); view.form.patchValue({ action: 'remove-blackout', blackoutId: id }); fixture.detectChanges(); const proposal = await previewed(); expect(proposal.blackouts).toEqual([]); expect(fixture.nativeElement.querySelector('#remove-blackout option[value="' + id + '"]').textContent).toContain('13:00');
    view.acknowledged.set(true); const p = view.save(); const r = await request('blackouts/' + id + '/removal'); expect(JSON.parse(r.request.body)).toEqual({ reason: 'Synthetic calendar reason', acknowledgedBookingIds: [id] }); r.flush({}, { status: 400, statusText: 'x' }); await p;
  });
  it('requires explicit repeated blackout occurrences and preserves its server-bound ID across re-preview', async () => {
    await loaded(); view.form.patchValue({ action: 'blackout', startDay: '2027-10-31', startTime: '02:30', endDay: '2027-10-31', endTime: '02:45' }); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#blackout-start-offset')).not.toBeNull(); expect(fixture.nativeElement.querySelector('#blackout-end-offset')).not.toBeNull(); await view.preview(); expect(view.message()).toContain('Check');
    view.form.controls.startInstant.setValue(view.starts()[0]); view.form.controls.endInstant.setValue(view.ends()[1]); const proposal = await previewed(); const added = proposal.blackouts[1]; expect(added.id).toMatch(/^[0-9a-f-]{36}$/); expect(added.startInstant).toBe('2027-10-31T00:30:00.000Z'); expect(added.endInstant).toBe('2027-10-31T01:45:00.000Z');
    view.acknowledged.set(true); const p = view.save(); const r = await request('blackouts'); expect(JSON.parse(r.request.body).blackout).toEqual(added); r.flush({ code: 'CONFLICT_REVIEW_REQUIRED' }, { status: 409, statusText: 'x' });
    const again = await request('conflicts'); expect(again.request.body.proposal.blackouts[1].id).toBe(added.id); again.flush({ ...review, conflicts: [] }); await p; expect(view.acknowledged()).toBe(false); expect(view.message()).toContain('acknowledge them again');
  });
  it('rejects invalid reasons, windows, dates, absent removal selections and invalid blackout ranges', async () => {
    await loaded(); view.form.controls.reason.setValue('short'); await view.preview(); expect(view.message()).toContain('reason'); view.form.controls.reason.setValue('Synthetic reason');
    view.weekly.controls['MONDAY'].at(0).controls.start.setValue('24:00'); await view.preview(); expect(view.review()).toBeNull();
    view.form.patchValue({ action: 'date-exception', date: '2026-02-29' }); await view.preview(); expect(view.review()).toBeNull();
    view.form.patchValue({ date: '2026-11-02' }); view.exceptionWindows.push(windowControls({ start: '11:00', end: '10:00' })); await view.preview(); expect(view.review()).toBeNull();
    view.form.patchValue({ action: 'remove-exception', date: '2026-11-04' }); await view.preview(); expect(view.review()).toBeNull();
    view.form.patchValue({ action: 'remove-blackout', blackoutId: '' }); await view.preview(); expect(view.review()).toBeNull();
    view.form.patchValue({ action: 'blackout', startDay: '2027-03-28', startTime: '02:30', endDay: '2027-03-28', endTime: '03:30' }); await view.preview(); expect(view.review()).toBeNull();
    view.form.patchValue({ startTime: '04:00' }); await view.preview(); expect(view.review()).toBeNull(); http.expectNone('/api/v1/admin/configuration/conflicts');
  });
  it('invalidates review when the reason or any window changes', async () => {
    await loaded(); await previewed(); view.acknowledged.set(true); view.form.controls.reason.setValue('Another synthetic reason'); expect(view.review()).toBeNull(); expect(view.acknowledged()).toBe(false); await view.save();
    await previewed(); view.weekly.controls['MONDAY'].at(0).controls.end.setValue('19:00'); expect(view.review()).toBeNull();
  });
  it.each([{ calendarVersion: 6 }, { policyVersion: 8 }])('rejects stale preview versions %j', async versions => {
    await loaded(); const p = view.preview(); (await request('conflicts')).flush({ ...review, ...versions }); await p; expect(view.configuration()).toBeNull(); expect(view.message()).toContain('Reload'); await view.preview();
  });
  it.each([412, 428])('requires reload after HTTP%s', async status => {
    await loaded(); await previewed(); await saveRejected('VERSION', status); expect(view.configuration()).toBeNull(); expect(view.message()).toContain('Reload');
  });
  it('handles failed automatic conflict re-preview without claiming fresh results', async () => {
    await loaded(); await previewed(); view.acknowledged.set(true); const p = view.save(); (await request('weekly')).flush({ code: 'CONFLICT_REVIEW_REQUIRED' }, { status: 409, statusText: 'x' }); (await request('conflicts')).flush({}, { status: 503, statusText: 'x' }); await p;
    expect(view.review()).toBeNull(); expect(view.uncertain()).toBe(false); expect(view.message()).toContain('could not be loaded');
  });
  it('keeps uncertain changes immutable through retry throttling and blocks competing edits', async () => {
    await loaded(); await previewed(); view.acknowledged.set(true); const p = view.save(); const r = await request('weekly'); const body = r.request.body, key = r.request.headers.get('Idempotency-Key');
    await view.save(); await view.preview(); await view.load(); await view.retry(); view.editException(exception); expect(view.form.controls.action.value).toBe('weekly'); r.flush({}, { status: 503, statusText: 'x' }); await p;
    await view.save(); await view.preview(); await view.load(); const retry = view.retry(); const limited = await request('weekly'); limited.flush({}, { status: 429, statusText: 'x', headers: { 'Retry-After': '2' } }); await retry; await view.retry(); expect(view.message()).toContain('still unknown');
    await vi.advanceTimersByTimeAsync(2001); const last = view.retry(); const success = await request('weekly'); expect(success.request.body).toBe(body); expect(success.request.headers.get('Idempotency-Key')).toBe(key); success.flush({ calendarVersion: 6 }); await settle(); http.expectOne('/api/v1/admin/configuration').flush(config); await last; await view.retry();
  });
  it('clears private reasons and returns to calendar after fresh-factor expiry', async () => {
    const nav = vi.spyOn(router, 'navigate'); await loaded(); await previewed(); await saveRejected('AUTHENTICATION_REQUIRED', 403);
    expect(nav).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/calendar' } }); expect(view.configuration()).toBeNull(); expect(view.form.controls.reason.value).toBe('');
  });
  it('clears state after expired commands and sends expired reads to sign-in', async () => {
    await loaded(); await previewed(); await saveRejected('SESSION_REQUIRED', 401); expect(view.configuration()).toBeNull();
    const nav = vi.spyOn(router, 'navigateByUrl'); const p = view.load(); http.expectOne('/api/v1/admin/configuration').flush({}, { status: 401, statusText: 'x' }); await p; expect(nav).toHaveBeenCalledWith('/admin/login');
  });
  it('preserves successful-save evidence when refresh fails', async () => {
    await loaded(); await previewed(); view.acknowledged.set(true); const p = view.save(); (await request('weekly')).flush({ calendarVersion: 6 }); await settle(); http.expectOne('/api/v1/admin/configuration').flush({}, { status: 503, statusText: 'x' }); await p; expect(view.message()).toContain('saved, but');
  });
  it('handles preparation failure and throttling safely', async () => {
    await loaded(); await previewed(); view.acknowledged.set(true); const prepare = vi.spyOn(TestBed.inject(CommandAccess), 'prepare').mockImplementation(() => { throw new Error(); }); await view.save(); expect(view.message()).toContain('could not be submitted'); prepare.mockRestore(); await saveRejected('THROTTLED', 429); expect(view.message()).toContain('wait');
  });
  it('ignores reads after destruction', async () => { create(); const r = http.expectOne('/api/v1/admin/configuration'); fixture.destroy(); r.flush(config); await settle(); expect(view.configuration()).toBeNull(); });
  it('ignores late preview after invalidation', async () => { await loaded(); const p = view.preview(); const r = await request('conflicts'); TestBed.inject(SessionAccess).invalidate(); r.flush(review); await p; expect(view.review()).toBeNull(); });
  it('ignores late commands after destruction', async () => { await loaded(); await previewed(); view.acknowledged.set(true); const p = view.save(); const r = await request('weekly'); fixture.destroy(); r.flush({ calendarVersion: 6 }); await p; expect(view.configuration()).toBeNull(); });
});

describe('Brussels start-window editor', () => {
  afterEach(() => TestBed.resetTestingModule());
  it('adds/removes windows, respects disabled state and caps the HTTP limit', () => {
    TestBed.configureTestingModule({ imports: [TimeWindowsComponent] }); const fixture = TestBed.createComponent(TimeWindowsComponent), windows = new FormArray<ReturnType<typeof windowControls>>([]);
    fixture.componentRef.setInput('windows', windows); fixture.componentRef.setInput('label', 'Synthetic day'); fixture.detectChanges(); expect(fixture.nativeElement.textContent).toContain('No start windows');
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click(); fixture.detectChanges(); expect(windows.length).toBe(1); (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click(); expect(windows.length).toBe(0);
    fixture.componentRef.setInput('disabled', true); fixture.componentInstance.add(); expect(windows.length).toBe(0); fixture.componentRef.setInput('disabled', false);
    for (let i = 0; i < 25; i++) fixture.componentInstance.add(); expect(windows.length).toBe(24);
  });
  it('permits exclusive midnight ends and rejects invalid or reversed windows', () => {
    expect(validWindows([])).toBe(true); expect(validWindows([{ start: '00:00', end: '24:00' }])).toBe(true);
    for (const w of [{ start: '24:00', end: '24:00' }, { start: '10:00', end: '10:00' }, { start: '9:00', end: '20:00' }, { start: '09:00', end: '25:00' }]) expect(validWindows([w])).toBe(false);
    expect(validWindows(Array.from({ length: 25 }, () => ({ start: '09:00', end: '20:00' })))).toBe(false);
  });
});
