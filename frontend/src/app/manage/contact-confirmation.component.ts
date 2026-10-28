import { Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { BookingsService, CustomerBookingStatus } from '../shared/generated';
import { VERIFICATION_PROOF } from '../booking/verification-proof';
import { SessionAccess } from '../shared/access/session-access';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { AccessFailure, safeFailure } from '../shared/access/access-failure';
import { zonedInstant } from '../shared/booking-status.component';

@Component({
  selector: 'app-contact-confirmation', imports: [RouterLink, MatButtonModule],
  template: `<section aria-labelledby="contact-title" [attr.aria-busy]="busy()">
    <h1 id="contact-title">Change booking delivery email</h1>
    <p>Opening this link does not change your booking. Confirmation uses the verified address from this email link. It does not change ownership, account login, booking details or limits.</p>
    @if (booking(); as b) { <p>Booking: {{ b.reference }} · {{ b.service.name }}</p><p>{{ at(b.startInstant, b.displayZone) }} · {{ b.displayZone }}</p> }
    <p id="contact-message" role="alert" tabindex="-1">{{ message() }}</p>
    @if (available() && !done() && !lost()) {
      @if (uncertain()) { <button mat-flat-button type="button" (click)="retry()" [disabled]="busy()">Retry the same contact change</button> }
      @else {
        @if (booking()) { <button mat-flat-button type="button" (click)="confirm()" [disabled]="busy()">Confirm delivery email change</button> }
        <button mat-stroked-button type="button" (click)="load()" [disabled]="busy()">Reload booking</button>
      }
    }
    @if (done()) { <a mat-flat-button [routerLink]="bookingLink">View booking</a> }
    @if (lost() || !available() && !done()) { <p>Sign in with the original account or recover access to the existing booking, then reopen the email link. If it expired, request a new contact link.</p><a mat-stroked-button routerLink="/account/login">Account sign in</a><a mat-stroked-button routerLink="/manage">Recover booking access</a> }
  </section>`,
  styles: [`section { max-width: 40rem; margin: 2rem auto; padding: 1rem; } button, a { margin: .5rem .75rem .5rem 0; }`],
})
export class ContactConfirmationComponent {
  private readonly proof = inject(VERIFICATION_PROOF).takeContact();
  private readonly api = inject(BookingsService); private readonly sessions = inject(SessionAccess); private readonly commands = inject(CommandAccess); private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private token = this.proof?.token ?? ''; private readonly id = this.proof?.bookingId ?? ''; private etag = ''; private active = true; private generation = 0; private command: FrozenCommand<unknown> | null = null;
  readonly booking = signal<CustomerBookingStatus | null>(null); readonly available = signal(this.token !== ''); readonly busy = signal(false); readonly uncertain = signal(false); readonly lost = signal(false); readonly done = signal(false); readonly at = zonedInstant;
  readonly message = signal(this.token === '' ? 'This contact link is missing or invalid.' : '');
  get bookingLink(): string { return this.sessions.state()?.role === 'ACCOUNT' ? '/account/bookings/' + this.id : '/manage/booking'; }
  constructor() {
    if (this.proof !== null) this.proof.token = '';
    const remove = this.sessions.onInvalidated(() => { this.clear(); this.lost.set(true); this.message.set('Your session changed. Restore booking access and reopen this email link.'); });
    inject(DestroyRef).onDestroy(() => { this.active = false; remove(); this.clear(); });
    if (this.available()) void this.load();
  }
  private current(generation: number): boolean { return this.active && generation === this.generation; }
  async load(): Promise<boolean> {
    if (this.busy() || this.uncertain() || this.lost() || !this.available() || this.done()) return false;
    const generation = this.generation; this.busy.set(true);
    try {
      const response = await firstValueFrom(this.api.getBooking(this.id, 'response').pipe(timeout(25000))); if (!this.current(generation)) return false;
      if (response.body === null) throw new AccessFailure('UNAVAILABLE');
      const tag = response.headers.get('ETag'); this.etag = tag !== null && /^"[1-9]\d{0,18}"$/.test(tag) ? tag : '"' + response.body.version + '"'; this.booking.set(response.body); return true;
    } catch (error) {
      if (this.current(generation)) { const failure = safeFailure(error, false); this.booking.set(null);
        if (failure.kind === 'SESSION_EXPIRED' || failure.status === 403 || failure.status === 404) { this.clear(); this.lost.set(true); this.message.set('This browser cannot access the booking. Restore its original access and reopen the email link.'); }
        else this.message.set('The booking could not be loaded. Retry before confirming the change.');
      } return false;
    } finally { if (this.current(generation)) this.busy.set(false); }
  }
  async confirm(): Promise<void> {
    if (this.busy() || this.uncertain() || !this.available() || this.lost() || this.done() || this.booking() === null) return;
    try { this.command = this.commands.prepare('POST', '/api/v1/bookings/' + this.id + '/contact', { token: this.token }, this.etag); }
    catch { this.message.set('The change could not be prepared. Reload the booking before trying again.'); return; }
    await this.send();
  }
  async retry(): Promise<void> { if (!this.busy() && this.uncertain() && this.command !== null) await this.send(); }
  private async send(): Promise<void> {
    const generation = this.generation; this.busy.set(true); this.message.set('');
    try { await this.command!.attempt(); if (this.current(generation)) { this.command = null; this.token = ''; this.available.set(false); this.uncertain.set(false); this.done.set(true); this.message.set('Delivery email changed. Notices will be sent to both addresses. Booking ownership and account login are unchanged.'); } }
    catch (error) {
      if (!this.current(generation)) return; const failure = safeFailure(error, true);
      if (failure.kind === 'UNCERTAIN' || failure.kind === 'THROTTLED') { this.uncertain.set(true); this.message.set(failure.kind === 'THROTTLED' ? 'Wait, then retry the same contact change.' : 'The change may have succeeded. Retry the same request before starting another.'); return; }
      this.command?.discard(); this.command = null; this.uncertain.set(false);
      if (failure.kind === 'VERSION_CONFLICT' || failure.status === 428) { this.booking.set(null); this.busy.set(false); const loaded = await this.load(); if (this.current(generation)) this.message.set(loaded ? 'The booking changed. Review its latest details and explicitly confirm again.' : 'Reload the latest booking before confirming again.'); }
      else { this.clear(); this.lost.set(failure.kind === 'SESSION_EXPIRED' || failure.status === 403 || failure.status === 404); this.message.set('The contact link could not be used. Restore booking access if needed, then request a new contact link.'); }
      this.focus();
    } finally { if (this.current(generation)) this.busy.set(false); }
  }
  private clear(): void { this.generation++; this.token = ''; this.command?.discard(); this.command = null; this.booking.set(null); this.available.set(false); this.uncertain.set(false); this.busy.set(false); this.done.set(false); this.etag = ''; }
  private focus(): void { if (this.active) this.host.nativeElement.querySelector<HTMLElement>('#contact-message')?.focus(); }
}
