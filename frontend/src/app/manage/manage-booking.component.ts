import { Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { CustomerBookingStatus, CustomerBookingStatusAllowedActionsEnum, BookingsService } from '../shared/generated';
import { BookingStatusComponent, zonedInstant } from '../shared/booking-status.component';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { AccessFailure, safeFailure } from '../shared/access/access-failure';

const STRONG_ETAG = /^"[1-9]\d{0,18}"$/;

@Component({
  imports: [RouterLink, MatButtonModule, BookingStatusComponent],
  template: `
    <section aria-labelledby="booking-title" [attr.aria-busy]="busy()">
      <h1 id="booking-title" tabindex="-1">Your booking</h1>
      <p id="booking-message" role="status" tabindex="-1">{{ message() }}</p>
      @if (booking(); as b) {
        <app-booking-status [booking]="b" />
        <button mat-stroked-button type="button" (click)="load()" [disabled]="busy()">Refresh booking status</button>
        @if (canCancel()) {
          @if (!confirming() && !uncertain()) { <button mat-stroked-button type="button" (click)="askCancel()" [disabled]="busy()">Cancel this booking</button> }
          @if (confirming() || uncertain()) {
            <div class="confirm" role="group" aria-labelledby="cancel-title">
              <h2 id="cancel-title" tabindex="-1">Cancel this booking?</h2>
              <p>You can cancel until {{ cutoff() }}. After that time this page can no longer change the booking. Payments and refunds are handled separately and are not changed by cancelling here.</p>
              @if (uncertain()) {
                <p>The response did not arrive. Your cancellation may have succeeded. Retry sends the same request; do not start a second one.</p>
                <button mat-flat-button type="button" (click)="retry()" [disabled]="busy()">Retry the same cancellation</button>
              } @else {
                <button mat-flat-button type="button" (click)="cancel()" [disabled]="busy()">Yes, cancel this booking</button>
                <button mat-stroked-button type="button" (click)="keep()" [disabled]="busy()">Keep my booking</button>
              }
            </div>
          }
        }
      }
      @if (!booking() && !busy() && message()) { <button mat-stroked-button type="button" (click)="load()">Try again</button> }
      @if (lost()) { <a mat-stroked-button routerLink="/manage">Request a new link</a> }
    </section>`,
  styles: [`section { max-width: 40rem; margin: 2rem auto; padding: 1rem; } .confirm { border: 1px solid #d6bfdc; border-radius: .5rem; padding: 1rem; margin-block: 1rem; background: #f8f2fa; } button, a { margin: .5rem .75rem .5rem 0; }`],
})
export class ManageBookingComponent {
  private readonly bookings = inject(BookingsService);
  private readonly commands = inject(CommandAccess);
  private readonly sessions = inject(SessionAccess);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private active = true;
  private etag: string | null = null;
  private command: FrozenCommand<unknown> | null = null;
  readonly booking = signal<CustomerBookingStatus | null>(null);
  readonly busy = signal(false);
  readonly confirming = signal(false);
  readonly uncertain = signal(false);
  readonly lost = signal(false);
  readonly message = signal('');

  constructor() {
    const remove = this.sessions.onInvalidated(() => this.clear('Your session expired. Request a new link to manage your booking.', true));
    inject(DestroyRef).onDestroy(() => { this.active = false; remove(); this.clear('', false); });
    void this.load();
  }

  canCancel(): boolean { return this.booking()?.allowedActions.includes(CustomerBookingStatusAllowedActionsEnum.Cancel) === true; }
  cutoff(): string { const b = this.booking(); return b === null ? '' : zonedInstant(b.customerChangesUntil, b.displayZone); }
  askCancel(): void { this.confirming.set(true); this.message.set(''); queueMicrotask(() => this.focus('#cancel-title')); }
  keep(): void { this.confirming.set(false); }

  async load(): Promise<void> {
    const generation = this.sessions.invalidations();
    const current = () => this.active && this.sessions.invalidations() === generation;
    this.busy.set(true);
    try {
      const response = await this.sessions.perform(() => this.bookings.getGuestBooking('response'));
      if (!current() || response.body === null) return;
      const tag = response.headers.get('ETag');
      this.etag = tag !== null && STRONG_ETAG.test(tag) ? tag : '"' + response.body.version + '"';
      this.booking.set(response.body); this.lost.set(false);
    } catch (error) {
      if (!current()) return;
      const failure = safeFailure(error, false);
      this.booking.set(null); this.etag = null; this.lost.set(true);
      this.message.set(failure.kind === 'REJECTED' || failure.kind === 'SESSION_EXPIRED' ? 'We could not find a booking for this browser session. Request a new link to manage your booking.' : 'Your booking could not be loaded right now. Try again shortly.');
      this.focus('#booking-message');
    } finally { if (this.active) this.busy.set(false); }
  }

  async cancel(): Promise<void> {
    const b = this.booking();
    if (this.busy() || b === null || this.etag === null || !this.canCancel()) return;
    try {
      this.command?.discard();
      this.command = this.commands.prepare<unknown>('POST', '/api/v1/bookings/' + b.id + '/cancel', {}, this.etag);
    } catch { this.message.set('This booking could not be cancelled from this page.'); this.confirming.set(false); return; }
    await this.send();
  }

  async retry(): Promise<void> { if (!this.busy() && this.command !== null) await this.send(); }

  private async send(): Promise<void> {
    const command = this.command!, generation = this.sessions.invalidations();
    this.busy.set(true); this.message.set('');
    try {
      await command.attempt();
      if (!this.active || this.sessions.invalidations() !== generation) return;
      this.command = null; this.uncertain.set(false); this.confirming.set(false);
      await this.load();
      if (this.active && this.booking() !== null) this.message.set('Your cancellation was received. The current status is shown below.');
    } catch (error) {
      if (this.active && this.sessions.invalidations() === generation) await this.failure(error);
    } finally { if (this.active) this.busy.set(false); }
  }

  private async failure(error: unknown): Promise<void> {
    const failure = error instanceof AccessFailure ? error : safeFailure(error, true);
    this.uncertain.set(failure.kind === 'UNCERTAIN');
    if (failure.kind === 'UNCERTAIN') { this.message.set('The response did not arrive. Your cancellation may have succeeded.'); }
    else {
      this.command = null; this.confirming.set(false);
      if (failure.kind === 'VERSION_CONFLICT' || failure.status === 428) { await this.load(); this.message.set('Your booking changed. The latest details are shown; review them before cancelling.'); }
      else if (failure.kind === 'CONFLICT') { await this.load(); this.message.set('Cancelling is no longer possible for this booking. The latest details are shown.'); }
      else if (failure.kind === 'THROTTLED') this.message.set('Please wait a moment before trying again.');
      else if (failure.kind === 'SESSION_EXPIRED') { this.message.set('Your session expired. Request a new link to manage your booking.'); this.lost.set(true); }
      else if (failure.status === 404) { this.booking.set(null); this.lost.set(true); this.message.set('We could not find that booking. Request a new link to manage your booking.'); }
      else if (failure.status === 403) { this.message.set('This booking can no longer be changed by you. Contact the practitioner if you need help.'); await this.load(); }
      else this.message.set('The booking could not be cancelled right now. Try again shortly.');
    }
    this.focus('#booking-message');
  }

  private clear(message: string, lost: boolean): void {
    this.booking.set(null); this.etag = null; this.command?.discard(); this.command = null;
    this.confirming.set(false); this.uncertain.set(false);
    if (this.active) { this.lost.set(lost); this.message.set(message); }
  }
  private focus(selector: string): void { this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(); }
}
