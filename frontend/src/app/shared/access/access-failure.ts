import { HttpErrorResponse } from '@angular/common/http';

export type FailureKind = 'SESSION_EXPIRED' | 'THROTTLED' | 'VERSION_CONFLICT' | 'CONFLICT' | 'REJECTED' | 'UNAVAILABLE' | 'UNCERTAIN';

export class AccessFailure extends Error {
  constructor(readonly kind: FailureKind, readonly status = 0, readonly retryAfterSeconds?: number) {
    super(kind); this.name = 'AccessFailure';
  }
}

export function retryAfter(header: string | null, now = Date.now()): number {
  if (header !== null && /^\d+$/.test(header)) {
    const seconds = Number(header), deadline = now + seconds * 1000;
    return Number.isSafeInteger(seconds) && Number.isSafeInteger(deadline) && Number.isFinite(new Date(deadline).getTime()) ? Math.max(1, seconds) : 60;
  }
  const deadline = header === null ? NaN : Date.parse(header);
  if (header === null || !Number.isFinite(deadline) || new Date(deadline).toUTCString() !== header) return 60;
  return Math.max(1, Math.ceil((deadline - now) / 1000));
}

export function safeFailure(error: unknown, mutation: boolean): AccessFailure {
  if (error instanceof AccessFailure) return error;
  if (!(error instanceof HttpErrorResponse)) return new AccessFailure(mutation ? 'UNCERTAIN' : 'UNAVAILABLE');
  if (error.status === 401 && error.error?.code === 'AUTHENTICATION_FAILED') return new AccessFailure('REJECTED', 401);
  if (error.status === 401 || error.error?.code === 'SESSION_REQUIRED' || error.error?.code === 'CSRF_REJECTED') return new AccessFailure('SESSION_EXPIRED', error.status);
  if (error.status === 429) return new AccessFailure('THROTTLED', 429, retryAfter(error.headers.get('Retry-After')));
  if (error.status === 412) return new AccessFailure('VERSION_CONFLICT', 412);
  if (error.status === 409) return new AccessFailure('CONFLICT', 409);
  if (error.status === 0 || error.status === 408 || (error.status >= 200 && error.status < 300) || error.status >= 500) return new AccessFailure(mutation ? 'UNCERTAIN' : 'UNAVAILABLE', error.status);
  return new AccessFailure('REJECTED', error.status);
}
