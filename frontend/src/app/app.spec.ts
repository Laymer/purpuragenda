import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { HttpContext, HttpErrorResponse } from '@angular/common/http';
import { AppComponent } from './app.component';
import { appConfig } from './app.config';
import { AdministratorLoginRequest, AdministratorService, CatalogService, GuestService, Problem, RedeemProofRequestPurposeEnum, Service, ServiceCurrencyEnum, SessionService, SessionStateRoleEnum } from './shared/generated';

describe('Application shell and typed API boundary', () => {
  beforeEach(() => TestBed.configureTestingModule({
    imports: [AppComponent], providers: [...appConfig.providers, provideHttpClientTesting()],
  }));
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('provides landmarks, a working home route and a keyboard skip destination', async () => {
    const fixture = TestBed.createComponent(AppComponent);
    await TestBed.inject(Router).navigateByUrl('/');
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('.skip-link')?.getAttribute('href')).toBe('#main');
    expect(root.querySelector('main#main')?.getAttribute('tabindex')).toBe('-1');
    expect(root.querySelector('h1')?.textContent).toBe('A little time for clarity.');
    expect(root.querySelector('header mat-toolbar')).not.toBeNull();
    expect(root.querySelector('footer')?.textContent).toContain('Europe/Brussels');
    expect(root.querySelector('a.brand')?.getAttribute('href')).toBe('/');
    expect(root.querySelectorAll('a').length).toBe(2);
  });

  it('renders the unknown-route recovery action and returns home', async () => {
    const fixture = TestBed.createComponent(AppComponent);
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/missing-page'); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('h1').textContent).toBe('This page is unavailable.');
    const link = fixture.nativeElement.querySelector('main a') as HTMLAnchorElement;
    expect(link.textContent?.trim()).toBe('Return home'); expect(link.getAttribute('href')).toBe('/');
    link.click(); await fixture.whenStable(); fixture.detectChanges();
    expect(router.url).toBe('/');
    expect(fixture.nativeElement.querySelector('h1').textContent).toBe('A little time for clarity.');
  });

  it('compiles the generated SDK and requests the catalog from the same origin', () => {
    const service: Service = { id: 'synthetic-service', version: 1, name: 'Synthetic consultation',
      durationMinutes: 30, priceMinor: 1500, currency: ServiceCurrencyEnum.Eur, active: true };
    let received: Service[] = [];
    TestBed.inject(CatalogService).listServices().subscribe(value => received = value);
    const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/services');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(false);
    request.flush([service]); expect(received).toEqual([service]);
  });

  it('preserves typed availability query parameters and HTTP errors for future callers', () => {
    let failure: HttpErrorResponse | undefined;
    TestBed.inject(CatalogService).availability('synthetic-service', '2026-11-01', '2026-11-02', 'Europe/Brussels')
      .subscribe({ error: (error: HttpErrorResponse) => failure = error });
    const request = TestBed.inject(HttpTestingController).expectOne(req => req.url === '/api/v1/availability');
    expect(new URL(request.request.urlWithParams, 'https://example.test').searchParams.get('displayZone')).toBe('Europe/Brussels');
    expect(request.request.params.get('from')).toBe('2026-11-01');
    expect(request.request.params.get('to')).toBe('2026-11-02');
    expect(request.request.params.get('serviceId')).toBe('synthetic-service');
    const problem: Problem = { status: 503, code: 'TEMPORARILY_UNAVAILABLE',
      correlationId: 'synthetic-correlation', retryable: true, refreshAvailability: false };
    request.flush(problem,
      { status: 503, statusText: 'Service unavailable' });
    expect(failure?.status).toBe(503); expect(failure?.error.code).toBe('TEMPORARILY_UNAVAILABLE');
  });

  it('sends the session operations to the same origin with the CSRF header and typed state', () => {
    const http = TestBed.inject(HttpTestingController); const sessions = TestBed.inject(SessionService);
    let state: unknown;
    sessions.bootstrapSession().subscribe(value => state = value);
    const bootstrap = http.expectOne('/api/v1/session/bootstrap');
    expect(bootstrap.request.method).toBe('POST'); expect(bootstrap.request.headers.has('X-CSRF-Token')).toBe(false);
    const csrfToken = 'c'.repeat(43);
    bootstrap.flush({ role: SessionStateRoleEnum.Anonymous, idleExpiresAt: '2026-10-25T10:30:00Z',
      absoluteExpiresAt: '2026-10-25T12:00:00Z', csrfToken });
    expect(state).toMatchObject({ role: 'ANONYMOUS', csrfToken });
    sessions.getSession().subscribe();
    expect(http.expectOne('/api/v1/session').request.method).toBe('GET');
    sessions.logoutSession(csrfToken).subscribe();
    const logout = http.expectOne('/api/v1/session/logout');
    expect(logout.request.method).toBe('POST'); expect(logout.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    logout.flush(null, { status: 204, statusText: 'No content' });
  });

  it('posts exact administrator factors with CSRF and consumes a typed session or safe failure', () => {
    const http = TestBed.inject(HttpTestingController); const administrator = TestBed.inject(AdministratorService);
    const csrf = 'c'.repeat(43); const credentials: AdministratorLoginRequest = {
      email: 'synthetic@example.test', password: 'synthetic private admin phrase', totp: '123456'
    };
    let role: string | undefined;
    administrator.loginAdministrator(csrf, credentials).subscribe(session => role = session.role);
    const request = http.expectOne('/api/v1/admin/login');
    expect(request.request.method).toBe('POST'); expect(request.request.headers.get('X-CSRF-Token') === csrf).toBe(true);
    expect(Object.keys(request.request.body).sort()).toEqual(['email', 'password', 'totp']);
    expect(request.request.body.password === credentials.password).toBe(true); expect(request.request.body.totp === credentials.totp).toBe(true);
    request.flush({ role: SessionStateRoleEnum.Admin, idleExpiresAt: '2026-10-26T10:15:00Z', absoluteExpiresAt: '2026-10-26T18:00:00Z', csrfToken: csrf });
    expect(role).toBe('ADMIN');
    let code: string | undefined;
    administrator.loginAdministrator(csrf, credentials).subscribe({ error: (failure: HttpErrorResponse) => code = (failure.error as Problem).code });
    http.expectOne('/api/v1/admin/login').flush({ type: 'about:blank', title: 'Authentication failed', status: 401,
      code: 'AUTHENTICATION_FAILED', correlationId: '00000000-0000-4000-8000-000000000001', retryable: false, refreshAvailability: false
    }, { status: 401, statusText: 'Unauthorized' });
    expect(code).toBe('AUTHENTICATION_FAILED');
  });

  it('sends guest proof requests and redemptions as JSON bodies with the CSRF header and typed replies', () => {
    const http = TestBed.inject(HttpTestingController); const guests = TestBed.inject(GuestService);
    const csrfToken = 'c'.repeat(43); const token = 'p'.repeat(43);
    const accepted: unknown[] = [];
    guests.requestCreateProof(csrfToken, { email: 'synthetic@example.test' }).subscribe(value => accepted.push(value));
    const create = http.expectOne('/api/v1/guest/create-proofs');
    expect(create.request.method).toBe('POST'); expect(create.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(create.request.body).toEqual({ email: 'synthetic@example.test' }); create.flush({ accepted: true });
    guests.requestManagementProof(csrfToken, { reference: 'SYNTHETIC1', email: 'synthetic@example.test' }).subscribe(value => accepted.push(value));
    const manage = http.expectOne('/api/v1/guest/management-proofs');
    expect(manage.request.body).toEqual({ reference: 'SYNTHETIC1', email: 'synthetic@example.test' }); manage.flush({ accepted: true });
    expect(accepted).toEqual([{ accepted: true }, { accepted: true }]);
    let state: unknown;
    guests.redeemGuestProof(csrfToken, { token, purpose: RedeemProofRequestPurposeEnum.Create }).subscribe(value => state = value);
    const redeem = http.expectOne('/api/v1/guest/redemptions');
    expect(redeem.request.method).toBe('POST'); expect(redeem.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(redeem.request.body).toEqual({ token, purpose: RedeemProofRequestPurposeEnum.Create });
    redeem.flush({ role: SessionStateRoleEnum.Guest, idleExpiresAt: '2026-10-25T10:30:00Z', absoluteExpiresAt: '2026-10-25T12:00:00Z', csrfToken });
    expect(state).toMatchObject({ role: 'GUEST' });
  });

  it('rejects missing required guest parameters before any request and honours per-call options', () => {
    const http = TestBed.inject(HttpTestingController); const guests = TestBed.inject(GuestService);
    const csrf = 'c'.repeat(43); const create = { email: 'synthetic@example.test' };
    const manage = { reference: 'SYNTHETIC1', email: 'synthetic@example.test' };
    const redeem = { token: 'p'.repeat(43), purpose: RedeemProofRequestPurposeEnum.Manage };
    const nothing = null as unknown as string;
    expect(() => guests.requestCreateProof(nothing, create)).toThrow(/xCSRFToken/);
    expect(() => guests.requestCreateProof(csrf, null as never)).toThrow(/createProofRequest/);
    expect(() => guests.requestManagementProof(nothing, manage)).toThrow(/xCSRFToken/);
    expect(() => guests.requestManagementProof(csrf, null as never)).toThrow(/managementProofRequest/);
    expect(() => guests.redeemGuestProof(nothing, redeem)).toThrow(/xCSRFToken/);
    expect(() => guests.redeemGuestProof(csrf, null as never)).toThrow(/redeemProofRequest/);
    const options = { httpHeaderAccept: 'application/problem+json' as const, context: new HttpContext(), transferCache: false };
    guests.requestCreateProof(csrf, create, 'response', false, options).subscribe();
    guests.requestManagementProof(csrf, manage, 'events', true, options).subscribe();
    guests.redeemGuestProof(csrf, redeem, 'response', false, options).subscribe();
    for (const url of ['/api/v1/guest/create-proofs', '/api/v1/guest/management-proofs', '/api/v1/guest/redemptions']) {
      const request = http.expectOne(url); expect(request.request.headers.get('Accept')).toBe('application/problem+json');
      request.flush({}, { status: 202, statusText: 'Accepted' });
    }
  });

  it('selects text and binary response handling for unusual Accept overrides and covers every purpose', () => {
    const http = TestBed.inject(HttpTestingController); const guests = TestBed.inject(GuestService);
    expect(Object.values(RedeemProofRequestPurposeEnum)).toEqual(['CREATE', 'MANAGE']);
    const csrf = 'c'.repeat(43); const body = { email: 'synthetic@example.test' };
    for (const accept of ['text/plain', 'application/octet-stream']) {
      guests.requestCreateProof(csrf, body, 'body', false, { httpHeaderAccept: accept as never }).subscribe();
      guests.requestManagementProof(csrf, { reference: 'SYNTHETIC1', email: body.email }, 'body', false, { httpHeaderAccept: accept as never }).subscribe();
      guests.redeemGuestProof(csrf, { token: 'p'.repeat(43), purpose: RedeemProofRequestPurposeEnum.Manage }, 'body', false, { httpHeaderAccept: accept as never }).subscribe();
      for (const url of ['/api/v1/guest/create-proofs', '/api/v1/guest/management-proofs', '/api/v1/guest/redemptions']) {
        const request = http.expectOne(url); expect(request.request.responseType).toBe(accept === 'text/plain' ? 'text' : 'blob');
        request.flush(accept === 'text/plain' ? '' : new Blob([]), { status: 202, statusText: 'Accepted' });
      }
    }
  });
});
