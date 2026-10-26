import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { AppComponent } from './app.component';
import { appConfig } from './app.config';
import { CatalogService, Problem, Service, ServiceCurrencyEnum, SessionService, SessionStateRoleEnum } from './shared/generated';

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
    expect(root.querySelectorAll('a').length).toBe(3);
    expect(root.querySelector('main a')?.getAttribute('href')).toBe('/book');
  });

  it('lazy-loads public selection and reads consultations from the real API boundary', async () => {
    const fixture = TestBed.createComponent(AppComponent);
    await TestBed.inject(Router).navigateByUrl('/book'); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('h1').textContent).toBe('Choose a consultation.');
    TestBed.inject(HttpTestingController).expectOne('/api/v1/services').flush([]); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No consultations are available');
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
});
