import { Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { AdministratorBookingDetail, AdministratorBookingDetailStateEnum, AdministratorService } from '../shared/generated';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { AccessFailure, safeFailure } from '../shared/access/access-failure';
import { brusselsDisplay, zoomHint } from './admin-time';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const STRONG_ETAG = /^"[1-9]\d{0,18}"$/;

@Component({
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule],
  template: `
    <section aria-labelledby="detail-title" [attr.aria-busy]="busy()">
      <p><a routerLink="/admin">Back to agenda</a></p>
      <h1 id="detail-title" tabindex="-1">Booking {{ booking()?.reference ?? '' }}</h1>
      <p id="detail-error" role="alert" tabindex="-1">{{ message() }}</p>
      @if (booking(); as b) {
        <dl>
          <dt>State</dt><dd>{{ b.state }}{{ b.pendingExpired ? ' (hold expired)' : '' }}</dd>
          <dt>Service</dt><dd>{{ b.snapshot.service.name }}</dd>
          <dt>Starts</dt><dd>{{ display(b.startInstant) }}</dd>
          <dt>Session ends</dt><dd>{{ display(b.sessionEndInstant) }}</dd>
          @if (b.pendingExpiresAt) { <dt>Request expires</dt><dd>{{ display(b.pendingExpiresAt) }}</dd> }
          <dt>Delivery email</dt><dd>{{ b.deliveryEmail }}</dd>
          <dt>Meeting link</dt><dd>{{ b.meetingUrl ?? 'Not set' }}</dd>
        </dl>
        <h2>Payment</h2>
        <p>Status {{ b.payment.status }}. Received {{ money(b.payment.receivedMinor) }}, refunded {{ money(b.payment.refundedMinor) }}, remaining {{ money(b.payment.remainingMinor) }} ({{ b.snapshot.service.currency }}). Approval does not imply payment.</p>
        <h2>Customer details</h2>
        <dl>
          <dt>Name</dt><dd>{{ b.snapshot.intake.firstName }} {{ b.snapshot.intake.lastName }}</dd>
          <dt>Telephone</dt><dd>{{ b.snapshot.intake.telephone }}</dd>
          <dt>Birth date</dt><dd>{{ b.snapshot.intake.birthDate }}</dd>
          <dt>Birth time</dt><dd>{{ b.snapshot.intake.birthTime }}</dd>
          <dt>Birthplace</dt><dd>{{ b.snapshot.intake.birthplace }}</dd>
          <dt>Message</dt><dd class="text">{{ b.snapshot.intake.message }}</dd>
        </dl>
        @if (canApprove()) {
          <h2>Approve</h2>
          <form (submit)="$event.preventDefault(); approve()">
            <label for="meeting-url">Zoom meeting link</label>
            <input id="meeting-url" type="url" autocomplete="off" autocapitalize="off" spellcheck="false" [formControl]="meeting" [attr.aria-invalid]="hint() !== null" [attr.aria-describedby]="hint() !== null ? 'meeting-hint' : null">
            @if (hint(); as text) { <p id="meeting-hint">{{ text }}</p> }
            <button mat-flat-button type="submit" [disabled]="busy() || uncertain()">Approve booking</button>
            @if (uncertain()) { <button mat-stroked-button type="button" (click)="retry()" [disabled]="busy()">Retry the same approval</button> }
          </form>
        }
      }
    </section>`,
  styles: [`section { max-width: 60rem; margin: 1rem auto; padding: 1rem; } dl { display: grid; grid-template-columns: max-content 1fr; gap: .3rem 1rem; } dd { margin: 0; overflow-wrap: anywhere; } .text { white-space: pre-wrap; } form { display: grid; gap: .5rem; max-width: 34rem; } input { font: inherit; min-height: 2.6rem; padding: .4rem; }`],
})
export class BookingDetailComponent {
  private readonly api = inject(AdministratorService);
  private readonly commands = inject(CommandAccess);
  private readonly sessions = inject(SessionAccess);
  private readonly router = inject(Router);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private active = true;
  private generation = 0;
  private etag: string | null = null;
  private command: FrozenCommand<unknown> | null = null;
  readonly meeting = new FormControl('', { nonNullable: true });
  readonly booking = signal<AdministratorBookingDetail | null>(null);
  readonly busy = signal(false);
  readonly uncertain = signal(false);
  readonly message = signal('');
  readonly hint = signal<string | null>(null);
  private readonly id: string;

  constructor() {
    this.id = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
    const remove = this.sessions.onInvalidated(() => { this.generation++; this.clear(); });
    inject(DestroyRef).onDestroy(() => { this.active = false; this.generation++; remove(); this.clear(); });
    void this.load();
  }

