import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { Availability, AvailabilityBusinessZoneEnum, provideApi, Service, ServiceCurrencyEnum, Slot } from '../shared/generated';
import { ServiceTimeComponent } from './service-time.component';

const first: Service = { id: '10000000-0000-0000-0000-000000000001', version: 3, name: 'Karmic reading', durationMinutes: 30, priceMinor: 1500, currency: ServiceCurrencyEnum.Eur, active: true };
const second: Service = { ...first, id: '10000000-0000-0000-0000-000000000002', version: 4, name: 'Transit reading', durationMinutes: 60, priceMinor: 3000 };
const slots: Slot[] = [
  { startInstant: '2026-10-25T00:30:00Z', sessionEndInstant: '2026-10-25T01:00:00Z', reservedEndInstant: '2026-10-25T02:30:00Z', businessDate: '2026-10-25', displayLocalTime: '2026-10-25T02:30', offset: '+02:00' },
  { startInstant: '2026-10-25T01:30:00Z', sessionEndInstant: '2026-10-25T02:00:00Z', reservedEndInstant: '2026-10-25T03:30:00Z', businessDate: '2026-10-25', displayLocalTime: '2026-10-25T02:30', offset: '+01:00' },
];
const available = (changes: Partial<Availability> = {}): Availability => ({ serverNow: '2026-10-01T10:00:00Z', businessZone: AvailabilityBusinessZoneEnum.EuropeBrussels, displayZone: 'Europe/Brussels', horizonInstant: '2026-11-01T10:00:00Z', policyVersion: 8, slots, restrictions: [], ...changes });

