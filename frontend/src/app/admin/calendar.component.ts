import { Component, DestroyRef, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormArray, FormControl, FormGroup, FormRecord, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { AdministratorCalendarProposal, AdministratorConfiguration, AdministratorConflictPreview, AdministratorDateException, AdministratorDateExceptionModeEnum, AdministratorService } from '../shared/generated';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';
import { ConfigurationReviewComponent } from './configuration-review.component';
import { TimeWindowsComponent, WindowControls, validWindows, windowControls } from './time-windows.component';
import { brusselsDisplay, brusselsInstants, parseDay } from './admin-time';
import { reauthenticate } from './admin-reauth';

const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] as const;
type Action = 'weekly' | 'date-exception' | 'remove-exception' | 'blackout' | 'remove-blackout';
type Change = { proposal: AdministratorCalendarProposal; path: string; body: Record<string, unknown> };
@Component({
  imports: [ReactiveFormsModule, MatButtonModule, ConfigurationReviewComponent, TimeWindowsComponent],
  template: `
    <section aria-labelledby="calendar-title" [attr.aria-busy]="busy()"><h1 id="calendar-title">Schedule and blackouts</h1><p role="alert">{{ message() }}</p>
      <button mat-stroked-button type="button" (click)="load()" [disabled]="busy() || uncertain()">Reload calendar</button>
      @if (configuration(); as config) {
        <p>Start windows use Europe/Brussels civil time. Enter HH:mm; 24:00 is allowed only as an end. Empty weekly windows close that day. Reservations always last 120 minutes.</p>
        <h2>Date exceptions</h2><ul>@for (exception of config.dateExceptions; track exception.date) { <li>{{ exception.date }} — {{ exception.mode }} <button mat-stroked-button type="button" (click)="editException(exception)" [disabled]="busy() || uncertain()">Edit exception</button></li> }</ul>
        <h2>Blackouts</h2><ul>@for (blackout of config.blackouts; track blackout.id) { <li>{{ display(blackout.startInstant) }} – {{ display(blackout.endInstant) }}: {{ blackout.reason }}</li> }</ul>
        <form [formGroup]="form" (ngSubmit)="preview()"><fieldset [disabled]="busy() || uncertain()"><legend>Proposed calendar change</legend>
          <label for="calendar-action">Change</label><select id="calendar-action" formControlName="action"><option value="weekly">Replace weekly schedule</option><option value="date-exception">Set a date exception</option><option value="remove-exception">Remove a date exception</option><option value="blackout">Add a blackout</option><option value="remove-blackout">Remove a blackout</option></select>
          @switch (form.controls.action.value) {
            @case ('weekly') { @for (day of days; track day) { <app-time-windows [windows]="weekly.controls[day]" [label]="day" [disabled]="busy() || uncertain()" /> } }
            @case ('date-exception') {
              <label for="exception-date">Brussels date</label><input id="exception-date" type="date" formControlName="date">
              <label for="exception-mode">Effect on weekly windows</label><select id="exception-mode" formControlName="mode"><option value="REPLACE">Replace windows on this date</option><option value="ADD">Add windows on this date</option><option value="CLOSED">Close this date</option></select>
              @if (form.controls.mode.value !== 'CLOSED') { <app-time-windows [windows]="exceptionWindows" label="Date exception" [disabled]="busy() || uncertain()" /> }
            }
            @case ('remove-exception') { <label for="remove-exception">Exception to remove (weekly schedule resumes)</label><select id="remove-exception" formControlName="date"><option value="">Choose a date</option>@for (exception of config.dateExceptions; track exception.date) { <option [value]="exception.date">{{ exception.date }} — {{ exception.mode }}</option> }</select> }
            @case ('blackout') {
              <p>Blackouts prohibit intersecting reservations. Repeated autumn times require an explicit UTC offset.</p>
              <label for="blackout-start-day">Start date</label><input id="blackout-start-day" type="date" formControlName="startDay"><label for="blackout-start-time">Start time</label><input id="blackout-start-time" type="time" formControlName="startTime">
              @if (starts().length > 1) { <label for="blackout-start-offset">Start occurrence</label><select id="blackout-start-offset" formControlName="startInstant"><option value="">Choose UTC offset</option>@for (instant of starts(); track instant) { <option [value]="instant">{{ display(instant) }}</option> }</select> }
              <label for="blackout-end-day">End date</label><input id="blackout-end-day" type="date" formControlName="endDay"><label for="blackout-end-time">End time</label><input id="blackout-end-time" type="time" formControlName="endTime">
              @if (ends().length > 1) { <label for="blackout-end-offset">End occurrence</label><select id="blackout-end-offset" formControlName="endInstant"><option value="">Choose UTC offset</option>@for (instant of ends(); track instant) { <option [value]="instant">{{ display(instant) }}</option> }</select> }
            }
            @case ('remove-blackout') { <label for="remove-blackout">Blackout to remove</label><select id="remove-blackout" formControlName="blackoutId"><option value="">Choose a blackout</option>@for (blackout of config.blackouts; track blackout.id) { <option [value]="blackout.id">{{ display(blackout.startInstant) }} – {{ display(blackout.endInstant) }} — {{ blackout.reason }}</option> }</select> }
          }
          <label for="calendar-reason">Reason (10–500 characters)</label><textarea id="calendar-reason" formControlName="reason" maxlength="500"></textarea>
          <button mat-stroked-button type="submit" [disabled]="busy() || uncertain()">Preview affected bookings</button>
        </fieldset></form>
        @if (review(); as result) { <app-configuration-review [preview]="result" [acknowledged]="acknowledged()" [disabled]="busy() || uncertain()" (acknowledge)="acknowledged.set($event)" /><button mat-flat-button type="button" (click)="save()" [disabled]="busy() || uncertain() || !acknowledged()">Save reviewed calendar change</button> }
        @if (uncertain()) { <button mat-stroked-button type="button" (click)="retry()" [disabled]="busy()">Retry the same calendar change</button> }
      }
    </section>`,
  styles: [`section { max-width: 55rem; margin: 1rem auto; padding: 1rem; } fieldset { display: grid; gap: .5rem; margin: 1rem 0; } input, textarea, select { font: inherit; min-height: 2.5rem; padding: .3rem; } li { overflow-wrap: anywhere; margin: .5rem 0; }`],
})
export class CalendarComponent {
  private readonly api = inject(AdministratorService);
  private readonly http = inject(HttpClient);
  private readonly commands = inject(CommandAccess);
  private readonly sessions = inject(SessionAccess);
  private readonly router = inject(Router);
  private active = true;
  private generation = 0;
  private command: FrozenCommand<unknown> | null = null;
  private change: Change | null = null;
  private draftId: string | null = null;
  readonly configuration = signal<AdministratorConfiguration | null>(null);
  readonly review = signal<AdministratorConflictPreview | null>(null);
  readonly acknowledged = signal(false);
  readonly busy = signal(false);
  readonly uncertain = signal(false);
  readonly message = signal('');
  readonly starts = signal<string[]>([]);
  readonly ends = signal<string[]>([]);
  readonly days = DAYS;
  readonly weekly = new FormRecord<FormArray<WindowControls>>(Object.fromEntries(DAYS.map(day => [day, new FormArray<WindowControls>([])])));
  readonly exceptionWindows = new FormArray<WindowControls>([]);
  readonly form = new FormGroup({ action: new FormControl<Action>('weekly', { nonNullable: true }), weekly: this.weekly, windows: this.exceptionWindows, date: new FormControl('', { nonNullable: true }), mode: new FormControl(AdministratorDateExceptionModeEnum.Replace, { nonNullable: true }), blackoutId: new FormControl('', { nonNullable: true }), startDay: new FormControl('', { nonNullable: true }), startTime: new FormControl('', { nonNullable: true }), startInstant: new FormControl('', { nonNullable: true }), endDay: new FormControl('', { nonNullable: true }), endTime: new FormControl('', { nonNullable: true }), endInstant: new FormControl('', { nonNullable: true }), reason: new FormControl('', { nonNullable: true }) });
  constructor() {
    const changes = this.form.valueChanges.subscribe(() => {
      this.clearReview(); this.draftId = null;
      const v = this.form.getRawValue(), starts = brusselsInstants(v.startDay, v.startTime), ends = brusselsInstants(v.endDay, v.endTime); this.starts.set(starts); this.ends.set(ends);
      if (!starts.includes(v.startInstant)) this.form.controls.startInstant.setValue(starts.length === 1 ? starts[0] : '', { emitEvent: false });
      if (!ends.includes(v.endInstant)) this.form.controls.endInstant.setValue(ends.length === 1 ? ends[0] : '', { emitEvent: false });
    });
    const remove = this.sessions.onInvalidated(() => { this.generation++; this.clear(); });
    inject(DestroyRef).onDestroy(() => { this.active = false; this.generation++; changes.unsubscribe(); remove(); this.clear(); });
    void this.load();
  }
  display = brusselsDisplay;
  editException(exception: AdministratorDateException): void {
    if (this.busy() || this.uncertain()) return;
    this.exceptionWindows.clear(); exception.windows.forEach(window => this.exceptionWindows.push(windowControls(window)));
    this.form.patchValue({ action: 'date-exception', date: exception.date, mode: exception.mode });
  }
  async load(): Promise<boolean> {
    if (this.busy() || this.uncertain()) return false;
    this.busy.set(true); this.clearReview(); const generation = this.generation;
    try {
      const config = await firstValueFrom(this.api.getAdministratorConfiguration().pipe(timeout(25000)));
      if (!this.active || generation !== this.generation) return false;
      this.configuration.set(config); this.form.reset(); this.exceptionWindows.clear();
      for (const day of DAYS) { const windows = this.weekly.controls[day]; windows.clear(); config.weekly[day].forEach(window => windows.push(windowControls(window))); }
      return true;
    } catch (error) { if (this.active && generation === this.generation) { this.configuration.set(null); await this.failure(error, false); } return false; }
    finally { if (this.active) this.busy.set(false); }
  }
  private proposal(): Change | null {
    const config = this.configuration()!, v = this.form.getRawValue(), reason = v.reason.trim().normalize('NFC');
    if ([...reason].length < 10 || [...reason].length > 500 || /[\u0000-\u001f\u007f]/.test(reason)) return null;
    const proposal: AdministratorCalendarProposal = { weekly: config.weekly, dateExceptions: [...config.dateExceptions], blackouts: [...config.blackouts] };
    if (v.action === 'weekly') {
      if (!DAYS.every(day => validWindows(v.weekly[day]))) return null;
      proposal.weekly = { MONDAY: v.weekly['MONDAY'], TUESDAY: v.weekly['TUESDAY'], WEDNESDAY: v.weekly['WEDNESDAY'], THURSDAY: v.weekly['THURSDAY'], FRIDAY: v.weekly['FRIDAY'], SATURDAY: v.weekly['SATURDAY'], SUNDAY: v.weekly['SUNDAY'] }; return { proposal, path: '/weekly', body: { weekly: proposal.weekly, reason } };
    }
    if (v.action === 'date-exception') {
      if (parseDay(v.date) === null || (v.mode !== 'CLOSED' && !validWindows(v.windows))) return null;
      const exception = { date: v.date, mode: v.mode, windows: v.mode === 'CLOSED' ? [] : v.windows };
      proposal.dateExceptions = [...proposal.dateExceptions.filter(e => e.date !== v.date), exception];
      return { proposal, path: '/date-exceptions', body: { dateException: exception, reason } };
    }
    if (v.action === 'remove-exception') {
      if (!config.dateExceptions.some(e => e.date === v.date)) return null;
      proposal.dateExceptions = proposal.dateExceptions.filter(e => e.date !== v.date); return { proposal, path: '/date-exceptions/' + v.date + '/removal', body: { reason } };
    }
    if (v.action === 'blackout') {
      if (!this.starts().includes(v.startInstant) || !this.ends().includes(v.endInstant) || Date.parse(v.endInstant) <= Date.parse(v.startInstant)) return null;
      const blackout = { id: this.draftId ??= crypto.randomUUID(), startInstant: v.startInstant, endInstant: v.endInstant, reason };
      proposal.blackouts.push(blackout); return { proposal, path: '/blackouts', body: { blackout, reason } };
    }
    if (!config.blackouts.some(b => b.id === v.blackoutId)) return null;
    proposal.blackouts = proposal.blackouts.filter(b => b.id !== v.blackoutId); return { proposal, path: '/blackouts/' + v.blackoutId + '/removal', body: { reason } };
  }
  async preview(): Promise<void> {
    const config = this.configuration(); if (this.busy() || this.uncertain() || config === null) return;
    const change = this.proposal();
    if (change === null) { this.clearReview(); this.message.set('Check the selected change, valid dates/windows or blackout times, and a reason of 10–500 characters.'); return; }
    this.busy.set(true); this.clearReview(); this.message.set(''); const generation = this.generation;
    try {
      const result = await this.sessions.perform(csrf => this.http.post<AdministratorConflictPreview>('/api/v1/admin/configuration/conflicts', { proposal: change.proposal }, { headers: { 'X-CSRF-Token': csrf } }));
      if (!this.active || generation !== this.generation) return;
      if (result.calendarVersion !== config.calendarVersion || result.policyVersion !== config.policyVersion) { this.configuration.set(null); this.message.set('The configuration changed during review. Reload calendar and review again.'); return; }
      this.change = change; this.review.set(result);
    } catch (error) { if (this.active && generation === this.generation) await this.failure(error, false); }
    finally { if (this.active) this.busy.set(false); }
  }
  async save(): Promise<void> {
    const review = this.review(), change = this.change;
    if (this.busy() || this.uncertain() || review === null || change === null || !this.acknowledged()) return;
    try { this.command?.discard(); this.command = this.commands.prepare('POST', '/api/v1/admin/configuration' + change.path, { ...change.body, acknowledgedBookingIds: review.conflicts.map(c => c.bookingId) }, '"' + review.calendarVersion + '"'); }
    catch { this.message.set('This change could not be submitted from this page.'); return; }
    await this.send();
  }
  async retry(): Promise<void> { if (!this.busy() && this.uncertain() && this.command !== null) await this.send(); }
  private async send(): Promise<void> {
    this.busy.set(true); this.message.set(''); const generation = this.generation;
    try {
      await this.command!.attempt(); if (!this.active || generation !== this.generation) return;
      this.command = null; this.uncertain.set(false); this.busy.set(false); this.clearReview(); this.form.reset();
      this.message.set(await this.load() ? 'Calendar change saved. The current calendar is shown.' : 'Calendar change saved, but the current calendar could not be loaded. Reload before making another change.');
    } catch (error) { if (this.active && generation === this.generation) await this.failure(error, true); }
    finally { if (this.active) this.busy.set(false); }
  }
  private async failure(error: unknown, mutation: boolean): Promise<void> {
    const failure = safeFailure(error, mutation);
    if (failure.kind === 'SESSION_EXPIRED') { this.clear(); await this.router.navigateByUrl('/admin/login').catch(() => false); return; }
    if (failure.status === 403) { await reauthenticate(this.sessions, this.router, '/admin/calendar'); return; }
    if (this.uncertain() && failure.kind === 'THROTTLED') { this.message.set('Please wait, then retry the same calendar change. Its original result is still unknown.'); return; }
    this.uncertain.set(mutation && failure.kind === 'UNCERTAIN');
    if (this.uncertain()) { this.message.set('The response did not arrive. The calendar may have changed. Retry the same change before starting another.'); return; }
    this.command?.discard(); this.command = null; this.clearReview();
    if (failure.code === 'CONFLICT_REVIEW_REQUIRED') { this.busy.set(false); await this.preview(); if (this.review() !== null) this.message.set('Affected bookings changed. Review the new results and acknowledge them again.'); }
    else if (failure.kind === 'VERSION_CONFLICT' || failure.status === 428) { this.configuration.set(null); this.message.set('The configuration changed. Reload calendar and review again.'); }
    else if (failure.kind === 'THROTTLED') this.message.set('Please wait before trying again.');
    else this.message.set(mutation ? 'The calendar change could not be saved. Review the values and conflicts again.' : 'Calendar review information could not be loaded. Try again.');
  }
  private clearReview(): void { this.review.set(null); this.change = null; this.acknowledged.set(false); }
  private clear(): void { this.command?.discard(); this.command = null; this.configuration.set(null); this.clearReview(); this.form.reset(); this.exceptionWindows.clear(); DAYS.forEach(day => this.weekly.controls[day].clear()); this.draftId = null; this.uncertain.set(false); }
}
