import { Component, DestroyRef, ElementRef, afterRenderEffect, inject, signal } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { ServiceTimeComponent } from '../public/service-time.component';
import { ConsultationSelection, offsetLabel, slotLabel } from '../public/selection-model';
import { BookingReceipt, BookingReviewContext, BookingsService, CatalogService, CustomerBookingStatus, GuestService } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { BookingStatusComponent, zonedInstant } from '../shared/booking-status.component';
import { AccessFailure, safeFailure } from '../shared/access/access-failure';

@Component({
  imports: [ServiceTimeComponent, BookingStatusComponent, ReactiveFormsModule, MatButtonModule, CurrencyPipe],
  templateUrl: './booking-flow.component.html', styleUrl: './booking-flow.component.css',
})
export class BookingFlowComponent {
  private readonly sessions = inject(SessionAccess);
  private readonly commands = inject(CommandAccess);
  private readonly catalog = inject(CatalogService);
  private readonly guest = inject(GuestService);
  private readonly bookings = inject(BookingsService);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private active = true;
  private submission: FrozenCommand<BookingReceipt> | null = null;
  private bookingId: string | null = null;
  private retryUntil = 0;
  private timer?: ReturnType<typeof setInterval>;
  readonly phase = signal<'choose' | 'email' | 'intake' | 'review' | 'uncertain' | 'status'>('choose');
  readonly selection = signal<ConsultationSelection | null>(null);
  readonly review = signal<BookingReviewContext | null>(null);
  readonly status = signal<CustomerBookingStatus | null>(null);
  readonly busy = signal(false);
  readonly message = signal('');
  readonly retrySeconds = signal(0);
  readonly agreed = new FormControl(false, { nonNullable: true });
  readonly email = new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(/^[^\s@]+@[^\s@]+\.[^\s@]+$/u), Validators.maxLength(254)] });
  readonly intake = new FormGroup({
    firstName: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    lastName: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    telephone: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(40), Validators.pattern(/^\+[0-9 ().-]{7,39}$/)] }),
    birthDate: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(/^\d{4}-\d{2}-\d{2}$/)] }),
    birthTime: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(/^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/)] }),
    birthplace: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
    message: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(1000)] }),
  });
  readonly label = slotLabel;
  readonly offset = offsetLabel;
  readonly inZone = zonedInstant;
  constructor() {
    let previousStep = this.phase();
    afterRenderEffect(() => {
      const step = this.phase();
      if (step === previousStep) return;
      previousStep = step;
      this.host.nativeElement.querySelector<HTMLElement>(step === 'choose' ? 'h1' : '.flow > h2')?.focus();
    });
    const remove = this.sessions.onInvalidated(() => this.clearPrivate());
    inject(DestroyRef).onDestroy(() => { this.active = false; remove(); this.clearPrivate(); clearInterval(this.timer); });
  }
  choose(value: ConsultationSelection | null): void { this.selection.set(value); }
  async begin(refresh = false): Promise<void> {
    if (this.busy() || this.selection() === null) return;
    const chosen = this.selection();
    this.busy.set(true); this.message.set('');
    try {
      const context = refresh || this.sessions.state() === null ? await this.sessions.bootstrap() : this.sessions.state()!;
      if (!this.active || this.selection() !== chosen || this.sessions.state() !== context) return;
      if (context.role === 'ADMIN') { this.message.set('Sign out of the administrator session before making a customer request.'); return; }
      if (context.role !== 'ANONYMOUS') this.cooldown(0);
      this.phase.set(context.role === 'ANONYMOUS' ? 'email' : 'intake');
      if (refresh && context.role === 'ANONYMOUS') this.message.set('Open the email link in this browser and choose Verify email, then return here.');
    } catch (error) { this.failure(error, false); }
    finally { if (this.active) this.busy.set(false); }
  }
  async requestEmail(): Promise<void> {
    if (this.busy() || this.cooling()) return;
    this.email.markAsTouched(); if (this.email.invalid) return;
    this.busy.set(true); this.message.set('');
    try {
      await this.sessions.perform(csrf => this.guest.requestCreateProof(csrf, { email: this.email.value.trim() }));
      if (this.active) { this.cooldown(60); this.message.set('Check your inbox. Open the link in this browser and choose Verify email, then return here.'); }
    } catch (error) {
      this.failure(error, true);
      if (this.active && safeFailure(error, true).kind === 'UNCERTAIN') {
        this.cooldown(60); this.message.set('An email may already have been sent. Check your inbox before requesting another link.');
      }
    }
    finally { if (this.active) this.busy.set(false); }
  }
  async reviewRequest(): Promise<void> {
    if (this.busy() || this.cooling()) return;
    this.intake.markAllAsTouched();
    if (this.intake.invalid) { this.message.set('Complete the highlighted fields.'); queueMicrotask(() => this.host.nativeElement.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()); return; }
    const choice = this.selection(); if (choice === null) return;
    const generation = this.sessions.invalidations();
    this.busy.set(true); this.message.set('');
    try {
      const context = await firstValueFrom(this.catalog.getBookingContext(choice.service.id).pipe(timeout(25000)));
      if (!this.active || this.sessions.invalidations() !== generation) return;
      if (context.service.version !== choice.service.version || context.policy.version !== choice.policyVersion) {
        this.phase.set('choose'); this.selection.set(null); this.message.set('Consultation terms changed. Choose a current time and review them again.'); return;
      }
      this.review.set(context); this.agreed.setValue(false); this.phase.set('review');
    } catch (error) {
      if (this.active && this.sessions.invalidations() === generation) {
        const failure = safeFailure(error, false);
        if (failure.status === 404) {
          this.review.set(null); this.selection.set(null); this.phase.set('choose');
          this.message.set('This consultation is no longer available. Choose another consultation and review its current terms.');
        } else this.failure(failure, false);
      }
    } finally { if (this.active) this.busy.set(false); }
  }
  edit(): void { if (!this.busy() && this.phase() === 'review') { this.review.set(null); this.agreed.setValue(false); this.phase.set('intake'); } }
  async submit(): Promise<void> {
    if (this.busy() || this.cooling()) return;
    const choice = this.selection(), context = this.review();
    if (this.phase() !== 'review' || !this.agreed.value || choice === null || context === null) return;
    this.submission = this.commands.prepare<BookingReceipt>('POST', '/api/v1/bookings', {
      serviceId: context.service.id, serviceVersion: context.service.version, policyVersion: context.policy.version,
      startInstant: choice.slot.startInstant, displayZone: choice.displayZone, noticeVersion: context.notice.version, intake: this.intake.getRawValue(),
    });
    await this.send();
  }
  async retry(): Promise<void> { if (!this.busy() && !this.cooling() && this.phase() === 'uncertain') await this.send(); }
  private async send(): Promise<void> {
    if (this.submission === null) return;
    this.busy.set(true); this.message.set('');
    const choiceForProof = this.selection();
    const generation = this.sessions.invalidations();
    try {
      const receipt = await this.submission.attempt();
      if (!this.active || this.sessions.invalidations() !== generation) return;
      this.bookingId = receipt.bookingId; this.intake.reset(); this.email.reset(); this.review.set(null); this.phase.set('status');
      await this.loadStatus();
    } catch (error) {
      const failure = safeFailure(error, true);
      if (this.active && this.sessions.invalidations() === generation) {
        if (failure.kind === 'UNCERTAIN' || failure.kind === 'THROTTLED') this.phase.set('uncertain');
        else { this.submission?.discard(); this.submission = null; this.review.set(null); this.selection.set(null); this.phase.set('choose'); }
        this.failure(failure, true);
        if (failure.kind === 'REJECTED' && failure.status === 403) {
          this.selection.set(choiceForProof); this.phase.set('email'); this.message.set('Verify your email for this new booking request.');
        }
      }
    } finally { if (this.active) this.busy.set(false); }
  }
  async loadStatus(): Promise<void> {
    if (this.bookingId === null) return;
    const bookingId = this.bookingId, generation = this.sessions.invalidations();
    const current = () => this.active && this.bookingId === bookingId && this.sessions.invalidations() === generation;
    this.busy.set(true);
    try {
      const value = await this.sessions.perform(() => this.bookings.getBooking(bookingId));
      if (current()) { this.status.set(value); this.message.set(''); }
    } catch (error) { if (current()) this.message.set('Your request was recorded, but its current status could not be loaded. Try loading the status again.'); }
    finally { if (this.active) this.busy.set(false); }
  }
  private clearPrivate(): void {
    this.submission?.discard(); this.submission = null; this.bookingId = null; this.status.set(null);
    this.intake.reset(); this.email.reset(); this.review.set(null); this.agreed.setValue(false);
    if (this.active) { this.phase.set('choose'); this.message.set('Your session changed or expired. Verify your email again to continue.'); }
  }
  private cooling(): boolean { return Date.now() < this.retryUntil; }
  private cooldown(seconds: number): void {
    this.retryUntil = Date.now() + seconds * 1000;
    const update = () => { this.retrySeconds.set(Math.max(0, Math.ceil((this.retryUntil - Date.now()) / 1000))); if (this.retrySeconds() === 0) clearInterval(this.timer); };
    clearInterval(this.timer); update(); this.timer = setInterval(update, 1000);
  }
  private failure(error: unknown, mutation: boolean): void {
    if (!this.active) return;
    const failure = error instanceof AccessFailure ? error : safeFailure(error, mutation);
    if (failure.kind === 'THROTTLED') this.cooldown(failure.retryAfterSeconds ?? 60);
    this.message.set(failure.kind === 'THROTTLED' ? 'Please wait before trying again.' : failure.kind === 'SESSION_EXPIRED' ? 'Your session expired. Verify your email again.' :
      failure.kind === 'CONFLICT' || failure.kind === 'VERSION_CONFLICT' ? 'That request could not be accepted. Choose a current time and review the terms again.' :
      failure.kind === 'UNCERTAIN' ? 'The response did not arrive. Your request may have succeeded. Use Retry to check the same submission; do not start a second request.' :
      'We could not complete this step. Check your details and try again.');
  }
}
