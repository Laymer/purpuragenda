import { InjectionToken } from '@angular/core';
import { RedeemProofRequest, RedeemProofRequestPurposeEnum } from '../shared/generated/model/redeemProofRequest';

type VerificationRequest = RedeemProofRequest | { token: string; purpose: 'ACCOUNT' };

/** One explicit verification attempt. Neither serialization nor routing exposes the bearer. */
export class VerificationProof {
  constructor(private request: VerificationRequest | null) {}
  get available(): boolean { return this.request !== null; }
  get account(): boolean { return this.request?.purpose === 'ACCOUNT'; }
  take(): RedeemProofRequest | null { if (this.request?.purpose === 'ACCOUNT') return null; const value = this.request; this.request = null; return value; }
  takeAccount(): string | null { if (this.request?.purpose !== 'ACCOUNT') return null; const token = this.request.token; this.request = null; return token; }
  clear(): void { this.request = null; }
  toJSON(): string { return '[VerificationProof]'; }
}
export const VERIFICATION_PROOF = new InjectionToken<VerificationProof>('verification proof', {
  providedIn: 'root', factory: () => new VerificationProof(null),
});

/** Called before router startup. Scrub even malformed links; a failed scrub never grants an attempt. */
export function captureVerificationProof(path: string, fragment: string, scrub: () => void): VerificationProof {
  if (path.replace(/\/+$/, '') !== '/verify') {
    if (new URLSearchParams(fragment.replace(/^#/, '')).has('token')) { try { scrub(); } catch { /* Reject even when history cannot be changed. */ } }
    return new VerificationProof(null);
  }
  try {
    scrub();
    if (fragment.length > 200) return new VerificationProof(null);
    const fields = new URLSearchParams(fragment.replace(/^#/, ''));
    const token = fields.get('token'), purpose = fields.get('purpose');
    if ([...fields.keys()].length !== 2 || fields.getAll('token').length !== 1 || fields.getAll('purpose').length !== 1 ||
      token === null || !/^[A-Za-z0-9_-]{43}$/.test(token) || (purpose !== 'CREATE' && purpose !== 'MANAGE' && purpose !== 'ACCOUNT')) return new VerificationProof(null);
    if (purpose === 'ACCOUNT') return new VerificationProof({ token, purpose });
    return new VerificationProof({ token, purpose: purpose === 'CREATE' ? RedeemProofRequestPurposeEnum.Create : RedeemProofRequestPurposeEnum.Manage });
  } catch { return new VerificationProof(null); }
}
