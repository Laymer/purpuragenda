import { Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { AccountHistoryBooking, AccountHistoryCursor, AccountService } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';
import { zonedInstant } from '../shared/booking-status.component';

@Component({
  imports: [RouterLink, MatButtonModule],
  template: `
    <section aria-labelledby="account-title" [attr.aria-busy]="busy()">
      <h1 id="account-title">Your account</h1>
      <a mat-flat-button routerLink="/book">Book a consultation</a>
      <a mat-stroked-button routerLink="/account/settings">Profile and password</a>
      <button mat-stroked-button type="button" (click)="logout()" [disabled]="busy()">Sign out</button>
      <h2>Your bookings</h2><p>Times shown in Europe/Brussels, newest scheduled time first.</p>
      <button mat-stroked-button type="button" (click)="load()" [disabled]="busy() || lost()">Refresh history</button>
      <p id="history-message" role="status" tabindex="-1">{{ message() }}</p>
      @if (loaded() && entries().length === 0) { <p>No bookings are linked to this account.</p> }
      <ul>@for (entry of entries(); track entry.id) {
        <li><a [routerLink]="['/account/bookings', entry.id]">{{ entry.reference }}</a>
          <p>{{ entry.serviceName }} · {{ at(entry.startInstant) }}</p>
          <p>Status: {{ entry.state.replaceAll('_', ' ').toLowerCase() }}.</p>
          @if (entry.pendingExpired) { <p>The hold has expired and is awaiting processing.</p> }
        </li>
      }</ul>
      @if (cursor() !== null) { <button mat-stroked-button type="button" (click)="more()" [disabled]="busy()">Load older bookings</button> }
      @if (lost()) { <a mat-stroked-button routerLink="/account/login">Sign in again</a> }
    </section>`,
  styles: [`section { max-width: 48rem; margin: 1rem auto; padding: 1rem; } li { margin: 1rem 0; } ul { padding-left: 1.2rem; } button, a { margin: .5rem .75rem .5rem 0; }`],
})
export class AccountHistoryComponent {
  private readonly api = inject(AccountService);
  private readonly sessions = inject(SessionAccess);
  private readonly router = inject(Router);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private active = true;
  private generation = 0;
  readonly entries = signal<AccountHistoryBooking[]>([]);
  readonly cursor = signal<AccountHistoryCursor | null>(null);
  readonly busy = signal(false);
  readonly loaded = signal(false);
  readonly lost = signal(false);
  readonly message = signal('');
  constructor() {
    const remove = this.sessions.onInvalidated(() => { this.clear(); this.lost.set(true); this.message.set('Your session expired or changed. Sign in again to view your history.'); });
    inject(DestroyRef).onDestroy(() => { this.active = false; remove(); this.clear(); });
    void this.load();
  }
  at(instant: string): string { return zonedInstant(instant, 'Europe/Brussels'); }
  private clear(): void { this.generation++; this.entries.set([]); this.cursor.set(null); this.loaded.set(false); }
  load(): Promise<void> { if (this.busy() || this.lost()) return Promise.resolve(); this.clear(); return this.fetch(null); }
  more(): Promise<void> { return this.cursor() === null ? Promise.resolve() : this.fetch(this.cursor()); }
  private async fetch(cursor: AccountHistoryCursor | null): Promise<void> {
    if (this.busy()) return;
    const generation = ++this.generation; this.busy.set(true); this.message.set('');
    try {
      const page = await firstValueFrom(this.api.getAccountHistory(20, cursor?.beforeStart, cursor?.beforeId).pipe(timeout(25000)));
      if (!this.active || generation !== this.generation) return;
      this.entries.update(current => [...new Map([...current, ...page.bookings].map(entry => [entry.id, entry])).values()]);
      this.cursor.set(page.next); this.loaded.set(true); this.lost.set(false);
    } catch (error) {
      if (!this.active || generation !== this.generation) return;
      const failure = safeFailure(error, false);
      if (failure.kind === 'SESSION_EXPIRED' || failure.status === 403) {
        this.sessions.invalidate(); await this.router.navigateByUrl('/account/login').catch(() => false);
      } else { this.message.set('Your history could not be loaded. Try again shortly.'); this.host.nativeElement.querySelector<HTMLElement>('#history-message')?.focus(); }
    } finally { if (this.active) this.busy.set(false); }
  }
  async logout(): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true); this.clear();
    try { await this.sessions.logout(); if (this.active) await this.router.navigateByUrl('/account/login').catch(() => false); }
    catch { if (this.active) this.message.set('Private browser state was cleared. Sign out could not be confirmed; check your browser session before continuing.'); }
    finally { if (this.active) this.busy.set(false); }
  }
}
