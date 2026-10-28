import { Component, DestroyRef, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { AdministratorConfiguration, AdministratorConflictPreview, AdministratorPolicy, AdministratorService } from '../shared/generated';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';
import { ConfigurationReviewComponent } from './configuration-review.component';
import { reauthenticate } from './admin-reauth';

const DURATIONS = ['minimumNotice', 'pendingLifetime', 'expiryBuffer', 'customerCutoff'] as const;
@Component({
  imports: [ReactiveFormsModule, MatButtonModule, ConfigurationReviewComponent],
  template: `
    <section aria-labelledby="policy-title" [attr.aria-busy]="busy()"><h1 id="policy-title">Booking policy</h1><p role="alert">{{ message() }}</p>
      <button mat-stroked-button type="button" (click)="load()" [disabled]="busy() || uncertain()">Reload policy</button>
      @if (configuration()) {
        <p>Existing bookings retain their policy snapshots. The booking horizon remains one Brussels calendar month; the customer active limit remains three.</p>
        <form [formGroup]="form" (ngSubmit)="preview()"><fieldset [disabled]="busy() || uncertain()"><legend>Proposed policy</legend>
          <label for="policy-grid">Start grid (1–120 minutes)</label><input id="policy-grid" type="number" min="1" max="120" step="1" formControlName="gridMinutes">
          <label for="policy-capacity">Daily booking capacity (zero closes new capacity)</label><input id="policy-capacity" type="number" min="0" step="1" formControlName="dailyCapacity">
          <p>Durations use hours, minutes and seconds: for example 24h, 1h30m or 30s. Zero is 0s.</p>
          @for (field of durationFields; track field.key) { <label [for]="field.key">{{ field.label }}</label><input [id]="field.key" [formControlName]="field.key" autocomplete="off"> }
          <button mat-stroked-button type="submit" [disabled]="busy() || uncertain()">Preview affected bookings</button>
        </fieldset></form>
        @if (review(); as result) {
          <app-configuration-review [preview]="result" [acknowledged]="acknowledged()" [disabled]="busy() || uncertain()" (acknowledge)="acknowledged.set($event)" />
          <label for="policy-reason">Reason (10–500 characters)</label><textarea id="policy-reason" [formControl]="reason" maxlength="500" [readOnly]="busy() || uncertain()"></textarea>
          <button mat-flat-button type="button" (click)="save()" [disabled]="busy() || uncertain() || !acknowledged()">Save reviewed policy</button>
        }
        @if (uncertain()) { <button mat-stroked-button type="button" (click)="retry()" [disabled]="busy()">Retry the same policy change</button> }
      }
    </section>`,
  styles: [`section { max-width: 44rem; margin: 1rem auto; padding: 1rem; } fieldset { display: grid; gap: .5rem; margin: 1rem 0; } input, textarea { font: inherit; min-height: 2.5rem; padding: .3rem; } textarea { display: block; width: 100%; box-sizing: border-box; }`],
})
export class PolicyComponent {
  private readonly api = inject(AdministratorService);
  private readonly http = inject(HttpClient);
  private readonly commands = inject(CommandAccess);
  private readonly sessions = inject(SessionAccess);
  private readonly router = inject(Router);
  private active = true;
  private generation = 0;
  private command: FrozenCommand<unknown> | null = null;
  private proposed: AdministratorPolicy | null = null;
  readonly configuration = signal<AdministratorConfiguration | null>(null);
  readonly review = signal<AdministratorConflictPreview | null>(null);
  readonly acknowledged = signal(false);
  readonly busy = signal(false);
  readonly uncertain = signal(false);
  readonly message = signal('');
  readonly reason = new FormControl('', { nonNullable: true });
  readonly durationFields = [{ key: 'minimumNotice', label: 'Minimum notice' }, { key: 'pendingLifetime', label: 'Pending request lifetime (must be positive)' }, { key: 'expiryBuffer', label: 'Pending expiry buffer before the start' }, { key: 'customerCutoff', label: 'Customer cancellation/rescheduling cutoff' }];
  readonly form = new FormGroup({ gridMinutes: new FormControl(30, { nonNullable: true }), dailyCapacity: new FormControl(3, { nonNullable: true }), minimumNotice: new FormControl('', { nonNullable: true }), pendingLifetime: new FormControl('', { nonNullable: true }), expiryBuffer: new FormControl('', { nonNullable: true }), customerCutoff: new FormControl('', { nonNullable: true }) });
  constructor() {
    const changes = this.form.valueChanges.subscribe(() => this.clearReview());
    const remove = this.sessions.onInvalidated(() => { this.generation++; this.clear(); });
    inject(DestroyRef).onDestroy(() => { this.active = false; this.generation++; changes.unsubscribe(); remove(); this.clear(); });
    void this.load();
  }
  async load(): Promise<boolean> {
    if (this.busy() || this.uncertain()) return false;
    this.busy.set(true); this.clearReview(); const generation = this.generation;
    try {
      const configuration = await firstValueFrom(this.api.getAdministratorConfiguration().pipe(timeout(25000)));
      if (!this.active || generation !== this.generation) return false;
      this.configuration.set(configuration); const policy = configuration.policy;
      this.form.setValue({ ...policy, minimumNotice: policy.minimumNotice.slice(2).toLowerCase(), pendingLifetime: policy.pendingLifetime.slice(2).toLowerCase(), expiryBuffer: policy.expiryBuffer.slice(2).toLowerCase(), customerCutoff: policy.customerCutoff.slice(2).toLowerCase() }); this.reason.reset(); return true;
    } catch (error) { if (this.active && generation === this.generation) { this.configuration.set(null); await this.failure(error, false); } return false; }
    finally { if (this.active) this.busy.set(false); }
  }
  async preview(): Promise<void> {
    const config = this.configuration();
    if (this.busy() || this.uncertain() || config === null) return;
    const values = this.form.getRawValue();
    const durations = DURATIONS.map(key => values[key].trim().toLowerCase().replaceAll(' ', ''));
    if (!Number.isInteger(values.gridMinutes) || values.gridMinutes < 1 || values.gridMinutes > 120 || !Number.isInteger(values.dailyCapacity) || values.dailyCapacity < 0 || values.dailyCapacity > 2147483647 || durations.some(value => !/^(?=\d)(?:\d+h)?(?:\d+m)?(?:\d+(?:\.\d{1,9})?s)?$/.test(value)) || !/[1-9]/.test(durations[1])) {
      this.message.set('Enter a whole grid of 1–120 minutes, a non-negative whole capacity, and valid durations with a positive pending lifetime.'); this.clearReview(); return;
    }
    const proposal: AdministratorPolicy = { gridMinutes: values.gridMinutes, dailyCapacity: values.dailyCapacity, minimumNotice: 'PT' + durations[0].toUpperCase(), pendingLifetime: 'PT' + durations[1].toUpperCase(), expiryBuffer: 'PT' + durations[2].toUpperCase(), customerCutoff: 'PT' + durations[3].toUpperCase() };
    this.busy.set(true); this.clearReview(); this.message.set(''); const generation = this.generation;
    try {
      const result = await this.sessions.perform(csrf => this.http.post<AdministratorConflictPreview>('/api/v1/admin/configuration/conflicts', { proposal }, { headers: { 'X-CSRF-Token': csrf } }));
      if (!this.active || generation !== this.generation) return;
      if (result.calendarVersion !== config.calendarVersion || result.policyVersion !== config.policyVersion) { this.configuration.set(null); this.message.set('The configuration changed during review. Reload policy and review again.'); return; }
      this.proposed = proposal; this.review.set(result);
    } catch (error) { if (this.active && generation === this.generation) await this.failure(error, false); }
    finally { if (this.active) this.busy.set(false); }
  }
  async save(): Promise<void> {
    const review = this.review();
    if (this.busy() || this.uncertain() || this.proposed === null || review === null || !this.acknowledged()) return;
    const reason = this.reason.value.trim().normalize('NFC');
    if ([...reason].length < 10 || [...reason].length > 500 || /[\u0000-\u001f\u007f]/.test(reason)) { this.message.set('Enter a reason of 10–500 characters.'); return; }
    try { this.command?.discard(); this.command = this.commands.prepare('POST', '/api/v1/admin/configuration/policy', { policy: this.proposed, reason, acknowledgedBookingIds: review.conflicts.map(conflict => conflict.bookingId) }, '"' + review.policyVersion + '"'); }
    catch { this.message.set('This policy could not be submitted from this page.'); return; }
    await this.send();
  }
  async retry(): Promise<void> { if (!this.busy() && this.uncertain() && this.command !== null) await this.send(); }
  private async send(): Promise<void> {
    this.busy.set(true); this.message.set(''); const generation = this.generation;
    try {
      await this.command!.attempt();
      if (!this.active || generation !== this.generation) return;
      this.command = null; this.uncertain.set(false); this.busy.set(false); this.clearReview(); this.reason.reset();
      this.message.set(await this.load() ? 'Policy saved. The current policy is shown.' : 'Policy saved, but the current policy could not be loaded. Reload before making another change.');
    } catch (error) { if (this.active && generation === this.generation) await this.failure(error, true); }
    finally { if (this.active) this.busy.set(false); }
  }
  private async failure(error: unknown, mutation: boolean): Promise<void> {
    const failure = safeFailure(error, mutation);
    if (failure.kind === 'SESSION_EXPIRED') { this.clear(); await this.router.navigateByUrl('/admin/login').catch(() => false); return; }
    if (failure.status === 403) { await reauthenticate(this.sessions, this.router, '/admin/policy'); return; }
    if (this.uncertain() && failure.kind === 'THROTTLED') { this.message.set('Please wait, then retry the same policy change. Its original result is still unknown.'); return; }
    this.uncertain.set(mutation && failure.kind === 'UNCERTAIN');
    if (this.uncertain()) { this.message.set('The response did not arrive. The policy may have changed. Retry the same change before starting another.'); return; }
    this.command?.discard(); this.command = null; this.clearReview();
    if (failure.code === 'CONFLICT_REVIEW_REQUIRED') {
      this.busy.set(false); await this.preview();
      if (this.review() !== null) this.message.set('Affected bookings changed. Review the new results and acknowledge them again.');
    } else if (failure.kind === 'VERSION_CONFLICT' || failure.status === 428) { this.configuration.set(null); this.message.set('The configuration changed. Reload policy and review again.'); }
    else if (failure.kind === 'THROTTLED') this.message.set('Please wait before trying again.');
    else this.message.set(mutation ? 'The policy could not be saved. Review the values and conflicts again.' : 'Policy review information could not be loaded. Try again.');
  }
  private clearReview(): void { this.review.set(null); this.proposed = null; this.acknowledged.set(false); }
  private clear(): void { this.command?.discard(); this.command = null; this.configuration.set(null); this.clearReview(); this.form.reset(); this.reason.reset(); this.uncertain.set(false); }
}
