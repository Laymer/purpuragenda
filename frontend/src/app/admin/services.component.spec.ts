import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { AdministratorServiceDefinition, AdministratorServiceDefinitionCurrencyEnum, provideApi } from '../shared/generated';
import { CommandAccess } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { ServicesComponent } from './services.component';

const id = '10000000-0000-4000-8000-000000000001', path = '/api/v1/admin/configuration/services', now = Date.parse('2026-10-28T10:00:00Z');
const service: AdministratorServiceDefinition = { id, version: 7, name: 'Synthetic consultation', durationMinutes: 60, priceMinor: 10000, currency: AdministratorServiceDefinitionCurrencyEnum.Eur, active: true };
const session = { role: 'ADMIN', csrfToken: 'x'.repeat(43), idleExpiresAt: new Date(now + 600000).toISOString(), absoluteExpiresAt: new Date(now + 1200000).toISOString() };
const settle = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };

describe('Administrator service management', () => {
  let http: HttpTestingController, router: Router;
  let fixture: ReturnType<typeof TestBed.createComponent<ServicesComponent>>, view: ServicesComponent;
  const create = () => { fixture = TestBed.createComponent(ServicesComponent); view = fixture.componentInstance; fixture.detectChanges(); };
  async function loaded(services = [service]) { create(); http.expectOne('/api/v1/admin/configuration').flush({ services }); await settle(); fixture.detectChanges(); }
  const fill = () => view.form.setValue({ name: 'Synthetic new consultation', duration: 120, price: '0.29', reason: 'Synthetic service reason' });
  async function post(suffix = '') { await settle(); http.match('/api/v1/session/bootstrap').forEach(r => r.flush(session)); await settle(); return http.expectOne(path + suffix); }
  async function reject(code: string, status = 409) { const p = view.save(); (await post('/' + id)).flush({ code, detail: 'synthetic-provider-error' }, { status, statusText: 'x' }); await p; }
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now);
    TestBed.configureTestingModule({ imports: [ServicesComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([])] });
    http = TestBed.inject(HttpTestingController); router = TestBed.inject(Router);
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });

  it('lists active/archived services and explains immutable booking snapshots', async () => {
    await loaded([service, { ...service, id: 'archived', name: '<b>Synthetic archived</b>', active: false }]);
    expect(fixture.nativeElement.textContent).toContain('Archived'); expect(fixture.nativeElement.textContent).toContain('Existing bookings retain'); expect(fixture.nativeElement.textContent).toContain('<b>Synthetic archived</b>'); expect(fixture.nativeElement.querySelector('li b')).toBeNull();
    view.edit(service); expect(view.form.controls.price.value).toBe('100.00'); expect(view.selected()?.version).toBe(7);
    view.create(); expect(view.selected()).toBeNull(); expect(view.form.controls.name.value).toBe('');
  });
  it('creates an active service with exact cents and no version header, then refreshes', async () => {
    await loaded([]); expect(fixture.nativeElement.textContent).toContain('No services yet'); fill(); const p = view.save(); const r = await post();
    expect(JSON.parse(r.request.body)).toEqual({ name: 'Synthetic new consultation', durationMinutes: 120, priceMinor: 29, reason: 'Synthetic service reason' }); expect(r.request.headers.has('If-Match')).toBe(false);
    r.flush({ id, version: 1 }); await settle(); http.expectOne('/api/v1/admin/configuration').flush({ services: [service] }); await p; expect(view.message()).toContain('saved'); expect(view.form.controls.name.value).toBe('');
  });
  it.each(['0', '0.00', '1000000.00'])('accepts the EUR boundary %s', async price => {
    await loaded(); fill(); view.form.controls.price.setValue(price); const p = view.save(); const r = await post(); expect(JSON.parse(r.request.body).priceMinor).toBe(price.startsWith('0') ? 0 : 100000000); r.flush({}, { status: 400, statusText: 'x' }); await p;
  });
  it('requires valid bounded fields and a normalized audited reason', async () => {
    await loaded(); await view.setActive(); await view.save(); expect(view.message()).toContain('Enter a name');
    fill(); for (const price of ['-1', '1.001', '1000000.01', 'bad']) { view.form.controls.price.setValue(price); await view.save(); expect(view.message()).toContain('exact EUR'); }
    fill(); for (const duration of [0, 121, 1.5]) { view.form.controls.duration.setValue(duration); await view.save(); expect(view.message()).toContain('whole duration'); }
    fill(); view.form.controls.reason.setValue('short'); await view.save(); expect(view.message()).toContain('reason');
    view.edit(service); view.form.controls.reason.setValue('short'); await view.setActive(); expect(view.message()).toContain('reason');
  });
  it('updates with the reviewed service version and a new idempotency key', async () => {
    await loaded(); view.edit(service); fill(); const p = view.save(); const r = await post('/' + id); expect(r.request.headers.get('If-Match')).toBe('"7"'); expect(r.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    r.flush({ id, version: 8 }); await settle(); http.expectOne('/api/v1/admin/configuration').flush({ services: [{ ...service, version: 8 }] }); await p; expect(view.selected()).toBeNull();
  });
  it.each([true, false])('changes active=%s using only a reason and strong version', async active => {
    await loaded([{ ...service, active }]); view.edit({ ...service, active }); view.form.controls.reason.setValue('Synthetic state change'); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(active ? 'Archive service' : 'Restore service'); const p = view.setActive(); const r = await post('/' + id + (active ? '/archive' : '/restore'));
    expect(JSON.parse(r.request.body)).toEqual({ reason: 'Synthetic state change' }); expect(r.request.headers.get('If-Match')).toBe('"7"'); r.flush({ id, version: 8 }); await settle(); http.expectOne('/api/v1/admin/configuration').flush({ services: [{ ...service, active: !active }] }); await p;
  });
  it.each([412, 428, 404, 409])('requires reload and a new review after HTTP%s', async status => {
    await loaded(); view.edit(service); fill(); await reject('STALE_VERSION', status); expect(view.services()).toBeNull(); expect(view.selected()).toBeNull(); expect(view.message()).toContain('Refresh');
    await view.save(); const p = view.load(); http.expectOne('/api/v1/admin/configuration').flush({ services: [{ ...service, version: 8 }] }); await p; expect(view.form.controls.name.value).toBe('');
  });
  it('keeps uncertain changes frozen across throttling and prevents competing edits', async () => {
    await loaded(); view.edit(service); fill(); const p = view.save(); const r = await post('/' + id); const body = r.request.body, key = r.request.headers.get('Idempotency-Key');
    await view.save(); await view.setActive(); await view.load(); await view.retry(); view.create(); view.edit({ ...service, name: 'Changed' }); expect(view.selected()?.name).toBe(service.name);
    r.flush({}, { status: 503, statusText: 'x' }); await p; expect(view.uncertain()).toBe(true); view.create(); view.edit(service); await view.save(); await view.setActive(); await view.load();
    const retry = view.retry(); const limited = await post('/' + id); limited.flush({}, { status: 429, statusText: 'x', headers: { 'Retry-After': '2' } }); await retry; await view.retry(); expect(view.uncertain()).toBe(true); expect(view.message()).toContain('still unknown');
    await vi.advanceTimersByTimeAsync(2001); const last = view.retry(); const success = await post('/' + id); expect(success.request.body).toBe(body); expect(success.request.headers.get('Idempotency-Key')).toBe(key);
    success.flush({ id, version: 8 }); await settle(); http.expectOne('/api/v1/admin/configuration').flush({ services: [service] }); await last; await view.retry();
  });
  it('clears private reasons on MFA expiry and returns to services', async () => {
    const nav = vi.spyOn(router, 'navigate'); await loaded(); view.edit(service); fill(); await reject('AUTHENTICATION_REQUIRED', 403);
    expect(nav).toHaveBeenCalledWith(['/admin/login'], { queryParams: { reason: 'reauth', return: '/admin/services' } }); expect(view.services()).toBeNull(); expect(view.form.controls.reason.value).toBe('');
  });
  it('clears state on session expiry and sends expired reads to sign-in', async () => {
    await loaded(); view.edit(service); fill(); await reject('SESSION_REQUIRED', 401); expect(view.services()).toBeNull();
    const nav = vi.spyOn(router, 'navigateByUrl'); const p = view.load(); http.expectOne('/api/v1/admin/configuration').flush({}, { status: 401, statusText: 'x' }); await p; expect(nav).toHaveBeenCalledWith('/admin/login');
  });
  it('retains success evidence if the follow-up refresh fails', async () => {
    await loaded(); fill(); const p = view.save(); (await post()).flush({ id, version: 1 }); await settle(); http.expectOne('/api/v1/admin/configuration').flush({}, { status: 503, statusText: 'x' }); await p; expect(view.message()).toContain('saved, but'); expect(view.services()).toBeNull();
  });
  it.each([[400, 'could not be saved'], [429, 'wait']])('handles HTTP%s without exposing errors', async (status, hint) => {
    await loaded(); view.edit(service); fill(); await reject('BAD', status as number); expect(view.message()).toContain(hint); expect(view.message()).not.toContain('provider');
  });
  it('handles preparation failure and ignores late mutation completion', async () => {
    await loaded(); fill(); const prepare = vi.spyOn(TestBed.inject(CommandAccess), 'prepare').mockImplementation(() => { throw new Error(); }); await view.save(); expect(view.message()).toContain('could not be submitted'); prepare.mockRestore();
    const p = view.save(); const r = await post(); TestBed.inject(SessionAccess).invalidate(); r.flush({ id, version: 1 }); await p; expect(view.services()).toBeNull();
  });
  it('ignores late reads after destruction', async () => {
    create(); const r = http.expectOne('/api/v1/admin/configuration'); fixture.destroy(); r.flush({ services: [service] }); await settle(); expect(view.services()).toBeNull();
  });
});
