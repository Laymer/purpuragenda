import { InjectionToken } from '@angular/core';
import { RedeemProofRequest, RedeemProofRequestPurposeEnum } from '../shared/generated/model/redeemProofRequest';

/** One explicit verification attempt. Neither serialization nor routing exposes the bearer. */
export class VerificationProof {
  constructor(private request: RedeemProofRequest | null) {}
  get available(): boolean { return this.request !== null; }
  take(): RedeemProofRequest | null { const value = this.request; this.request = null; return value; }
  clear(): void { this.request = null; }
  toJSON(): string { return '[VerificationProof]'; }
}
export const VERIFICATION_PROOF = new InjectionToken<VerificationProof>('verification proof', {
  providedIn: 'root', factory: () => new VerificationProof(null),
});

/** Called before router startup. Scrub even malformed links; a failed scrub never grants an attempt. */
export function captureVerificationProof(path: string, fragment: string, scrub: () => void): VerificationProof {
  if (path.replace(/\/+$/, '') !== '/verify') return new VerificationProof(null);
  try {
    scrub();
    if (fragment.length > 200) return new VerificationProof(null);
    const fields = new URLSearchParams(fragment.replace(/^#/, ''));
    const token = fields.get('token'), purpose = fields.get('purpose');
    if ([...fields.keys()].length !== 2 || fields.getAll('token').length !== 1 || fields.getAll('purpose').length !== 1 ||
      token === null || !/^[A-Za-z0-9_-]{43}$/.test(token) || (purpose !== 'CREATE' && purpose !== 'MANAGE')) return new VerificationProof(null);
    return new VerificationProof({ token, purpose: purpose === 'CREATE' ? RedeemProofRequestPurposeEnum.Create : RedeemProofRequestPurposeEnum.Manage });
  } catch { return new VerificationProof(null); }
}