  display(instant: string): string { return brusselsDisplay(instant); }
  money(minor: number): string { return (minor / 100).toFixed(2); }
  canApprove(): boolean {
    const b = this.booking();
    return b !== null && b.state === AdministratorBookingDetailStateEnum.PendingConfirmation && !b.pendingExpired;
  }

  async load(): Promise<void> {
    if (!UUID.test(this.id)) { this.message.set('That booking could not be found.'); return; }
    const generation = ++this.generation;
    this.busy.set(true);
    try {
      const response = await firstValueFrom(this.api.getAdministratorBooking(this.id, 'response').pipe(timeout(25000)));
      if (!this.active || generation !== this.generation || response.body === null) return;
      const tag = response.headers.get('ETag');
      this.etag = tag !== null && STRONG_ETAG.test(tag) ? tag : '"' + response.body.version + '"';
      this.booking.set(response.body); this.hint.set(null);
    } catch (error) {
      if (this.active && generation === this.generation) await this.readFailure(error);
    } finally { if (this.active) this.busy.set(false); }
  }

  async approve(): Promise<void> {
    if (this.busy() || this.uncertain() || !this.canApprove()) return;
    const problem = zoomHint(this.meeting.value);
    this.hint.set(problem);
    if (problem !== null) { this.focus('#meeting-url'); return; }
    if (this.etag === null) { await this.load(); return; }
    try {
      this.command?.discard();
      this.command = this.commands.prepare<unknown>('POST', '/api/v1/admin/bookings/' + this.id + '/approve', { meetingUrl: this.meeting.value.trim() }, this.etag);
    } catch { this.message.set('That booking could not be approved from this page.'); return; }
    await this.send();
  }

  async retry(): Promise<void> { if (!this.busy() && this.command !== null) await this.send(); }

  private async send(): Promise<void> {
    const command = this.command!, generation = this.generation;
    this.busy.set(true); this.message.set('');
    try {
      await command.attempt();
      if (!this.active || generation !== this.generation) return;
      this.uncertain.set(false); this.command = null; this.meeting.reset();
      this.message.set('Booking approved.');
      await this.load();
    } catch (error) {
      if (this.active && generation === this.generation) await this.commandFailure(error);
    } finally { if (this.active) this.busy.set(false); }
  }

  private async commandFailure(error: unknown): Promise<void> {
    const failure = error instanceof AccessFailure ? error : safeFailure(error, true);
    this.uncertain.set(failure.kind === 'UNCERTAIN');
    if (failure.kind === 'SESSION_EXPIRED') { await this.router.navigateByUrl('/admin/login').catch(() => false); return; }
    if (failure.kind === 'VERSION_CONFLICT' || failure.status === 428) { this.command = null; await this.load(); this.message.set('This booking changed. The latest details are shown; review them and approve again.'); }
    else if (failure.kind === 'CONFLICT' && failure.code === 'MEETING_IN_USE') this.message.set('That meeting link is already used by another booking. Use a different link.');
    else if (failure.kind === 'CONFLICT' && failure.code === 'HOLD_EXPIRED') { this.command = null; await this.load(); this.message.set('The pending hold has expired, so this booking can no longer be approved.'); }
    else if (failure.kind === 'CONFLICT') { this.command = null; await this.load(); this.message.set('That approval conflicts with the current booking. The latest details are shown.'); }
    else if (failure.kind === 'THROTTLED') this.message.set('Please wait before trying again.');
    else if (failure.kind === 'UNCERTAIN') this.message.set('The response did not arrive. The approval may have succeeded. Use Retry to check the same approval; do not start a second one.');
    else if (failure.status === 403) this.message.set('Sign in again with a fresh authenticator code to approve bookings.');
    else this.message.set('The booking could not be approved. Check the link and try again.');
    this.focus('#detail-error');
  }

  private async readFailure(error: unknown): Promise<void> {
    const failure = safeFailure(error, false);
    if (failure.kind === 'SESSION_EXPIRED' || failure.status === 403) { await this.router.navigateByUrl('/admin/login').catch(() => false); return; }
    this.booking.set(null);
    this.message.set(failure.status === 404 ? 'That booking could not be found.' : 'The booking could not be loaded. Try again.');
    this.focus('#detail-error');
  }

  private clear(): void { this.booking.set(null); this.etag = null; this.command?.discard(); this.command = null; this.meeting.reset(); this.uncertain.set(false); }
  private focus(selector: string): void { this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(); }
}
