import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideApi } from '../shared/generated';
import { ReschedulePickerComponent, RescheduleChoice } from './reschedule-picker.component';

const now = Date.parse('2026-10-26T10:00:00Z');
const id = '20000000-0000-4000-8000-000000000001';
const slot = (start: string, offset = '+01:00') => ({ startInstant: start, sessionEndInstant: new Date(Date.parse(start) + 3600000).toISOString(), reservedEndInstant: new Date(Date.parse(start) + 7200000).toISOString(), businessDate: start.slice(0, 10), displayLocalTime: start.slice(0, 19), offset });
const avail = (slots: object[]) => ({ serverNow: new Date(now).toISOString(), businessZone: 'Europe/Brussels', displayZone: 'Europe/Brussels', horizonInstant: '2026-12-01T00:00:00Z', policyVersion: 2, slots, restrictions: [] });

describe('Reschedule slot picker', () => {
  let http: HttpTestingController, view: ReschedulePickerComponent, choices: (RescheduleChoice | null)[], lost: number;
  let fixture: ReturnType<typeof TestBed.createComponent<ReschedulePickerComponent>>;
  const list = () => http.expectOne(request => request.url === '/api/v1/bookings/' + id + '/availability');
  beforeEach(async () => {
    vi.useFakeTimers(); vi.setSystemTime(now); choices = []; lost = 0;
    TestBed.configureTestingModule({ imports: [ReschedulePickerComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1')] });
    http = TestBed.inject(HttpTestingController); fixture = TestBed.createComponent(ReschedulePickerComponent);
    fixture.componentRef.setInput('bookingId', id); fixture.componentRef.setInput('zone', 'UTC'); view = fixture.componentInstance;
    view.chosenSlot.subscribe(value => choices.push(value)); view.sessionLost.subscribe(() => lost++); fixture.detectChanges(); await Promise.resolve();
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });

  it('defaults to the booking display zone, searches a valid range and groups times by day with offsets', () => {
    expect(view.form.controls.zone.value).toBe('UTC'); view.search(); const request = list();
    const query = new URL(request.request.urlWithParams, 'https://example.test').searchParams; expect(query.get('displayZone')).toBe('UTC'); expect(query.get('from')).toBe('2026-10-26'); expect(query.get('to')).toBe('2026-11-01');
    request.flush(avail([slot('2026-10-27T09:00:00'), slot('2026-10-28T09:00:00', 'Z')])); fixture.detectChanges();
    expect(view.days()).toEqual(['2026-10-27', '2026-10-28']); expect(view.shown().length).toBe(1);
    view.shownDay.set('2026-10-28'); fixture.detectChanges(); expect(fixture.nativeElement.textContent).toContain('UTC+00:00');
  });
  it('emits the chosen slot with the availability policy version and clears it when inputs change', () => {
    view.search(); list().flush(avail([slot('2026-10-27T09:00:00')])); view.choose(view.shown()[0]);
    expect(choices.at(-1)).toMatchObject({ policyVersion: 2, displayZone: 'Europe/Brussels' }); expect(view.chosen()).not.toBeNull();
    view.form.controls.to.setValue('2026-11-02'); expect(choices.at(-1)).toBeNull(); expect(view.availability()).toBeNull();
  });
  it('rejects invalid ranges and zones locally and ignores a second search while loading', () => {
    view.form.setValue({ from: '2026-10-01', to: '2026-12-01', zone: 'UTC' }); view.search(); expect(view.problem()).toContain('31'); http.expectNone(() => true);
    view.form.setValue({ from: '2026-10-26', to: '2026-10-27', zone: 'Not/AZone' }); view.search(); expect(view.problem()).toContain('time zone');
    view.form.setValue({ from: '2026-10-26', to: '2026-10-27', zone: ' utc ' }); view.search(); view.search(); expect(view.form.controls.zone.value).toBe('UTC'); list().flush(avail([]));
    expect(view.availability()?.slots.length).toBe(0); expect(view.shownDay()).toBe('');
  });
  it('reports a lost session for 401 and 404 without a message', () => {
    view.search(); list().flush({}, { status: 401, statusText: 'x' }); expect(lost).toBe(1);
    view.search(); list().flush({}, { status: 404, statusText: 'x' }); expect(lost).toBe(2); expect(view.loading()).toBe(false);
  });
  it('gives safe messages for 400, 503 and a throttled 429 with a countdown', () => {
    view.search(); list().flush({ detail: 'synthetic-provider-detail' }, { status: 400, statusText: 'x' }); expect(view.problem()).toContain('Check the dates'); expect(view.problem()).not.toContain('provider');
    view.search(); list().flush({}, { status: 503, statusText: 'x' }); expect(view.problem()).toContain('could not be loaded');
    view.search(); list().flush({}, { status: 429, statusText: 'x', headers: { 'Retry-After': '3' } }); expect(view.retrySeconds()).toBe(3);
    view.search(); http.expectNone(() => true); vi.advanceTimersByTime(3000); expect(view.retrySeconds()).toBe(0);
  });
});
