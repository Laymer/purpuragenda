import { Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { CustomerBookingStatus, CustomerBookingStatusAllowedActionsEnum, BookingsService } from '../shared/generated';
import { BookingStatusComponent, zonedInstant } from '../shared/booking-status.component';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { AccessFailure, safeFailure } from '../shared/access/access-failure';
import { ReschedulePickerComponent, RescheduleChoice } from './reschedule-picker.component';

const STRONG_ETAG = /^"[1-9]\d{0,18}"$/;
type Phase = 'view' | 'cancel' | 'pick' | 'confirm';
type Action = 'cancel' | 'reschedule';

@Component({
  imports: [RouterLink, MatButtonModule, BookingStatusComponent, ReschedulePickerComponent],
  template: `
    <section aria-labelledby="booking-title" [attr.aria-busy]="busy()">
      <h1 id="booking-title" tabindex="-1">Your booking</h1>
      <p id="booking-message" role="status" tabindex="-1">{{ message() }}</p>
      @if (booking(); as b) {
        <app-booking-status [booking]="b" />
        @if (phase() === 'view') {
          <button mat-stroked-button type="button" (click)="load()" [disabled]="busy()">Refresh booking status</button>
          @if (canReschedule()) { <button mat-stroked-button type="button" (click)="askReschedule()" [disabled]="busy()">Change time</button> }
          @if (canCancel()) { <button mat-stroked-button type="button" (click)="askCancel()" [disabled]="busy()">Cancel this booking</button> }
        }
        @if (uncertain()) {
          <div class="panel" role="group" aria-labelledby="retry-title">
            <h2 id="retry-title" tabindex="-1">Check your request</h2>
            <p>The response did not arrive. Your {{ action() === 'cancel' ? 'cancellation' : 'new time' }} may have been accepted. Retry sends the same request; do not start a second one.</p>
            <button mat-flat-button type="button" (click)="retry()" [disabled]="busy()">Retry the same {{ action() === 'cancel' ? 'cancellation' : 'change' }}</button>
          </div>
        } @else {
          @if (phase() === 'cancel') {
            <div class="panel" role="group" aria-labelledby="cancel-title">
              <h2 id="cancel-title" tabindex="-1">Cancel this booking?</h2>
              <p>You can cancel until {{ cutoff() }}. After that time this page can no longer change the booking. Payments and refunds are handled separately and are not changed by cancelling here.</p>
              <button mat-flat-button type="button" (click)="cancel()" [disabled]="busy()">Yes, cancel this booking</button>
              <button mat-stroked-button type="button" (click)="keep()" [disabled]="busy()">Keep my booking</button>
            </div>
          }
          @if (phase() === 'pick') {
            <div class="panel" role="group" aria-labelledby="pick-title">
              <h2 id="pick-title" tabindex="-1">Choose a new time</h2>
              <p>The original change cutoff still applies: you can change this booking until {{ cutoff() }}. Your current time stays reserved until the new one is confirmed.</p>
              <app-reschedule-picker [bookingId]="b.id" [zone]="b.displayZone" (chosenSlot)="pickSlot($event)" (sessionLost)="sessionEnded()" />
              <button mat-flat-button type="button" (click)="review()" [disabled]="busy() || choice() === null">Review new time</button>
              <button mat-stroked-button type="button" (click)="keep()" [disabled]="busy()">Keep my current time</button>
            </div>
          }
          @if (phase() === 'confirm' && choice(); as picked) {
            <div class="panel" role="group" aria-labelledby="confirm-title">
              <h2 id="confirm-title" tabindex="-1">Confirm the new time</h2>
              <p>New time: {{ at(picked.slot.startInstant, picked.displayZone) }} · {{ picked.displayZone }}</p>
              <p>Current time: {{ at(b.startInstant, b.displayZone) }} · {{ b.displayZone }}</p>
              <button mat-flat-button type="button" (click)="reschedule()" [disabled]="busy()">Yes, move my booking</button>
              <button mat-stroked-button type="button" (click)="backToPick()" [disabled]="busy()">Choose a different time</button>
            </div>
          }
        }
      }
      @if (!booking() && !busy() && message()) { <button mat-stroked-button type="button" (click)="load()">Try again</button> }
      @if (lost()) { <a mat-stroked-button routerLink="/manage">Request a new link</a> }
    </section>`,
  styles: [`section { max-width: 40rem; margin: 2rem auto; padding: 1rem; } .panel { border: 1px solid #d6bfdc; border-radius: .5rem; padding: 1rem; margin-block: 1rem; background: #f8f2fa; } button, a { margin: .5rem .75rem .5rem 0; }`],
})
export class ManageBookingComponent {
  private readonly bookings = inject(BookingsService);
  private readonly commands = inject(CommandAccess);
  private readonly sessions = inject(SessionAccess);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private active = true;
  private etag: string | null = null;
  private command: FrozenCommand<unknown> | null = null;
  readonly at = zonedInstant;
  readonly booking = signal<CustomerBookingStatus | null>(null);
  readonly busy = signal(false);
  readonly phase = signal<Phase>('view');
  readonly action = signal<Action>('cancel');
  readonly choice = signal<RescheduleChoice | null>(null);
  readonly uncertain = signal(false);
  readonly lost = signal(false);
  readonly message = signal('');

  constructor() {
    const remove = this.sessions.onInvalidated(() => this.clear('Your session expired. Request a new link to manage your booking.', true));
    inject(DestroyRef).onDestroy(() => { this.active = false; remove(); this.clear('', false); });
    void this.load();
  }

  canCancel(): boolean { return this.booking()?.allowedActions.includes(CustomerBookingStatusAllowedActionsEnum.Cancel) === true; }
  canReschedule(): boolean { return this.booking()?.allowedActions.includes(CustomerBookingStatusAllowedActionsEnum.Reschedule) === true; }
  cutoff(): string { const b = this.booking(); return b === null ? '' : zonedInstant(b.customerChangesUntil, b.displayZone); }
  askCancel(): void { this.phase.set('cancel'); this.message.set(''); queueMicrotask(() => this.focus('#cancel-title')); }
  askReschedule(): void { this.choice.set(null); this.phase.set('pick'); this.message.set(''); queueMicrotask(() => this.focus('#pick-title')); }
  keep(): void { this.phase.set('view'); this.choice.set(null); }
  pickSlot(value: RescheduleChoice | null): void { this.choice.set(value); }
  review(): void { if (this.choice() !== null) { this.phase.set('confirm'); queueMicrotask(() => this.focus('#confirm-title')); } }
  backToPick(): void { this.phase.set('pick'); }
  sessionEnded(): void { this.clear('Your session expired. Request a new link to manage your booking.', true); }

  async load(): Promise<boolean> {
    const generation = this.sessions.invalidations();
    const current = () => this.active && this.sessions.invalidations() === generation;
    this.busy.set(true);
    try {
      const response = await this.sessions.perform(() => this.bookings.getGuestBooking('response'));
      if (!current() || response.body === null) return false;
      const tag = response.headers.get('ETag');
      this.etag = tag !== null && STRONG_ETAG.test(tag) ? tag : '"' + response.body.version + '"';
      this.booking.set(response.body); this.lost.set(false);
      return true;
    } catch (error) {
      if (!current()) return false;
      const failure = safeFailure(error, false);
      this.booking.set(null); this.etag = null; this.lost.set(true);
      this.message.set(failure.kind === 'REJECTED' || failure.kind === 'SESSION_EXPIRED' ? 'We could not find a booking for this browser session. Request a new link to manage your booking.' : 'Your booking could not be loaded right now. Try again shortly.');
      this.focus('#booking-message');
      return false;
    } finally { if (this.active) this.busy.set(false); }
  }

  async cancel(): Promise<void> {
    const b = this.booking();
    if (this.busy() || b === null || this.etag === null || !this.canCancel()) return;
    await this.start('cancel', '/api/v1/bookings/' + b.id + '/cancel', {});
  }

  async reschedule(): Promise<void> {
    const b = this.booking(), picked = this.choice();
    if (this.busy() || b === null || picked === null || this.etag === null || !this.canReschedule()) return;
    await this.start('reschedule', '/api/v1/bookings/' + b.id + '/reschedule', { policyVersion: picked.policyVersion, startInstant: picked.slot.startInstant });
  }

  async retry(): Promise<void> { if (!this.busy() && this.command !== null) await this.send(); }

  private async start(action: Action, path: string, body: unknown): Promise<void> {
    try {
      this.command?.discard();
      this.command = this.commands.prepare<unknown>('POST', path, body, this.etag!);
      this.action.set(action);
    } catch { this.message.set('This booking could not be changed from this page.'); this.phase.set('view'); return; }
    await this.send();
  }

  private async send(): Promise<void> {
    const command = this.command!, action = this.action(), generation = this.sessions.invalidations();
    this.busy.set(true); this.message.set('');
    try {
      await command.attempt();
      if (!this.active || this.sessions.invalidations() !== generation) return;
      this.command = null; this.uncertain.set(false); this.phase.set('view'); this.choice.set(null);
      const loaded = await this.load();
      if (this.active && loaded) this.message.set(action === 'cancel' ? 'Your cancellation was received. The current status is shown below.' : 'Your booking was moved. The current status is shown below.');
    } catch (error) {
      if (this.active && this.sessions.invalidations() === generation) await this.failure(error, action);
    } finally { if (this.active) this.busy.set(false); }
  }

  private async failure(error: unknown, action: Action): Promise<void> {
    const failure = error instanceof AccessFailure ? error : safeFailure(error, true);
    this.uncertain.set(failure.kind === 'UNCERTAIN');
    if (failure.kind === 'UNCERTAIN') { this.message.set('The response did not arrive. Your request may have succeeded.'); this.focus('#retry-title'); return; }
    this.command = null;
    if (failure.kind === 'VERSION_CONFLICT' || failure.status === 428) {
      this.phase.set('view'); this.choice.set(null);
      this.message.set(await this.load() ? 'Your booking changed. The latest details are shown; review them before changing anything.' : 'Your booking changed, but its latest details could not be loaded. Refresh and try again.');
    } else if (failure.kind === 'CONFLICT' && action === 'reschedule') {
      this.choice.set(null); this.phase.set('pick');
      this.message.set('That time is no longer available. Choose another time; your current booking is unchanged.');
    } else if (failure.kind === 'CONFLICT') {
      this.phase.set('view');
      this.message.set(await this.load() ? 'Cancelling is no longer possible for this booking. The latest details are shown.' : 'Cancelling is no longer possible, but the latest details could not be loaded. Refresh the page.');
    } else if (failure.kind === 'THROTTLED') this.message.set('Please wait a moment before trying again.');
    else if (failure.kind === 'SESSION_EXPIRED') { this.message.set('Your session expired. Request a new link to manage your booking.'); this.lost.set(true); }
    else if (failure.status === 404) { this.booking.set(null); this.lost.set(true); this.message.set('We could not find that booking. Request a new link to manage your booking.'); }
    else if (failure.status === 403) { this.phase.set('view'); this.message.set('This booking can no longer be changed by you. Contact the practitioner if you need help.'); await this.load(); }
    else { this.phase.set('view'); this.message.set(action === 'cancel' ? 'The booking could not be cancelled right now. Try again shortly.' : 'The booking could not be moved right now. Try again shortly.'); }
    this.focus('#booking-message');
  }

  private clear(message: string, lost: boolean): void {
    this.booking.set(null); this.etag = null; this.command?.discard(); this.command = null;
    this.phase.set('view'); this.choice.set(null); this.uncertain.set(false);
    if (this.active) { this.lost.set(lost); this.message.set(message); }
  }
  private focus(selector: string): void { this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(); }
}
