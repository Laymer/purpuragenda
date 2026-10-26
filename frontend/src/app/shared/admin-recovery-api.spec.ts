import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdministratorService } from './generated';
import { provideApi } from './generated/provide-api';

describe('Restricted administrator recovery typed HTTP boundary', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1')] }));
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });
  it('keeps setup expiry and reenrollment codes separate from ordinary authentication', () => {
    const api = TestBed.inject(AdministratorService); const http = TestBed.inject(HttpTestingController);
    const csrf = 'c'.repeat(43); const token = 'r'.repeat(43); let expires: string | undefined;
    api.beginAdministratorRecovery(csrf, { email: 'owner@example.test', password: 'synthetic password phrase', recoveryCode: 'a'.repeat(43) })
      .subscribe(value => expires = value.expiresAt);
    const begin = http.expectOne('/api/v1/admin/recovery');
    expect(begin.request.method).toBe('POST'); expect(begin.request.headers.get('X-CSRF-Token') === csrf).toBe(true);
    expect(Object.keys(begin.request.body)).toEqual(['email', 'password', 'recoveryCode']);
    begin.flush({ token, expiresAt: '2026-11-01T10:10:00Z', totpSecret: 'A'.repeat(32), provisioningUri: 'otpauth://totp/Synthetic' });
    expect(expires).toBe('2026-11-01T10:10:00Z');
    let codes: string[] = []; let fresh = false;
    api.reenrollAdministrator(csrf, { email: 'owner@example.test', password: 'synthetic password phrase', token, totp: '123456' })
      .subscribe(value => { codes = value.recoveryCodes; fresh = value.requiresFreshMfa; });
    const finish = http.expectOne('/api/v1/admin/recovery/reenrollment');
    expect(finish.request.method).toBe('POST'); expect(finish.request.headers.get('X-CSRF-Token') === csrf).toBe(true);
    expect(Object.keys(finish.request.body)).toEqual(['email', 'password', 'token', 'totp']);
    finish.flush({ recoveryCodes: Array.from({ length: 10 }, (_, index) => String(index).repeat(43)), requiresFreshMfa: true });
    expect(Array.isArray(codes)).toBe(true); expect(codes.length).toBe(10); expect(fresh).toBe(true);
    http.expectNone('/api/v1/admin/login');
  });
});