describe('Real catalog-backed service and time selection', () => {
  let fixture: ComponentFixture<ServiceTimeComponent>;
  let component: ServiceTimeComponent;
  let http: HttpTestingController;
  const root = () => fixture.nativeElement as HTMLElement;
  const text = () => root().textContent ?? '';
  const render = () => fixture.detectChanges();
  const catalog = () => http.expectOne('/api/v1/services');
  const times = () => http.expectOne(request => request.url === '/api/v1/availability');
  function ready(values = [first, second]): void { catalog().flush(values); render(); }
  function choose(service = first): TestRequest {
    component.form.setValue({ from: '2026-10-25', to: '2026-10-25', zone: 'Europe/Brussels' });
    component.chooseService(service); render(); return times();
  }
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ServiceTimeComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1')] });
    fixture = TestBed.createComponent(ServiceTimeComponent); component = fixture.componentInstance; http = TestBed.inject(HttpTestingController); render();
  });
  afterEach(() => { fixture.destroy(); http.verify(); TestBed.resetTestingModule(); vi.useRealTimers(); });

  it('loads real service versions, durations and prices, without assuming a reservation duration', () => {
    expect(text()).toContain('Loading consultations'); ready([first, second, { ...first, id: 'archived', active: false }]);
    expect(root().querySelectorAll('input[name=consultation]').length).toBe(2);
    expect(text()).toContain('30 minutes · €15.00'); expect(text()).toContain('60 minutes · €30.00');
    expect(text()).not.toContain('120 minutes');
    const radio = root().querySelector('input[name=consultation]') as HTMLInputElement;
    radio.dispatchEvent(new Event('change')); render();
    const request = times(); expect(request.request.params.get('serviceId')).toBe(first.id);
    expect(text()).toContain('Checking available times'); request.flush(available()); render();
    expect(text()).toContain('Times in Europe/Brussels');
  });

  it('catalog refresh invalidates a selected version and native controls remain labelled', () => {
    ready(); choose().flush(available()); render(); component.chooseTime(slots[0]); render();
    for (const input of root().querySelectorAll<HTMLInputElement>('input')) expect(input.labels!.length).toBeGreaterThan(0);
    expect(root().querySelector('fieldset.times legend')?.textContent).toBe('Choose an available time');
    component.loadServices(); render(); expect(component.choice()).toBeNull(); expect(component.service()).toBeNull();
    ready([{ ...first, version: 4 }]); expect(root().querySelector('.selection')).toBeNull();
  });

  it('keeps repeated civil times distinct by instant and displays each explicit UTC offset', () => {
    ready(); choose().flush(available()); render();
    const inputs = [...root().querySelectorAll<HTMLInputElement>('input[name=time]')];
    expect(inputs.map(input => input.value)).toEqual(slots.map(slot => slot.startInstant));
    expect(text()).toContain('UTC+02:00 · 30 minutes'); expect(text()).toContain('UTC+01:00 · 30 minutes');
    const emitted: unknown[] = []; component.selectionChanged.subscribe(value => emitted.push(value));
    inputs[1].dispatchEvent(new Event('change')); render();
    expect(component.choice()?.startInstant).toBe(slots[1].startInstant);
    expect(root().querySelector('.selection')?.textContent).toContain('Your selection');
    expect(emitted.at(-1)).toEqual({ service: first, policyVersion: 8, slot: slots[1], displayZone: 'Europe/Brussels' });
    expect(text()).toContain('until you submit a booking request');
    expect(root().querySelector('button[type=submit]')?.textContent).toContain('Show available times');
  });

  it('clears a chosen time for every service, date or zone change and cancels stale requests', () => {
    ready(); const old = choose();
    component.chooseService(second); render(); expect(old.cancelled).toBe(true);
    const latest = times(); latest.flush(available()); render(); component.chooseTime(slots[0]); render();
    expect(component.choice()).not.toBeNull();
    component.form.controls.zone.setValue('America/New_York'); render();
    expect(component.choice()).toBeNull(); expect(component.availability()).toBeNull();
    component.search(); const changingDates = times();
    expect(new URL(changingDates.request.urlWithParams, 'https://example.test').searchParams.get('displayZone')).toBe('America/New_York');
    component.form.controls.from.setValue('2026-10-26'); render(); expect(changingDates.cancelled).toBe(true);
    component.form.controls.to.setValue('2026-10-27'); render(); expect(component.choice()).toBeNull();
    fixture.destroy();
  });

  it('uses canonical IANA zones and displays times across business and display dates', () => {
    ready(); component.form.setValue({ from: '2026-10-24', to: '2026-10-25', zone: 'america/new_york' });
    component.chooseService(first); const request = times();
    expect(new URL(request.request.urlWithParams, 'https://example.test').searchParams.get('displayZone')).toBe('America/New_York');
    expect(request.request.params.get('from')).toBe('2026-10-24'); expect(request.request.params.get('to')).toBe('2026-10-25');
    request.flush(available({ displayZone: 'America/New_York', slots: [{ ...slots[0], offset: '-04:00' }] })); render();
    expect(text()).toContain('20:30'); expect(text()).toContain('UTC-04:00');
  });

  it('does not request times without a service or with invalid dates or zones', () => {
    ready(); component.search(); http.expectNone(request => request.url === '/api/v1/availability');
    component.service.set(first); render();
    const focus = vi.spyOn(root().querySelector<HTMLInputElement>('#from-date')!, 'focus');
    component.form.setValue({ from: '2026-01-01', to: '2026-02-01', zone: 'Europe/Brussels' }); component.search(); render();
    expect(text()).toContain('no more than 31 days'); expect(focus).toHaveBeenCalled();
    const zoneFocus = vi.spyOn(root().querySelector<HTMLInputElement>('#display-zone')!, 'focus');
    component.form.setValue({ from: '2026-01-01', to: '2026-01-31', zone: 'not-a-zone' }); component.search(); render();
    expect(zoneFocus).toHaveBeenCalled(); http.expectNone(request => request.url === '/api/v1/availability');
  });

  it('renders empty catalogs and advisory restrictions without inventing slots', () => {
    ready([]); expect(text()).toContain('No consultations are available');
    component.loadServices(); ready(); choose().flush(available({ slots: [], restrictions: ['DAILY_CAPACITY'] })); render();
    expect(text()).toContain('No times are available'); expect(text()).toContain('booking policies');
    expect(root().querySelectorAll('input[name=time]').length).toBe(0);
  });

  it('shows safe failures with working manual retries and archived-service recovery', () => {
    catalog().flush({ detail: 'SYNTHETIC_PRIVATE' }, { status: 503, statusText: 'Unavailable' }); render();
    expect(text()).toContain('could not load consultations'); expect(text()).not.toContain('SYNTHETIC_PRIVATE');
    (root().querySelector('[role=alert] button') as HTMLButtonElement).click(); ready();
    choose().flush({}, { status: 503, statusText: 'Unavailable' }); render(); expect(text()).toContain('could not load times');
    component.search(); times().flush({}, { status: 404, statusText: 'Not found' }); render();
    expect(text()).toContain('no longer available');
    component.loadServices(); ready([second]); component.chooseService(second); times().flush(available({ slots: [] })); render();
  });

  it('honors429 Retry-After across changed selections and both retry actions', () => {
    vi.useFakeTimers(); ready(); choose().flush({}, { status: 429, statusText: 'Too many requests', headers: { 'Retry-After': '2' } }); render();
    expect(component.retrySeconds()).toBe(2); expect(text()).toContain('wait 2 seconds');
    component.search(); component.loadServices(); component.chooseService(second);
    http.expectNone(request => request.url === '/api/v1/availability'); http.expectNone('/api/v1/services');
    expect(root().querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);
    vi.advanceTimersByTime(1000); render(); expect(component.retrySeconds()).toBe(1);
    vi.advanceTimersByTime(1000); render(); expect(component.retrySeconds()).toBe(0);
    component.search(); times().flush(available()); render(); expect(component.service()?.id).toBe(second.id);
  });

  it('applies a safe cooldown to a throttled catalog and cleans up pending requests', () => {
    vi.useFakeTimers(); catalog().flush({}, { status: 429, statusText: 'Too many requests' }); render();
    expect(component.retrySeconds()).toBe(60); component.loadServices(); http.expectNone('/api/v1/services');
    vi.advanceTimersByTime(60000); component.loadServices(); const pending = catalog();
    fixture.destroy(); expect(pending.cancelled).toBe(true);
  });
});
