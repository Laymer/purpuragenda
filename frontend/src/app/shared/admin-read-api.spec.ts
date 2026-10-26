import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdministratorService } from './generated';
import { provideApi } from './generated/provide-api';

describe('Private administrator read typed HTTP boundary', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1')] }));
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });
  it('copies opaque cursor unchanged and receives minimal agenda entries as JSON arrays', () => {
    const http = TestBed.inject(HttpTestingController); let size = 0; let next: string | null | undefined;
    TestBed.inject(AdministratorService).listAdministratorBookings('2026-11-01', '2026-11-07', 2, 'opaque-cursor')
      .subscribe(value => { size = value.entries.length; next = value.nextCursor; expect(Array.isArray(value.entries)).toBe(true); });
    const request = http.expectOne(value => value.url === '/api/v1/admin/bookings');
    expect(request.request.method).toBe('GET'); expect(request.request.headers.has('X-CSRF-Token')).toBe(false);
    expect(request.request.params.keys()).toEqual(['from', 'through', 'limit', 'cursor']);
    expect(request.request.params.get('cursor')).toBe('opaque-cursor'); expect(request.request.params.get('limit')).toBe('2');
    request.flush({ serverNow: '2026-11-01T10:00:00Z', businessZone: 'Europe/Brussels', nextCursor: null, entries: [] });
    expect(size).toBe(0); expect(next).toBeNull();
  });
  it('keeps owner intake and independent money in the private detail projection with its command version', () => {
    const http = TestBed.inject(HttpTestingController); const id = '11111111-1111-4111-8111-111111111111';
    let version: number | undefined; let etag: string | null = null; let birthTime: string | undefined; let payment: string | undefined;
    TestBed.inject(AdministratorService).getAdministratorBooking(id, 'response').subscribe(value => {
      version = value.body?.version; etag = value.headers.get('ETag'); birthTime = value.body?.snapshot.intake.birthTime; payment = value.body?.payment.status;
    });
    const request = http.expectOne('/api/v1/admin/bookings/' + id);
    expect(request.request.method).toBe('GET'); expect(request.request.headers.has('If-None-Match')).toBe(false);
    request.flush({ id, version: 7, snapshot: { intake: { birthTime: '12:30:15' } }, payment: { status: 'UNPAID', entries: [], refundObligations: [] } }, { headers: { ETag: '"7"', 'Cache-Control': 'no-store' } });
    expect(version).toBe(7); expect(etag).toBe('"7"'); expect(birthTime).toBe('12:30:15'); expect(payment).toBe('UNPAID');
  });
});
