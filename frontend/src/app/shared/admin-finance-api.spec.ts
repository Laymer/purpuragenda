import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdministratorService } from './generated';
import { provideApi } from './generated/provide-api';

describe('Administrator finance typed HTTP boundary', () => {
  const id = '11111111-1111-4111-8111-111111111111', payment = '22222222-2222-4222-8222-222222222222';
  const fact = { amountMinor: 1500, currency: 'EUR', method: 'SYNTHETIC', reference: 'synthetic', verifiedAt: '2026-11-01T10:00:00Z' };
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1')] }));
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });
  it('keeps actual payment entry IDs distinct and sends no booking version for independent money', () => {
    const sdk = TestBed.inject(AdministratorService), http = TestBed.inject(HttpTestingController); let entry: string | undefined;
    sdk.recordAdministratorReceipt(id, 'csrf', 'receipt-key', fact).subscribe(value => { entry = value.paymentEntryId; expect(value.bookingId).toBe(id); expect(value.bookingVersion).toBe(2); });
    const receipt = http.expectOne('/api/v1/admin/bookings/' + id + '/receipts');
    expect(receipt.request.method).toBe('POST'); expect(receipt.request.headers.get('X-CSRF-Token')).toBe('csrf'); expect(receipt.request.headers.get('Idempotency-Key')).toBe('receipt-key'); expect(receipt.request.headers.has('If-Match')).toBe(false); expect(receipt.request.body).toEqual(fact);
    receipt.flush({ bookingId: id, paymentEntryId: payment, bookingVersion: 2 }, { status: 201, statusText: 'Created' }); expect(entry).toBe(payment);
    sdk.recordAdministratorRefund(id, 'csrf', 'refund-key', { ...fact, receiptId: payment }).subscribe();
    const refund = http.expectOne('/api/v1/admin/bookings/' + id + '/refunds'); expect(refund.request.body.receiptId).toBe(payment); expect(refund.request.headers.has('If-Match')).toBe(false); refund.flush({ bookingId: id, paymentEntryId: payment, bookingVersion: 2 });
    sdk.requireAdministratorRefund(id, 'csrf', 'required-key', { receiptId: payment, reason: 'Synthetic provider cancellation' }).subscribe();
    const required = http.expectOne('/api/v1/admin/bookings/' + id + '/refund-requirements'); expect(required.request.headers.has('If-Match')).toBe(false); required.flush({ bookingId: id, receiptId: payment, bookingVersion: 2 });
  });
  it('sends the reviewed strong version only on separate manual/external confirmations', () => {
    const sdk = TestBed.inject(AdministratorService), http = TestBed.inject(HttpTestingController); const meeting = { meetingUrl: 'https://zoom.us/j/123456789' };
    sdk.approveAdministratorBooking(id, 'csrf', 'approve-key', '"1"', meeting).subscribe(value => expect(value.version).toBe(2));
    const approval = http.expectOne('/api/v1/admin/bookings/' + id + '/approve'); expect(approval.request.headers.get('If-Match')).toBe('"1"'); expect(approval.request.body).toEqual(meeting); approval.flush({ bookingId: id, version: 2 });
    sdk.confirmAdministratorExternalPayment(id, 'csrf', 'external-key', '"2"', meeting).subscribe();
    const external = http.expectOne('/api/v1/admin/bookings/' + id + '/confirm-external-payment'); expect(external.request.headers.get('If-Match')).toBe('"2"'); expect(external.request.headers.get('Idempotency-Key')).toBe('external-key'); external.flush({ bookingId: id, version: 3 });
  });
});
