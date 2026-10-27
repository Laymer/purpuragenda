import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BookingsService } from './generated';
import { provideApi } from './generated/provide-api';

describe('Authorized rescheduling availability contract', () => {
  const id = '11111111-1111-4111-8111-111111111111';
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1')] }));
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });
  it('uses only booking scope and display dates, retaining both fall offsets and original duration', () => {
    let offsets: string[] = []; let duration = 0;
    TestBed.inject(BookingsService).bookingReschedulingAvailability(id, '2026-10-25', '2026-10-25', 'Europe/Brussels').subscribe(value => {
      offsets = value.slots.map(slot => slot.offset); duration = Date.parse(value.slots[0].sessionEndInstant) - Date.parse(value.slots[0].startInstant);
    });
    const request = TestBed.inject(HttpTestingController).expectOne(value => value.url === `/api/v1/bookings/${id}/availability`);
    expect(request.request.method).toBe('GET'); expect(request.request.params.keys()).toEqual(['from', 'to', 'displayZone']);
    expect(new URL(request.request.urlWithParams, 'https://synthetic.example.test').searchParams.get('displayZone')).toBe('Europe/Brussels'); expect(request.request.headers.has('X-CSRF-Token')).toBe(false);
    request.flush({ serverNow: '2026-10-01T10:00:00Z', businessZone: 'Europe/Brussels', displayZone: 'Europe/Brussels', horizonInstant: '2026-11-01T10:00:00Z', policyVersion: 2, restrictions: [], slots: [
      { startInstant: '2026-10-25T00:00:00Z', sessionEndInstant: '2026-10-25T00:30:00Z', reservedEndInstant: '2026-10-25T02:00:00Z', businessDate: '2026-10-25', displayLocalTime: '2026-10-25T02:00', offset: '+02:00' },
      { startInstant: '2026-10-25T01:00:00Z', sessionEndInstant: '2026-10-25T01:30:00Z', reservedEndInstant: '2026-10-25T03:00:00Z', businessDate: '2026-10-25', displayLocalTime: '2026-10-25T02:00', offset: '+01:00' },
    ] });
    expect(offsets).toEqual(['+02:00', '+01:00']); expect(duration).toBe(1800000);
  });
  it('preserves scope loss as a private failure rather than switching to public creation availability', () => {
    let status = 0;
    TestBed.inject(BookingsService).bookingReschedulingAvailability(id, '2026-10-25', '2026-10-25', 'UTC').subscribe({ error: (error: HttpErrorResponse) => status = error.status });
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(value => value.url === `/api/v1/bookings/${id}/availability`).flush({ code: 'BOOKING_NOT_FOUND', retryable: false }, { status: 404, statusText: 'Not found' });
    expect(status).toBe(404); http.expectNone(value => value.url === '/api/v1/availability');
  });
});
