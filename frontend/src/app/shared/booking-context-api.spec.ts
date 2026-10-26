import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BookingReviewContext, CatalogService, ServiceCurrencyEnum } from './generated';
import { provideApi } from './generated/provide-api';

describe('Typed booking review context boundary', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1')] }));
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });
  it('retrieves exact current versions and plaintext notice with no anonymous credential requirement', () => {
    const context: BookingReviewContext = {
      serverNow: '2026-11-01T10:00:00Z', horizon: '2026-12-01T10:00:00Z', businessZone: 'Europe/Brussels', reservationMinutes: 120,
      service: { id: '10000000-0000-0000-0000-000000000001', version: 3, name: 'Synthetic consultation',
        durationMinutes: 60, priceMinor: 2500, currency: ServiceCurrencyEnum.Eur, active: true },
      policy: { version: 7, gridMinutes: 30, dailyCapacity: 3, minimumNoticeSeconds: 86400,
        pendingLifetimeSeconds: 86400, expiryBufferSeconds: 3600, customerCutoffSeconds: 86400 },
      notice: { version: 'local-v2', text: 'Synthetic <plaintext>\nNot an approved notice.', synthetic: true },
    };
    let received: BookingReviewContext | undefined;
    TestBed.inject(CatalogService).getBookingContext(context.service.id).subscribe(value => received = value);
    const request = TestBed.inject(HttpTestingController).expectOne(value => value.url === '/api/v1/booking-context');
    expect(request.request.method).toBe('GET'); expect(request.request.params.keys()).toEqual(['serviceId']);
    expect(request.request.params.get('serviceId')).toBe(context.service.id);
    expect(request.request.headers.has('X-CSRF-Token')).toBe(false); request.flush(context);
    expect(received?.service.version).toBe(3); expect(received?.policy.version).toBe(7); expect(received?.notice.version).toBe('local-v2');
    expect(received?.notice.text).toBe(context.notice.text); expect(received?.notice.synthetic).toBe(true);
    expect(received?.service.durationMinutes).not.toBe(received?.reservationMinutes);
  });
  it('preserves safe service removal and transient retry responses for review recovery', () => {
    const failures: HttpErrorResponse[] = [];
    for (const status of [404, 503]) {
      TestBed.inject(CatalogService).getBookingContext('10000000-0000-0000-0000-000000000001').subscribe({ error: error => failures.push(error) });
      const request = TestBed.inject(HttpTestingController).expectOne(value => value.url === '/api/v1/booking-context');
      request.flush({ code: status === 404 ? 'SERVICE_UNAVAILABLE' : 'TEMPORARILY_UNAVAILABLE', retryable: status === 503 },
        { status, statusText: 'Unavailable', headers: status === 503 ? { 'Retry-After': '1' } : {} });
    }
    expect(failures.map(value => value.status)).toEqual([404, 503]);
    expect(failures[0].error.retryable).toBe(false); expect(failures[1].headers.get('Retry-After')).toBe('1');
  });
});
