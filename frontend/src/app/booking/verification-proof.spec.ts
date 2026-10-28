import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideApi } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { captureVerificationProof, VERIFICATION_PROOF } from './verification-proof';
import { VerifyEmailComponent } from './verify-email.component';

const token = 'a'.repeat(43);
const settle = async () => { for (let i = 0; i < 15; i++) await Promise.resolve(); };
const reply = (role = 'ANONYMOUS') => ({ role, csrfToken: 'synthetic-csrf', idleExpiresAt: new Date(Date.now() + 60000).toISOString(), absoluteExpiresAt: new Date(Date.now() + 120000).toISOString() });

describe('Verification fragment privacy', () => {
  it.each(['CREATE', 'MANAGE'])('scrubs before accepting one %s proof and consumes it once', purpose => {
    const scrub = vi.fn(); const proof = captureVerificationProof('/verify/', `#token=${token}&purpose=${purpose}`, scrub);
    expect(scrub).toHaveBeenCalledOnce(); expect(proof.available).toBe(true);
    expect(JSON.stringify(proof).includes(token)).toBe(false);
    expect(proof.take()?.purpose).toBe(purpose); expect(proof.take()).toBeNull();
  });
  it('keeps ACCOUNT proofs separate from guest redemption and consumes each purpose only once', () => {
    const scrub = vi.fn(); const account = captureVerificationProof('/verify', `#token=${token}&purpose=ACCOUNT`, scrub);
    expect(scrub).toHaveBeenCalledOnce(); expect(account.account).toBe(true); expect(account.take()).toBeNull(); expect(account.available).toBe(true); expect(JSON.stringify(account).includes(token)).toBe(false); expect(account.takeAccount() === token).toBe(true); expect(account.takeAccount()).toBeNull(); expect(account.available).toBe(false);
    const guest = captureVerificationProof('/verify', `#token=${token}&purpose=CREATE`, scrub); expect(guest.takeAccount()).toBeNull(); expect(guest.available).toBe(true); expect(guest.take()?.purpose).toBe('CREATE');
  });
  it.each(['', '#token=a&purpose=CREATE', `#token=${token}`, `#purpose=CREATE`,
    `#token=${token}&purpose=UNKNOWN`, `#token=${token}&purpose=CREATE&purpose=MANAGE`,
    `#token=${token}&token=${token}&purpose=CREATE`, `#token=${token}&purpose=CREATE&next=/admin`, '#'+ 'x'.repeat(201)])('scrubs and rejects malformed fragments', fragment => {
    const scrub = vi.fn(); expect(captureVerificationProof('/verify', fragment, scrub).available).toBe(false); expect(scrub).toHaveBeenCalledOnce();
  });
  it.each(['/book', '/verif', '/%76erify'])('scrubs a recognizable bearer on unsupported path %s without accepting it', path => {
    const scrub = vi.fn();
    expect(captureVerificationProof(path, `#token=${token}&purpose=CREATE`, scrub).available).toBe(false);
    expect(scrub).toHaveBeenCalledOnce();
  });
  it('leaves ordinary anchors alone and fails closed when history cannot be scrubbed', () => {
    const scrub = vi.fn(); expect(captureVerificationProof('/book', '#main-content', scrub).available).toBe(false); expect(scrub).not.toHaveBeenCalled();
    expect(captureVerificationProof('/verify', `#token=${token}&purpose=CREATE`, () => { throw Error('synthetic'); }).available).toBe(false);
    const proof = captureVerificationProof('/verify', `token=${token}&purpose=CREATE`, scrub); proof.clear(); expect(proof.available).toBe(false);
  });
});

describe('Explicit email verification through the real typed transport', () => {
  let http: HttpTestingController;
  function setup(fragment = `#token=${token}&purpose=CREATE`) {
    TestBed.configureTestingModule({ imports: [VerifyEmailComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideApi('/api/v1'), provideRouter([]),
      { provide: VERIFICATION_PROOF, useValue: captureVerificationProof('/verify', fragment, () => {}) }] });
    http = TestBed.inject(HttpTestingController); const fixture = TestBed.createComponent(VerifyEmailComponent); fixture.detectChanges(); return fixture;
  }
  afterEach(() => { TestBed.resetTestingModule(); http.verify(); });
  it.each(['CREATE', 'MANAGE'])('never redeems %s on navigation and rotates only after an explicit click, with no storage or token rendered', async purpose => {
    const storage = vi.spyOn(Storage.prototype, 'setItem'); const f = setup(`#token=${token}&purpose=${purpose}`); http.expectNone('/api/v1/guest/redemptions');
    expect(f.nativeElement.textContent.includes(token)).toBe(false);
    const work = f.componentInstance.verify(); await f.componentInstance.verify(); await settle();
    http.expectOne('/api/v1/session/bootstrap').flush(reply()); await settle();
    const redemption = http.expectOne('/api/v1/guest/redemptions');
    expect(redemption.request.body.token === token).toBe(true); expect(redemption.request.headers.has('X-CSRF-Token')).toBe(true);
    redemption.flush(reply('GUEST')); await work; f.detectChanges();
    expect(f.componentInstance.verified()).toBe(true); expect(TestBed.inject(SessionAccess).state()?.role).toBe('GUEST');
    expect(f.nativeElement.querySelector('a').getAttribute('href')).toBe(purpose === 'CREATE' ? '/book' : '/manage/booking');
    await f.componentInstance.verify(); http.expectNone('/api/v1/guest/redemptions'); expect(storage).not.toHaveBeenCalled(); storage.mockRestore();
  });
  it('sends a failed management verification back to request a new management link', async () => {
    const f = setup(`#token=${token}&purpose=MANAGE`); const work = f.componentInstance.verify(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply()); await settle();
    http.expectOne('/api/v1/guest/redemptions').flush({}, { status: 401, statusText: 'Unauthorized' }); await work; f.detectChanges();
    expect(f.componentInstance.verified()).toBe(false); expect(f.nativeElement.querySelector('a').getAttribute('href')).toBe('/manage'); expect(f.nativeElement.textContent).toContain('Request a new link');
  });
  it('does not show or retry arbitrary provider errors', async () => {
    const f = setup(); const work = f.componentInstance.verify(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply()); await settle();
    http.expectOne('/api/v1/guest/redemptions').flush({ detail: 'synthetic-provider-detail' }, { status: 401, statusText: 'Unauthorized' }); await work; f.detectChanges();
    expect(f.nativeElement.textContent).not.toContain('synthetic-provider-detail'); expect(f.componentInstance.verified()).toBe(false);
    expect(f.componentInstance.available()).toBe(false); expect(f.componentInstance.busy()).toBe(false);
  });
  it('clears abandoned links and ignores late authentication results after navigation', async () => {
    const f = setup(); const work = f.componentInstance.verify(); await settle(); http.expectOne('/api/v1/session/bootstrap').flush(reply()); await settle();
    const request = http.expectOne('/api/v1/guest/redemptions'); f.destroy(); request.flush(reply('GUEST')); await work;
    expect(f.componentInstance.verified()).toBe(false); expect(TestBed.inject(VERIFICATION_PROOF).available).toBe(false);
  });
  it('renders a safe missing-link path without making any authentication request', async () => {
    const f = setup(''); await f.componentInstance.verify(); expect(f.componentInstance.message()).toContain('missing or invalid');
  });
});
