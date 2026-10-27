import { HttpClient, HttpHeaders } from '@angular/common/http';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { AccessFailure } from './access-failure';
import { SessionAccess } from './session-access';

type CommandState = 'ready' | 'sending' | 'succeeded' | 'rejected' | 'uncertain' | 'expired';
type Method = 'POST' | 'PUT' | 'PATCH' | 'DELETE';

/** One immutable submission, kept only in memory; retry is an explicit caller action. */
export class FrozenCommand<T> {
  #state = signal<CommandState>('ready');
  readonly state = this.#state.asReadonly();
  #attempt: Promise<T> | null = null;
  #result?: T;
  #retryUntil = 0;
  #body: string;
  #headers: HttpHeaders;

  constructor(private readonly sessions: SessionAccess, private readonly http: HttpClient,
    private readonly method: Method, private readonly path: string, body: unknown, version?: string) {
    if (!['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) throw new AccessFailure('REJECTED');
    if (!/^\/api\/v1\/[a-z0-9/-]+$/.test(path) || (version !== undefined && !/^"[1-9]\d*"$/.test(version))) throw new AccessFailure('REJECTED');
    let serialized: string | undefined;
    try { serialized = JSON.stringify(body); } catch { throw new AccessFailure('REJECTED'); }
    if (serialized === undefined) throw new AccessFailure('REJECTED');
    this.#body = serialized;
    this.#headers = new HttpHeaders({ 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() });
    if (version !== undefined) this.#headers = this.#headers.set('If-Match', version);
  }

  attempt(): Promise<T> {
    if (this.#state() === 'expired') return Promise.reject(new AccessFailure('SESSION_EXPIRED'));
    if (Date.now() < this.#retryUntil) return Promise.reject(new AccessFailure('THROTTLED', 429, Math.ceil((this.#retryUntil - Date.now()) / 1000)));
    if (this.#attempt !== null) return this.#attempt;
    if (this.#state() === 'succeeded') return Promise.resolve(this.#result!);
    this.#state.set('sending');
    const request = this.sessions.perform(csrf => {
      if (this.#state() === 'expired') throw new AccessFailure('SESSION_EXPIRED');
      return this.http.request<T>(this.method, this.path, { body: this.#body, headers: this.#headers.set('X-CSRF-Token', csrf) });
    }).then(result => {
      if (this.#state() === 'expired') throw new AccessFailure('SESSION_EXPIRED');
      this.#result = result; this.#body = ''; this.#state.set('succeeded'); return result;
    }, error => {
      if (error.kind === 'THROTTLED') this.#retryUntil = Date.now() + error.retryAfterSeconds * 1000;
      if (this.#state() !== 'expired') this.#state.set(error.kind === 'SESSION_EXPIRED' ? 'expired' : error.kind === 'UNCERTAIN' ? 'uncertain' : 'rejected');
      throw error;
    });
    this.#attempt = request;
    const clear = () => { this.#attempt = null; }; request.then(clear, clear);
    return request;
  }

  discard(): void { this.#retryUntil = 0; this.#body = ''; this.#result = undefined; this.#headers = new HttpHeaders(); this.#state.set('expired'); }
  toString(): string { return '[FrozenCommand]'; }
  toJSON(): string { return this.toString(); }
}

@Injectable({ providedIn: 'root' })
export class CommandAccess {
  private readonly sessions = inject(SessionAccess);
  private readonly http = inject(HttpClient);
  #commands = new Set<FrozenCommand<unknown>>();
  constructor() {
    const remove = this.sessions.onInvalidated(() => { this.#commands.forEach(command => command.discard()); this.#commands.clear(); });
    inject(DestroyRef).onDestroy(remove);
  }
  prepare<T>(method: Method, path: string, body: unknown, version?: string): FrozenCommand<T> {
    const command = new FrozenCommand<T>(this.sessions, this.http, method, path, body, version);
    this.#commands.add(command); return command;
  }
}
