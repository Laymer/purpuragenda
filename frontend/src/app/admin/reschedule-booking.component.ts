import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { AdministratorBookingDetail, AdministratorConfiguration, AdministratorService, BookingReceipt } from '../shared/generated';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';
import { brusselsDisplay, brusselsInstants } from './admin-time';
import { reauthenticate } from './admin-reauth';
import { OVERRIDE_RULES } from './scheduling-overrides';

@Component({
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule],
  template: `
    <section aria-labelledby="move-title" [attr.aria-busy]="busy()">
      <p><a [routerLink]="'/admin/bookings/' + id">Back to booking</a></p><h1 id="move-title">Reschedule booking</h1>
      <p role="alert">{{ message() }}</p>
      <button mat-stroked-button type="button" (click)="load()" [disabled]="busy() || uncertain()">Refresh booking</button>
      @if (booking(); as b) {
        <p>{{ b.reference }} — {{ b.snapshot.service.name }} — {{ b.state }}</p><p>Current start: {{ display(b.startInstant) }}</p>
        @if (eligible()) {
          <form [formGroup]="form" (ngSubmit)="review()">
            <fieldset [disabled]="busy() || uncertain()"><legend>New start (Europe/Brussels)</legend>
              <label for="move-day">Date</label><input id="move-day" type="date" formControlName="day">
              <label for="move-time">Time</label><input id="move-time" type="time" formControlName="time">
              @if (instants().length === 0) { <p>Choose a valid Brussels time. Spring clock-change gaps cannot be booked.</p> }
              @if (instants().length > 1) { <label for="move-occurrence">Choose the occurrence of this repeated time</label><select id="move-occurrence" formControlName="startInstant"><option value="">Choose a UTC offset</option>@for (instant of instants(); track instant) { <option [value]="instant">{{ display(instant) }}</option> }</select> }
              <label for="move-reason">Rescheduling reason (10–500 characters)</label><textarea id="move-reason" formControlName="reason" maxlength="500"></textarea>
              <button mat-stroked-button type="submit" [disabled]="busy() || uncertain()">Review new start</button>
            </fieldset>
          </form>
          @if (configuration(); as config) {
            <h2>Review change</h2><p>Move to {{ display(form.controls.startInstant.value) }}.</p>
            <p>The service, intake and policy snapshots and recorded money stay unchanged. Both audiences are notified. The full 120-minute reservation must be free.</p>
            @if (violations().length) { <fieldset [disabled]="busy() || uncertain()"><legend>Explicit exceptions</legend>
              @for (violation of violations(); track violation.rule) { <label><input type="checkbox" [checked]="selected.has(violation.rule)" (change)="toggle(selected, violation.rule, $event)">{{ violation.label }}</label> }
              @if (selected.has('HARD_BLACKOUT')) { @for (blackout of blackouts(); track blackout.id) { <label><input type="checkbox" [checked]="blackoutIds.has(blackout.id)" (change)="toggle(blackoutIds, blackout.id, $event)">{{ display(blackout.startInstant) }} – {{ display(blackout.endInstant) }}: {{ blackout.reason }}</label> } }
              <label for="move-override-reason">Exception reason (10–500 characters)</label><textarea id="move-override-reason" [formControl]="overrideReason" maxlength="500"></textarea>
            </fieldset> }
            <button mat-flat-button type="button" (click)="submit()" [disabled]="busy() || uncertain()">Reschedule booking</button>
          }
          @if (uncertain()) { <button mat-stroked-button type="button" (click)="retry()" [disabled]="busy()">Retry the same reschedule</button> }
        } @else { <p>This booking cannot be rescheduled.</p> }
      }
    </section>`,
  styles: [`section { max-width: 42rem; margin: 1rem auto; padding: 1rem; } fieldset { display: grid; gap: .5rem; margin: 1rem 0; } input, select, textarea { font: inherit; min-height: 2.5rem; padding: .3rem; } input[type=checkbox] { min-height: auto; margin-right: .5rem; }`],
})
export class RescheduleBookingComponent {
  private readonly api = inject(AdministratorService);
  private readonly commands = inject(CommandAccess);
  private readonly sessions = inject(SessionAccess);
  private readonly router = inject(Router);
  readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
  private active = true;
  private generation = 0;
  private etag: string | null = null;
  private command: FrozenCommand<BookingReceipt> | null = null;
  readonly booking = signal<AdministratorBookingDetail | null>(null);
  readonly configuration = signal<AdministratorConfiguration | null>(null);
  readonly busy = signal(false);
  readonly uncertain = signal(false);
  readonly message = signal('');
  readonly instants = signal<string[]>([]);
  readonly violations = signal<{ rule: string; label: string }[]>([]);
  readonly selected = new Set<string>();
  readonly blackoutIds = new Set<string>();
  readonly overrideReason = new FormControl('', { nonNullable: true });
  readonly form = new FormGroup({ day: new FormControl('', { nonNullable: true }), time: new FormControl('', { nonNullable: true }), startInstant: new FormControl('', { nonNullable: true }), reason: new FormControl('', { nonNullable: true }) });
  constructor() {
    const changes = this.form.valueChanges.subscribe(() => {
      this.configuration.set(null); this.resetOverrides();
      const candidates = brusselsInstants(this.form.controls.day.value, this.form.controls.time.value); this.instants.set(candidates);
      if (!candidates.includes(this.form.controls.startInstant.value)) this.form.controls.startInstant.setValue(candidates.length === 1 ? candidates[0] : '', { emitEvent: false });
    });
    const remove = this.sessions.onInvalidated(() => { this.generation++; this.clear(); });
    inject(DestroyRef).onDestroy(() => { this.active = false; this.generation++; changes.unsubscribe(); remove(); this.clear(); });
    void this.load();
  }
  display = brusselsDisplay;
  eligible(): boolean { const b = this.booking(); return b !== null && (b.state === 'CONFIRMED' || (b.state === 'PENDING_CONFIRMATION' && !b.pendingExpired)); }
  blackouts() { const start = Date.parse(this.form.controls.startInstant.value); return this.configuration()!.blackouts.filter(b => Date.parse(b.startInstant) < start + 7200000 && Date.parse(b.endInstant) > start); }
  toggle(set: Set<string>, value: string, event: Event): void { if ((event.target as HTMLInputElement).checked) set.add(value); else set.delete(value); }
  private validReason(reason: string): boolean { return [...reason].length >= 10 && [...reason].length <= 500 && !/[\u0000-\u001f\u007f]/.test(reason); }
  async load(): Promise<void> {
    if (this.busy() || this.uncertain()) return;
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(this.id)) { this.message.set('That booking could not be found.'); return; }
    this.configuration.set(null); this.resetOverrides(); this.busy.set(true); const generation = this.generation;
    try {
      const response = await firstValueFrom(this.api.getAdministratorBooking(this.id, 'response').pipe(timeout(25000)));
      if (!this.active || generation !== this.generation || response.body === null) return;
      this.booking.set(response.body); const tag = response.headers.get('ETag');
      this.etag = tag !== null && /^"[1-9]\d{0,18}"$/.test(tag) ? tag : '"' + response.body.version + '"';
    } catch (error) { if (this.active && generation === this.generation) { this.booking.set(null); await this.failure(error, false); } }
    finally { if (this.active) this.busy.set(false); }
  }
  async review(): Promise<void> {
    if (this.busy() || this.uncertain() || !this.eligible()) return;
    const v = this.form.getRawValue();
    if (!this.instants().includes(v.startInstant) || Date.parse(v.startInstant) === Date.parse(this.booking()!.startInstant) || !this.validReason(v.reason.trim().normalize('NFC'))) { this.message.set('Choose a different, unambiguous start and enter a reason of 10–500 characters.'); return; }
    this.busy.set(true); this.configuration.set(null); this.message.set(''); const generation = this.generation;
    try {
      const config = await firstValueFrom(this.api.getAdministratorConfiguration().pipe(timeout(25000)));
      if (this.active && generation === this.generation) this.configuration.set(config);
    } catch (error) { if (this.active && generation === this.generation) await this.failure(error, false); }
    finally { if (this.active) this.busy.set(false); }
  }
  async submit(): Promise<void> {
    const config = this.configuration();
    if (this.busy() || this.uncertain() || !this.eligible() || config === null || this.etag === null) return;
    const reason = this.overrideReason.value.trim().normalize('NFC');
    if (this.selected.size && !this.validReason(reason)) { this.message.set('Enter an exception reason of 10–500 characters.'); return; }
    const body = { policyVersion: config.policyVersion, calendarVersion: config.calendarVersion, startInstant: this.form.controls.startInstant.value, reason: this.form.controls.reason.value.trim().normalize('NFC'),
      override: { rules: [...this.selected], reason: this.selected.size ? reason : null, blackoutIds: this.selected.has('HARD_BLACKOUT') ? [...this.blackoutIds] : [] } };
    try { this.command?.discard(); this.command = this.commands.prepare<BookingReceipt>('POST', '/api/v1/admin/bookings/' + this.id + '/reschedule', body, this.etag); }
    catch { this.message.set('This change could not be submitted from this page.'); return; }
    await this.send();
  }
  async retry(): Promise<void> { if (!this.busy() && this.uncertain() && this.command !== null) await this.send(); }
  private async send(): Promise<void> {
    this.busy.set(true); this.message.set(''); const generation = this.generation;
    try {
      await this.command!.attempt();
      if (!this.active || generation !== this.generation) return;
      this.clear(); this.message.set('Booking rescheduled.'); await this.router.navigateByUrl('/admin/bookings/' + this.id).catch(() => false);
    } catch (error) { if (this.active && generation === this.generation) await this.failure(error, true); }
    finally { if (this.active) this.busy.set(false); }
  }
  private async failure(error: unknown, mutation: boolean): Promise<void> {
    const failure = safeFailure(error, mutation);
    if (failure.kind === 'SESSION_EXPIRED') { this.clear(); await this.router.navigateByUrl('/admin/login').catch(() => false); return; }
    if (failure.status === 403) { await reauthenticate(this.sessions, this.router, '/admin/bookings/' + this.id + '/reschedule'); return; }
    if (this.uncertain() && failure.kind === 'THROTTLED') { this.message.set('Please wait, then retry the same reschedule. Its original result is still unknown.'); return; }
    this.uncertain.set(failure.kind === 'UNCERTAIN');
    if (this.uncertain()) { this.message.set('The response did not arrive. The change may have succeeded. Retry the same reschedule before starting another.'); return; }
    this.command?.discard(); this.command = null;
    const violation = failure.code ? OVERRIDE_RULES[failure.code] : undefined;
    if (violation) {
      if (!this.violations().some(v => v.rule === violation.rule)) this.violations.update(values => [...values, violation]);
      this.message.set('The new start violates a rule. Review the explicit exception choices.');
    } else if (failure.kind === 'VERSION_CONFLICT' || failure.status === 428 || ['POLICY_CHANGED', 'CALENDAR_CHANGED', 'INVALID_TRANSITION', 'HOLD_EXPIRED'].includes(failure.code ?? '')) {
      this.configuration.set(null); this.booking.set(null); this.etag = null; this.resetOverrides(); this.message.set('The booking or configuration changed. Refresh the booking and review again.');
    } else if (failure.code === 'OVERRIDE_UNUSED') { this.resetOverrides(); this.message.set('An exception is no longer needed. Review and submit without unused exceptions.'); }
    else if (failure.code === 'BLACKOUT_OVERRIDE_REQUIRED') this.message.set('Explicitly select every intersecting blackout before submitting.');
    else if (failure.code === 'SLOT_UNAVAILABLE') this.message.set('Another reservation occupies this time. Choose a different start; overlap cannot be overridden.');
    else if (failure.kind === 'THROTTLED') this.message.set('Please wait before trying again.');
    else this.message.set(mutation ? 'The booking could not be rescheduled. Check the details and review again.' : 'The booking or configuration could not be loaded. Try again.');
  }
  private resetOverrides(): void { this.violations.set([]); this.selected.clear(); this.blackoutIds.clear(); this.overrideReason.reset(); }
  private clear(): void { this.command?.discard(); this.command = null; this.booking.set(null); this.configuration.set(null); this.etag = null; this.form.reset(); this.resetOverrides(); this.uncertain.set(false); }
}
