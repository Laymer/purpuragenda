import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BookingsService, CreateBookingRequest, CustomerBookingStatusAllowedActionsEnum } from './generated';
import { provideApi } from './generated/provide-api';

describe('Customer booking typed HTTP boundary', () => {
  const id = '11111111-1111-4111-8111-111111111111';
  const csrf = 'c'.repeat(43);
  const body: CreateBookingRequest = {
    serviceId: id, serviceVersion: 1, policyVersion: 2,
    startInstant: '2026-11-01T10:00:00Z', displayZone: 'Europe/Brussels', noticeVersion: 'local-v1',
    intake: { firstName: 'Synthetic', lastName: 'Customer', telephone: '+32000000000',
      birthDate: '2000-01-01', birthTime: '12:30', birthplace: 'Synthetic town', message: '' },
  };
  beforeEach(() => TestBed.configureTestingModule({ providers: [
    provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'),
  ] }));
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('retains the same creation body and idempotency key after an uncertain response', () => {
    const api = TestBed.inject(BookingsService); const http = TestBed.inject(HttpTestingController);
    let failure: HttpErrorResponse | undefined; let version: number | undefined; let etag: string | null = null;
    api.createBooking(csrf, 'synthetic-create-key', body).subscribe({ error: error => failure = error });
    const first = http.expectOne('/api/v1/bookings');
    expect(first.request.method).toBe('POST');
    expect(first.request.headers.get('X-CSRF-Token') === csrf).toBe(true);
    expect(first.request.headers.get('Idempotency-Key')).toBe('synthetic-create-key');
    expect(first.request.body === body).toBe(true);
    first.error(new ProgressEvent('network'));
    expect(failure?.status).toBe(0);
    api.createBooking(csrf, 'synthetic-create-key', body, 'response').subscribe(response => {
      version = response.body?.version; etag = response.headers.get('ETag');
    });
    const retry = http.expectOne('/api/v1/bookings');
    expect(retry.request.headers.get('Idempotency-Key')).toBe(first.request.headers.get('Idempotency-Key'));
    expect(retry.request.body === first.request.body).toBe(true);
    retry.flush({ bookingId: id, version: 1 }, { status: 201, statusText: 'Created', headers: { ETag: '"1"' } });
    expect(version).toBe(1); expect(etag).toBe('"1"');
  });

  it('reads current actions as a JSON array without treating approval as payment', () => {
    const http = TestBed.inject(HttpTestingController); let actions: CustomerBookingStatusAllowedActionsEnum[] = [];
    let payment: string | undefined;
    TestBed.inject(BookingsService).getBooking(id).subscribe(value => { actions = value.allowedActions; payment = value.payment.status; });
    const request = http.expectOne('/api/v1/bookings/' + id);
    expect(request.request.method).toBe('GET'); expect(request.request.headers.has('X-CSRF-Token')).toBe(false);
    request.flush({ id, version: 2, state: 'CONFIRMED', allowedActions: ['CANCEL', 'RESCHEDULE'], payment: { status: 'UNPAID' } });
    expect(Array.isArray(actions)).toBe(true); expect(actions.includes(CustomerBookingStatusAllowedActionsEnum.Reschedule)).toBe(true);
    expect(payment).toBe('UNPAID');
  });

  it('sends strong versions on both changes and preserves stale/conflict refresh signals', () => {
    const http = TestBed.inject(HttpTestingController); const api = TestBed.inject(BookingsService);
    const failures: HttpErrorResponse[] = [];
    api.rescheduleBooking(id, csrf, 'synthetic-move', '"2"', { policyVersion: 2, startInstant: body.startInstant })
      .subscribe({ error: error => failures.push(error) });
    const move = http.expectOne('/api/v1/bookings/' + id + '/reschedule');
    expect(move.request.method).toBe('POST'); expect(move.request.headers.get('If-Match')).toBe('"2"');
    expect(move.request.headers.get('Idempotency-Key')).toBe('synthetic-move');
    expect(move.request.body).toEqual({ policyVersion: 2, startInstant: body.startInstant });
    move.flush({ code: 'STALE_VERSION', refreshAvailability: true }, { status: 412, statusText: 'Precondition Failed' });
    api.cancelBooking(id, csrf, 'synthetic-cancel', '"3"', {}).subscribe({ error: error => failures.push(error) });
    const cancel = http.expectOne('/api/v1/bookings/' + id + '/cancel');
    expect(cancel.request.method).toBe('POST'); expect(cancel.request.headers.get('If-Match')).toBe('"3"');
    expect(cancel.request.headers.get('X-CSRF-Token') === csrf).toBe(true); expect(cancel.request.body).toEqual({});
    cancel.flush({ code: 'CUTOFF_PASSED', refreshAvailability: true }, { status: 409, statusText: 'Conflict' });
    expect(failures.map(value => value.status)).toEqual([412, 409]);
    expect(failures.map(value => value.error.refreshAvailability)).toEqual([true, true]);
  });
});
