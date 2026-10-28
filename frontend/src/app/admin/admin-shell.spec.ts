import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideApi } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { adminGuard } from './admin-guard';
import { AdminShellComponent } from './admin-shell.component';
import { routes } from '../app.config';

const now = Date.parse('2026-10-26T10:00:00Z');
const session = (role: string) => ({ role, csrfToken: 'x'.repeat(43), idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() });
const settle = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

describe('Administrator route guard and shell', () => {
  let http: HttpTestingController, router: Router;
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now);
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([])] });
    http = TestBed.inject(HttpTestingController); router = TestBed.inject(Router);
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });
  const run = () => TestBed.runInInjectionContext(() => adminGuard({} as never, {} as never)) as Promise<unknown>;

  it('lazy-loads the guarded shell with agenda and detail children before the wildcard', async () => {
    const paths = routes.map(route => route.path), admin = routes.find(route => route.path === 'admin')!;
    expect(paths.indexOf('admin/login')).toBeLessThan(paths.indexOf('admin')); expect(paths.indexOf('admin')).toBeLessThan(paths.indexOf('**'));
    expect(admin.canActivate).toContain(adminGuard); expect(await admin.loadComponent!()).toBe(AdminShellComponent);
    expect(admin.children!.map(child => child.path)).toEqual(['', 'bookings/new', 'bookings/:id/reschedule', 'bookings/:id']);
    for (const child of admin.children!) expect(await child.loadComponent!()).toBeTruthy();
  });
  it('admits only a server-confirmed administrator and otherwise redirects to sign in', async () => {
    const allowed = run(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(session('ADMIN')); expect(await allowed).toBe(true);
    expect(await run()).toBe(true);
    TestBed.inject(SessionAccess).invalidate();
    const denied = run(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(session('GUEST'));
    expect(String(await denied)).toBe('/admin/login');
  });
  it('redirects to sign in when the session cannot be established', async () => {
    const failed = run(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(String(await failed)).toBe('/admin/login');
  });
  it('signs out, clears the session and returns to sign in; ignores a second click while busy', async () => {
    const sessions = TestBed.inject(SessionAccess), spy = vi.spyOn(router, 'navigateByUrl');
    const fixture = TestBed.createComponent(AdminShellComponent); fixture.detectChanges();
    const boot = sessions.bootstrap(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(session('ADMIN')); await boot;
    const first = fixture.componentInstance.signOut(); await fixture.componentInstance.signOut(); await settle();
    http.expectOne('/api/v1/session/logout').flush({}, { status: 503, statusText: 'Unavailable' }); await first;
    expect(sessions.state()).toBeNull(); expect(spy).toHaveBeenCalledWith('/admin/login'); expect(fixture.componentInstance.busy()).toBe(false);
  });
  it('returns to sign in when the session is invalidated elsewhere', async () => {
    const spy = vi.spyOn(router, 'navigateByUrl'); const fixture = TestBed.createComponent(AdminShellComponent); fixture.detectChanges();
    TestBed.inject(SessionAccess).invalidate(); expect(spy).toHaveBeenCalledWith('/admin/login'); fixture.destroy();
  });
});
