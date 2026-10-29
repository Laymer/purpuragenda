import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { AdministratorConfiguration, AdministratorService, BookingReceipt, BookingReviewContext, CatalogService } from '../shared/generated';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';
import { brusselsDisplay, brusselsInstants, zoomHint } from './admin-time';
import { reauthenticate } from './admin-reauth';

import { OVERRIDE_RULES } from './scheduling-overrides';

const text = (max: number) => new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(max)] });

@Component({
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule],
  template: `
    <section aria-labelledby="create-title" [attr.aria-busy]="busy()">
      <p><a routerLink="/admin">Back to agenda</a></p><h1 id="create-title">Create a booking</h1>
      <p role="alert">{{ message() }}</p>
      @if (!configuration()) { <button mat-stroked-button (click)="load()" [disabled]="busy()">Reload services</button> }
      @if (configuration(); as config) {
        <form [formGroup]="form" (ngSubmit)="review()">
          <fieldset [disabled]="busy() || uncertain()"><legend>Booking and customer</legend>
            <label for="service">Service</label><select id="service" formControlName="serviceId"><option value="">Choose a service</option>
              @for (service of config.services; track service.id) { @if (service.active) { <option [value]="service.id">{{ service.name }} — {{ service.priceMinor / 100 }} EUR</option> } }
            </select>
            <label for="start-day">Date (Europe/Brussels)</label><input id="start-day" type="date" formControlName="day">
            <label for="start-time">Time (Europe/Brussels)</label><input id="start-time" type="time" formControlName="time">
            @if (instants().length === 0) { <p>Choose a valid Brussels date and time. Spring clock-change gaps cannot be booked.</p> }
            @if (instants().length > 1) { <label for="occurrence">This time occurs twice. Choose its UTC offset.</label>
              <select id="occurrence" formControlName="startInstant"><option value="">Choose an occurrence</option>@for (instant of instants(); track instant) { <option [value]="instant">{{ display(instant) }}</option> }</select> }
            <label for="email">Customer email</label><input id="email" type="email" formControlName="customerEmail" autocomplete="off">
            @for (field of intakeFields; track field.key) { <label [for]="field.key">{{ field.label }}</label><input [id]="field.key" [type]="field.type" [formControlName]="field.key" autocomplete="off"> }
            <p id="manual-privacy-guidance">This is a non-medical service. Do not enter medical information or other sensitive personal information. Include only details needed for this consultation. Do not record unnecessary information about other people.</p>
            <label for="intake-message">Customer message (optional)</label><textarea aria-describedby="manual-privacy-guidance" id="intake-message" formControlName="message" maxlength="1000"></textarea>
            <label for="mode">Initial state</label><select id="mode" formControlName="mode"><option value="pending">Pending confirmation</option><option value="confirmed">Confirmed manually</option></select>
            @if (form.controls.mode.value === 'confirmed') { <label for="manual-meeting">Zoom meeting link</label><input id="manual-meeting" type="url" formControlName="meetingUrl" autocomplete="off"> }
            <p>Confirmation does not record payment. Every booking reserves 120 minutes; overlaps cannot be overridden.</p>
            <button mat-stroked-button type="submit" [disabled]="busy() || uncertain()">Review booking</button>
          </fieldset>
        </form>
        @if (context(); as terms) {
          <h2>Review</h2><p>{{ terms.service.name }} — {{ terms.service.priceMinor / 100 }} EUR; {{ terms.service.durationMinutes }} minute session.</p>
          <p>{{ display(form.controls.startInstant.value) }}. {{ form.controls.mode.value === 'confirmed' ? 'Confirmed manually' : 'Pending confirmation' }}.</p>
          <p>{{ terms.notice.text }}</p>@if (terms.notice.synthetic) { <p>This local notice is synthetic.</p> }
          @if (violations().length) {
            <fieldset [disabled]="busy() || uncertain()"><legend>Explicit exceptions</legend>
              <p>The server reported these violated rules. Select only the exceptions you intend to authorize.</p>
              @for (violation of violations(); track violation.rule) { <label><input type="checkbox" [checked]="selected.has(violation.rule)" (change)="toggle(selected, violation.rule, $event)">{{ violation.label }}</label> }
              @if (selected.has('HARD_BLACKOUT')) { @for (blackout of blackouts(); track blackout.id) { <label><input type="checkbox" [checked]="blackoutIds.has(blackout.id)" (change)="toggle(blackoutIds, blackout.id, $event)">{{ display(blackout.startInstant) }} – {{ display(blackout.endInstant) }}: {{ blackout.reason }}</label> } }
              <label for="override-reason">Exception reason (10–500 characters)</label><textarea id="override-reason" [formControl]="reason" maxlength="500"></textarea>
            </fieldset>
          }
          <button mat-flat-button type="button" (click)="submit()" [disabled]="busy() || uncertain()">Create booking</button>
        }
        @if (uncertain()) { <button mat-stroked-button type="button" (click)="retry()" [disabled]="busy()">Retry the same booking</button> }
      }
    </section>`,
  styles: [`section { max-width: 42rem; margin: 1rem auto; padding: 1rem; } fieldset { display: grid; gap: .5rem; margin: 1rem 0; } input, select, textarea { font: inherit; min-height: 2.5rem; padding: .3rem; } input[type=checkbox] { min-height: auto; margin-right: .5rem; }`],
})
export class ManualBookingComponent {
  private readonly api = inject(AdministratorService);
  private readonly catalog = inject(CatalogService);
  private readonly commands = inject(CommandAccess);
  private readonly sessions = inject(SessionAccess);
  private readonly router = inject(Router);
  private active = true;
  private generation = 0;
  private command: FrozenCommand<BookingReceipt> | null = null;
  readonly configuration = signal<AdministratorConfiguration | null>(null);
  readonly context = signal<BookingReviewContext | null>(null);
  readonly busy = signal(false);
  readonly uncertain = signal(false);
  readonly message = signal('');
  readonly instants = signal<string[]>([]);
  readonly violations = signal<{ rule: string; label: string }[]>([]);
  readonly selected = new Set<string>();
  readonly blackoutIds = new Set<string>();
  readonly reason = new FormControl('', { nonNullable: true });
  readonly intakeFields = [
    { key: 'firstName', label: 'First name', type: 'text' }, { key: 'lastName', label: 'Last name', type: 'text' },
    { key: 'telephone', label: 'Telephone with international prefix', type: 'tel' }, { key: 'birthDate', label: 'Birth date', type: 'date' },
    { key: 'birthTime', label: 'Birth time', type: 'time' }, { key: 'birthplace', label: 'Birthplace', type: 'text' },
  ];
  readonly form = new FormGroup({
    serviceId: text(36), day: text(10), time: text(5), startInstant: text(40),
    customerEmail: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.email, Validators.maxLength(254)] }),
    firstName: text(100), lastName: text(100), telephone: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(/^\+[0-9 ().-]{7,39}$/)] }),
    birthDate: text(10), birthTime: text(8), birthplace: text(200), message: new FormControl('', { nonNullable: true, validators: Validators.maxLength(1000) }),
    mode: new FormControl('pending', { nonNullable: true }), meetingUrl: new FormControl('', { nonNullable: true }),
  });
  constructor() {
    const changes = this.form.valueChanges.subscribe(() => {
      this.context.set(null); this.resetOverrides();
      const candidates = brusselsInstants(this.form.controls.day.value, this.form.controls.time.value);
      this.instants.set(candidates);
      if (!candidates.includes(this.form.controls.startInstant.value)) this.form.controls.startInstant.setValue(candidates.length === 1 ? candidates[0] : '', { emitEvent: false });
    });
    const remove = this.sessions.onInvalidated(() => { this.generation++; this.clear(); });
    inject(DestroyRef).onDestroy(() => { this.active = false; this.generation++; changes.unsubscribe(); remove(); this.clear(); });
    void this.load();
  }
  display = brusselsDisplay;
  blackouts() {
    const start = Date.parse(this.form.controls.startInstant.value);
    return this.configuration()!.blackouts.filter(b => Date.parse(b.startInstant) < start + 7200000 && Date.parse(b.endInstant) > start);
  }
  toggle(set: Set<string>, value: string, event: Event): void { if ((event.target as HTMLInputElement).checked) set.add(value); else set.delete(value); }
  async load(): Promise<void> {
    if (this.busy() || this.uncertain()) return;
    const generation = this.generation; this.busy.set(true); this.context.set(null); this.resetOverrides();
    try {
      const config = await firstValueFrom(this.api.getAdministratorConfiguration().pipe(timeout(25000)));
      if (this.active && generation === this.generation) this.configuration.set(config);
    } catch (error) { if (this.active && generation === this.generation) await this.failure(error, false); }
    finally { if (this.active) this.busy.set(false); }
  }
  async review(): Promise<void> {
    if (this.busy() || this.uncertain()) return;
    if (this.form.invalid || !this.instants().includes(this.form.controls.startInstant.value)) { this.message.set('Complete the customer details and choose a valid, unambiguous start time.'); return; }
    if (this.form.controls.mode.value === 'confirmed' && zoomHint(this.form.controls.meetingUrl.value) !== null) { this.message.set('Enter a valid https Zoom meeting link for manual confirmation.'); return; }
    const generation = this.generation; this.busy.set(true); this.context.set(null); this.message.set('');
    try {
      const context = await firstValueFrom(this.catalog.getBookingContext(this.form.controls.serviceId.value).pipe(timeout(25000)));
      if (!this.active || generation !== this.generation) return;
      if (context.policy.version !== this.configuration()!.policyVersion) { this.configuration.set(null); this.message.set('The policy changed. Reload services and review again.'); return; }
      this.context.set(context);
    } catch (error) { if (this.active && generation === this.generation) await this.failure(error, false); }
    finally { if (this.active) this.busy.set(false); }
  }
  async submit(): Promise<void> {
    const terms = this.context(), config = this.configuration();
    if (this.busy() || this.uncertain() || terms === null || config === null) return;
    const reason = this.reason.value.trim().normalize('NFC');
    if (this.selected.size && ([...reason].length < 10 || [...reason].length > 500 || /[\u0000-\u001f\u007f]/.test(reason))) { this.message.set('Enter an exception reason of 10–500 characters.'); return; }
    const v = this.form.getRawValue();
    const body = { serviceId: terms.service.id, serviceVersion: terms.service.version, policyVersion: terms.policy.version, calendarVersion: config.calendarVersion,
      startInstant: v.startInstant, displayZone: 'Europe/Brussels', noticeVersion: terms.notice.version, customerEmail: v.customerEmail.trim(),
      intake: { firstName: v.firstName, lastName: v.lastName, telephone: v.telephone, birthDate: v.birthDate, birthTime: v.birthTime, birthplace: v.birthplace, message: v.message },
      meetingUrl: v.mode === 'confirmed' ? v.meetingUrl.trim() : null,
      override: { rules: [...this.selected], reason: this.selected.size ? reason : null, blackoutIds: this.selected.has('HARD_BLACKOUT') ? [...this.blackoutIds] : [] } };
    try { this.command?.discard(); this.command = this.commands.prepare<BookingReceipt>('POST', '/api/v1/admin/bookings', body); }
    catch { this.message.set('This booking could not be submitted from this page.'); return; }
    await this.send();
  }
  async retry(): Promise<void> { if (!this.busy() && this.uncertain() && this.command !== null) await this.send(); }
  private async send(): Promise<void> {
    const generation = this.generation; this.busy.set(true); this.message.set('');
    try {
      const receipt = await this.command!.attempt();
      if (!this.active || generation !== this.generation) return;
      this.clear(); this.message.set('Booking created.');
      await this.router.navigateByUrl('/admin/bookings/' + receipt.bookingId).catch(() => false);
    } catch (error) { if (this.active && generation === this.generation) await this.failure(error, true); }
    finally { if (this.active) this.busy.set(false); }
  }
  private async failure(error: unknown, mutation: boolean): Promise<void> {
    const failure = safeFailure(error, mutation);
    if (failure.kind === 'SESSION_EXPIRED') { this.clear(); await this.router.navigateByUrl('/admin/login').catch(() => false); return; }
    if (failure.status === 403) { await reauthenticate(this.sessions, this.router, '/admin/bookings/new'); return; }
    // A rejected retry does not establish whether an earlier uncertain attempt committed.
    if (this.uncertain() && failure.kind === 'THROTTLED') { this.message.set('Please wait, then retry the same booking. Its original result is still unknown.'); return; }
    this.uncertain.set(failure.kind === 'UNCERTAIN');
    if (this.uncertain()) { this.message.set('The response did not arrive. The booking may exist. Retry the same booking before starting another.'); return; }
    this.command?.discard(); this.command = null;
    const violation = failure.code ? OVERRIDE_RULES[failure.code] : undefined;
    if (violation) {
      if (!this.violations().some(v => v.rule === violation.rule)) this.violations.update(values => [...values, violation]);
      this.message.set('The booking violates a rule. Review the explicit exception choices.');
    } else if (failure.kind === 'VERSION_CONFLICT' || failure.status === 428 || (!mutation && failure.status === 404) || ['SERVICE_UNAVAILABLE', 'SERVICE_CHANGED', 'POLICY_CHANGED', 'CALENDAR_CHANGED', 'NOTICE_CHANGED'].includes(failure.code ?? '')) {
      this.context.set(null); this.configuration.set(null); this.resetOverrides(); this.message.set('The configuration changed. Reload services and review again.');
    } else if (failure.code === 'OVERRIDE_UNUSED') {
      this.resetOverrides(); this.message.set('An exception is no longer needed. Review and submit again without unused exceptions.');
    } else if (failure.code === 'BLACKOUT_OVERRIDE_REQUIRED') this.message.set('Explicitly select every intersecting blackout before submitting.');
    else if (failure.code === 'SLOT_UNAVAILABLE') this.message.set('Another reservation occupies this time. Choose a different start; overlap cannot be overridden.');
    else if (failure.code === 'MEETING_IN_USE') this.message.set('That meeting link is already in use. Choose a different link.');
    else if (failure.kind === 'THROTTLED') this.message.set('Please wait before trying again.');
    else this.message.set(mutation ? 'The booking could not be created. Check the details and review again.' : 'Review information could not be loaded. Try again.');
  }
  private resetOverrides(): void { this.violations.set([]); this.selected.clear(); this.blackoutIds.clear(); this.reason.reset(); }
  private clear(): void { this.command?.discard(); this.command = null; this.context.set(null); this.configuration.set(null); this.form.reset(); this.resetOverrides(); this.uncertain.set(false); }
}
