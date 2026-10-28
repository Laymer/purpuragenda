import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideApi } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { reauthenticate, safeReturn } from './admin-reauth';

describe('Administrator re-authentication hand-off', () => {
  const id = '30000000-0000-4000-8000-000000000001';
  afterEach(() => TestBed.resetTestingModule());
  it('accepts only administrator agenda and booking pages as return targets', () => {
    expect(safeReturn('/admin/bookings/' + id + '/reschedule')).toBe('/admin/bookings/' + id + '/reschedule'); expect(safeReturn('/admin/bookings/new')).toBe('/admin/bookings/new'); expect(safeReturn('/admin')).toBe('/admin'); expect(safeReturn('/admin/bookings/' + id)).toBe('/admin/bookings/' + id);
    for (const bad of [null, undefined, '', 'https://evil.test/admin', '//evil.test', '/admin/login', '/admin/bookings/not-a-uuid', '/book', '/admin/bookings/' + id + '/x', '/admin?x=1'])
      expect(safeReturn(bad)).toBe('/admin');
  });
  it('invalidates the session and opens the sign-in form with the reason and a safe return target', async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([])] });
    const router = TestBed.inject(Router), sessions = TestBed.inject(SessionAccess), spy = vi.spyOn(router, 'navigate'), invalidate = vi.spyOn(sessions, 'invalidate');
    await reauthenticate(sessions, router, 'https://evil.test'); expect(invalidate).toHaveBeenCalled();
    expect(spy).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin' } });
    await reauthenticate(sessions, router, '/admin/bookings/' + id); expect(spy).toHaveBeenLastCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/bookings/' + id } });
  });
});
