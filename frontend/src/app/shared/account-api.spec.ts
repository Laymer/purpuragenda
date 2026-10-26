import { TestBed } from '@angular/core/testing';
import { provideHttpClient, HttpErrorResponse } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { AccountService, SessionBootstrapRoleEnum } from './generated';

/** Protocol coverage for generated code excluded individually by committed generator provenance. */
describe('Private account credential protocol', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] }));
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('sends credentials only in the guarded POST body and receives the rotated private bootstrap', () => {
    const api = TestBed.inject(AccountService), http = TestBed.inject(HttpTestingController);
    const csrf = 'SYNTHETIC_CSRF_PROOF', password = 'SYNTHETIC_ACCOUNT_PASSWORD'; let role: string | undefined;
    api.loginAccount(csrf, { email: 'account@example.test', password }).subscribe(value => { role = value.role; expect(value.csrfToken === 'SYNTHETIC_ROTATED_PROOF').toBe(true); });
    const requests = http.match(() => true); expect(requests.length).toBe(1); const request = requests[0]; expect(request.request.url === '/api/v1/account/login').toBe(true); expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-Token') === csrf).toBe(true); expect(request.request.body.password === password).toBe(true);
    expect(request.request.params.keys()).toEqual([]); expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({ role: SessionBootstrapRoleEnum.Account, csrfToken: 'SYNTHETIC_ROTATED_PROOF', idleExpiresAt: '2026-10-26T12:30:00Z', absoluteExpiresAt: '2026-10-27T00:00:00Z' });
    expect(role).toBe('ACCOUNT');
  });
  it('keeps current and replacement passwords private and never automatically retries uncertain credential actions', () => {
    const api = TestBed.inject(AccountService), http = TestBed.inject(HttpTestingController);
    const csrf = 'SYNTHETIC_CSRF_PROOF', currentPassword = 'SYNTHETIC_CURRENT_PASSWORD', newPassword = 'SYNTHETIC_REPLACEMENT_PASSWORD'; let failure: HttpErrorResponse | undefined;
    api.changeAccountPassword(csrf, { email: 'account@example.test', currentPassword, newPassword }).subscribe({ error: error => failure = error });
    const requests = http.match(() => true); expect(requests.length).toBe(1); const request = requests[0]; expect(request.request.url === '/api/v1/account/password').toBe(true); expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-Token') === csrf).toBe(true); expect(request.request.body.currentPassword === currentPassword).toBe(true); expect(request.request.body.newPassword === newPassword).toBe(true);
    expect(request.request.params.keys()).toEqual([]); request.error(new ProgressEvent('network')); expect(failure?.status).toBe(0); expect(http.match(() => true).length).toBe(0);
  });
});
