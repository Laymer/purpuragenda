import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { Observable, firstValueFrom, timeout } from 'rxjs';
import { SessionBootstrap, SessionService, SessionState, SessionStateRoleEnum } from '../generated';
import { AccessFailure, safeFailure } from './access-failure';

/** Secrets stay in this instance. All CSRF-consuming work shares one rotation queue. */
@Injectable({ providedIn: 'root' })
export class SessionAccess {
  private readonly api = inject(SessionService);
  private readonly destroy = inject(DestroyRef);
  #snapshot = signal<SessionState | null>(null);
  #invalidations = signal(0);
  readonly state = this.#snapshot.asReadonly();
  readonly invalidations = this.#invalidations.asReadonly();
  #listeners = new Set<() => void>();
  #csrf: string | null = null;
  #epoch = 0;
  #tail: Promise<void> = Promise.resolve();
  #bootstrap: Promise<SessionState> | null = null;
  #timer?: ReturnType<typeof setTimeout>;

  constructor() {
    this.destroy.onDestroy(() => this.invalidate());
  }

  /** Refreshes the server-owned browser context; callers share an in-flight refresh. */
  bootstrap(): Promise<SessionState> {
    if (this.#bootstrap !== null) return this.#bootstrap;
    const epoch = this.#epoch;
    const request = this.serial(() => this.load(epoch));
    this.#bootstrap = request;
    const clear = () => { if (this.#bootstrap === request) this.#bootstrap = null; };
    request.then(clear, clear); return request;
  }

  /** Authentication consumes the rotated proof here; views receive only public context. */
  authenticate(send: (csrf: string) => Observable<SessionBootstrap>): Promise<SessionState> {
    return this.run(send, result => result).then(() => this.#snapshot()!);
  }

  /** The callback must issue exactly one request and must never log its borrowed proof. */
  perform<T>(send: (csrf: string) => Observable<T>): Promise<T> { return this.run(send); }

  private run<T>(send: (csrf: string) => Observable<T>, rotation?: (result: T) => SessionBootstrap): Promise<T> {
    const epoch = this.#epoch;
    return this.serial(async () => {
      try {
        this.checkEpoch(epoch);
        if (!this.live()) {
          if (this.#snapshot() !== null) { this.invalidate(); throw new AccessFailure('SESSION_EXPIRED'); }
          await this.load(epoch);
        }
        const result = await firstValueFrom(send(this.#csrf!).pipe(timeout(25000)));
        this.checkEpoch(epoch);
        if (rotation !== undefined) { const reply = rotation(result); this.invalidate(); this.accept(reply); }
        return result;
      } catch (error) {
        const failure = safeFailure(error, true);
        if ((failure.kind === 'SESSION_EXPIRED' || (rotation !== undefined && failure.kind === 'UNCERTAIN')) && epoch === this.#epoch) this.invalidate();
        throw failure;
      }
    });
  }

  toJSON(): string { return '[SessionAccess]'; }

  logout(): Promise<void> {
    return this.perform(csrf => this.api.logoutSession(csrf)).then(() => {}).finally(() => this.invalidate());
  }

  onInvalidated(listener: () => void): () => void { this.#listeners.add(listener); return () => { this.#listeners.delete(listener); }; }

  /** Views use this generation to clear intake/private state after expiry or revocation. */
  invalidate(): void {
    clearTimeout(this.#timer); this.#csrf = null; this.#snapshot.set(null);
    this.#epoch++; this.#invalidations.update(value => value + 1); this.#bootstrap = null; this.#listeners.forEach(listener => { try { listener(); } catch { /* One view cannot block other privacy invalidations. */ } });
  }

  private serial<T>(work: () => Promise<T>): Promise<T> {
    const result = this.#tail.then(work);
    this.#tail = result.then(() => {}, () => {}); return result;
  }

  private async load(epoch: number): Promise<SessionState> {
    try {
      this.checkEpoch(epoch);
      const reply = await firstValueFrom(this.api.bootstrapSession().pipe(timeout(25000)));
      this.checkEpoch(epoch);
      // A cookie may have changed in another tab; a deliberate refresh starts new intents.
      if (this.#snapshot() !== null) this.invalidate();
      this.accept(reply); return this.#snapshot()!;
    } catch (error) { const failure = safeFailure(error, false); if (epoch === this.#epoch) this.invalidate(); throw failure; }
  }

  private live(): boolean {
    const value = this.#snapshot();
    return this.#csrf !== null && value !== null && Date.now() < Math.min(Date.parse(value.idleExpiresAt), Date.parse(value.absoluteExpiresAt));
  }

  private checkEpoch(epoch: number): void {
    if (epoch !== this.#epoch) throw new AccessFailure('SESSION_EXPIRED');
  }

  private accept(reply: SessionBootstrap): void {
    const expiry = Math.min(Date.parse(reply.idleExpiresAt), Date.parse(reply.absoluteExpiresAt));
    if (!Number.isFinite(expiry) || expiry <= Date.now() || typeof reply.csrfToken !== 'string' || reply.csrfToken.length === 0) throw new AccessFailure('SESSION_EXPIRED');
    const roles = { ANONYMOUS: SessionStateRoleEnum.Anonymous, GUEST: SessionStateRoleEnum.Guest, ACCOUNT: SessionStateRoleEnum.Account, ADMIN: SessionStateRoleEnum.Admin };
    if (!Object.hasOwn(roles, reply.role) || expiry - Date.now() > 2147483647) throw new AccessFailure('SESSION_EXPIRED');
    this.#csrf = reply.csrfToken;
    this.#snapshot.set(Object.freeze({ role: roles[reply.role], idleExpiresAt: reply.idleExpiresAt, absoluteExpiresAt: reply.absoluteExpiresAt }));
    clearTimeout(this.#timer); this.#timer = setTimeout(() => this.invalidate(), expiry - Date.now());
  }
}
