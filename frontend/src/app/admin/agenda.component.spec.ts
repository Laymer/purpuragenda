import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { AdministratorAgendaEntryStateEnum, provideApi } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { AgendaComponent } from './agenda.component';

const now = Date.parse('2026-10-25T23:30:00Z');
const entry = (n: number, state = AdministratorAgendaEntryStateEnum.PendingConfirmation, start = '2026-10-26T09:00:00Z') => ({ id: '00000000-0000-4000-8000-00000000000' + n, reference: 'B-synthetic-' + n, version: 1, state, startInstant: start, sessionEndInstant: '2026-10-26T10:00:00Z', serviceName: 'Synthetic consultation ' + n });
const page = (entries: unknown[], nextCursor: string | null = null) => ({ entries, nextCursor, serverNow: new Date(now).toISOString(), businessZone: 'Europe/Brussels' });
const settle = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

describe('Administrator agenda', () => {
  let http: HttpTestingController, view: AgendaComponent, router: Router;
  let fixture: ReturnType<typeof TestBed.createComponent<AgendaComponent>>;
  const list = () => http.expectOne(request => request.url === '/api/v1/admin/bookings');
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(now);
    TestBed.configureTestingModule({ imports: [AgendaComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([])] });
    http = TestBed.inject(HttpTestingController); router = TestBed.inject(Router);
    fixture = TestBed.createComponent(AgendaComponent); view = fixture.componentInstance; fixture.detectChanges();
  });
  afterEach(() => { try { http.verify(); } finally { TestBed.resetTestingModule(); vi.useRealTimers(); } });

  it('starts on the Brussels civil date, shows the pending queue and each Brussels time with its offset', async () => {
    const request = list(); expect(request.request.params.get('from')).toBe('2026-10-26'); expect(request.request.params.get('through')).toBe('2026-10-26'); expect(request.request.params.get('limit')).toBe('50');
    request.flush(page([entry(1), entry(2, AdministratorAgendaEntryStateEnum.Confirmed, '2026-10-25T01:30:00Z')])); await settle(); fixture.detectChanges();
    expect(view.pending().map(item => item.reference)).toEqual(['B-synthetic-1']);
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Awaiting confirmation (1)'); expect(text).toContain('All bookings (2)'); expect(text).toContain('UTC+1'); expect(text).toContain('confirmed');
    expect(fixture.nativeElement.querySelector('a[href="/admin/bookings/00000000-0000-4000-8000-000000000001"]')).not.toBeNull();
  });
  it('reports an empty range and pages with the server cursor without duplicating entries', async () => {
    list().flush(page([])); await settle(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Nothing is awaiting confirmation'); expect(fixture.nativeElement.textContent).toContain('No bookings');
    view.range.setValue({ from: '2026-10-26', through: '2026-11-01' }); const first = view.load(); list().flush(page([entry(1)], 'abc_-1')); await first; await settle();
    expect(view.cursor()).toBe('abc_-1'); const more = view.more(); const request = list(); expect(request.request.params.get('cursor')).toBe('abc_-1'); request.flush(page([entry(2)])); await more;
    expect(view.entries().length).toBe(2); expect(view.cursor()).toBeNull(); await view.more(); http.expectNone(r => r.url === '/api/v1/admin/bookings');
  });
  it('offers today and this week shortcuts and refuses ranges over thirty-one dates without calling the server', async () => {
    list().flush(page([])); await settle();
    view.thisWeek(); const week = list(); expect(week.request.params.get('from')).toBe('2026-10-26'); expect(week.request.params.get('through')).toBe('2026-11-01'); week.flush(page([])); await settle();
    view.range.setValue({ from: '2026-10-01', through: '2026-11-01' }); await view.load(); fixture.detectChanges();
    expect(view.message()).toContain('31'); http.expectNone(r => r.url === '/api/v1/admin/bookings');
    view.today(); list().flush(page([])); await settle();
  });
  it('coalesces a second load while busy and ignores a late response after destruction', async () => {
    const request = list(); const again = view.more(); await view.load(); await again; fixture.destroy(); request.flush(page([entry(1)])); await settle();
    expect(view.entries().length).toBe(0);
  });
  it('clears entries when the session is invalidated and ignores the late page', async () => {
    const request = list(); TestBed.inject(SessionAccess).invalidate(); request.flush(page([entry(1)])); await settle();
    expect(view.entries().length).toBe(0); expect(view.loaded()).toBe(false);
  });
  it('shows safe messages for rejected and unavailable reads without provider text', async () => {
    list().flush({ detail: 'synthetic-provider-detail' }, { status: 400, statusText: 'Bad' }); await settle();
    expect(view.message()).toContain('31'); expect(view.message()).not.toContain('provider');
    const retry = view.load(); list().flush({ detail: 'synthetic-provider-detail' }, { status: 503, statusText: 'Unavailable' }); await retry;
    expect(view.message()).toContain('could not be loaded'); expect(view.busy()).toBe(false);
  });
  it.each([401, 403])('sends a %s read to sign in and clears private state', async status => {
    const spy = vi.spyOn(router, 'navigateByUrl'); list().flush({ code: status === 401 ? 'SESSION_REQUIRED' : 'ADMIN_ACCESS_REQUIRED' }, { status, statusText: 'No' }); await settle();
    expect(spy).toHaveBeenCalledWith('/admin/login'); expect(view.entries().length).toBe(0);
  });
});
