import { Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { AdministratorNotification, AdministratorNotificationStateEnum, AdministratorRefundFollowUp, AdministratorService } from '../shared/generated';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';
import { brusselsDisplay } from './admin-time';
import { displayMoney } from './finance-money';
import { reauthenticate } from './admin-reauth';

@Component({
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule],
  template: `
    <section aria-labelledby="operations-title" [attr.aria-busy]="busy()">
      <h1 id="operations-title">{{ notifications() ? 'Notification follow-up' : 'Outstanding refunds' }}</h1>
      <p id="operations-message" role="status" tabindex="-1">{{ message() }}</p>
      @if (notifications()) {
        <form [formGroup]="filterForm" (ngSubmit)="load()">
          <label for="notification-state">Delivery status</label>
          <select id="notification-state" formControlName="state"><option value="">All statuses</option><option value="FAILED">Failed</option><option value="QUEUED">Queued</option><option value="LEASED">Being processed</option><option value="SENT">Sent</option><option value="SUPPRESSED">Suppressed</option></select>
          <button mat-stroked-button type="submit" [disabled]="busy() || uncertain()">Apply status and refresh</button>
        </form>
        <p>Only booking notifications are shown. A retry queues one attempt; the worker may suppress messages that are no longer relevant. Delivery can occasionally duplicate after a connection failure.</p>
        @if (selected(); as job) {
          <h2>Retry for {{ job.reference }}</h2>
          <form [formGroup]="reasonForm" (ngSubmit)="submit()">
            <label for="retry-reason">Reason for retry (10–500 characters)</label>
            <textarea id="retry-reason" formControlName="reason" maxlength="1000" autocomplete="off" [readOnly]="busy() || uncertain()"></textarea>
            <button mat-flat-button type="submit" [disabled]="busy() || uncertain()">Queue retry</button>
            <button mat-stroked-button type="button" (click)="cancel()" [disabled]="busy() || uncertain()">Cancel</button>
          </form>
          @if (uncertain()) { <button mat-stroked-button type="button" (click)="retrySame()" [disabled]="busy()">Retry the same request</button> }
        }
        @if (loaded()) {
          <ul>@for (job of jobs(); track job.id) {
            <li><a [routerLink]="'/admin/bookings/' + job.bookingId">{{ job.reference }}</a>
              <p>{{ job.audience }} — {{ job.template.replaceAll('_', ' ') }} — {{ job.state.replaceAll('_', ' ') }}</p>
              <p>Attempts: {{ job.attempts }}. Scheduled: {{ display(job.dueAt) }} (Europe/Brussels).</p>
              @if (job.safeCode) { <p>{{ job.safeCode.replaceAll('_', ' ') }}</p> }
              @if (retryable(job)) { <button mat-stroked-button type="button" (click)="choose(job.id)" [disabled]="busy() || uncertain()">Review retry for {{ job.reference }}</button> }
            </li>
          } @empty { <li>No booking notifications on this page.</li> }</ul>
        }
      } @else {
        <p>Outstanding obligations are based on recorded receipts and refunds. Recording a refund never transfers money.</p>
        <button mat-stroked-button type="button" (click)="load()" [disabled]="busy()">Refresh outstanding refunds</button>
        @if (loaded()) {
          <ul>@for (entry of refunds(); track entry.receiptId) {
            <li><a [routerLink]="'/admin/bookings/' + entry.bookingId + '/finance'">{{ entry.reference }} — review receipts and refunds</a>
              <p>Received {{ money(entry.receivedMinor, entry.currency) }}; refunded {{ money(entry.refundedMinor, entry.currency) }}.</p>
              <p>Full obligation {{ money(entry.dueMinor, entry.currency) }}; outstanding {{ money(entry.outstandingMinor, entry.currency) }}.</p>
              <p>{{ entry.reason.replaceAll('_', ' ') }} — {{ entry.state.replaceAll('_', ' ') }}</p>
            </li>
          } @empty { <li>No outstanding refunds on this page.</li> }</ul>
        }
      }
      @if (cursor()) { <button mat-stroked-button type="button" (click)="more()" [disabled]="busy() || uncertain()">Next page</button> }
    </section>`,
  styles: [`section { max-width: 48rem; margin: 1rem auto; padding: 1rem; } form { display: grid; gap: .5rem; margin: 1rem 0; } select, textarea { font: inherit; padding: .6rem; min-width: 0; } textarea { min-height: 5rem; } ul { padding-left: 1.2rem; } li { margin: 1rem 0; overflow-wrap: anywhere; }`],
})
export class OperationsComponent {
  private readonly api = inject(AdministratorService);
  private readonly commands = inject(CommandAccess);
  private readonly sessions = inject(SessionAccess);
  private readonly router = inject(Router);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private active = true;
  private blocked = false;
  private generation = 0;
  private filter: AdministratorNotificationStateEnum | undefined = AdministratorNotificationStateEnum.Failed;
  private command: FrozenCommand<unknown> | null = null;
  readonly notifications = signal(false);
  readonly refunds = signal<AdministratorRefundFollowUp[]>([]);
  readonly jobs = signal<AdministratorNotification[]>([]);
  readonly selected = signal<AdministratorNotification | null>(null);
  readonly cursor = signal<string | null>(null);
  readonly busy = signal(false);
  readonly loaded = signal(false);
  readonly uncertain = signal(false);
  readonly message = signal('');
  readonly filterForm = new FormGroup({ state: new FormControl('FAILED', { nonNullable: true }) });
  readonly reasonForm = new FormGroup({ reason: new FormControl('', { nonNullable: true }) });
  readonly money = displayMoney;
  readonly display = brusselsDisplay;
  constructor() {
    const route = inject(ActivatedRoute).data.subscribe(data => { this.clear(); this.blocked = false; this.notifications.set(data['mode'] === 'notifications'); void this.load(); });
    const remove = this.sessions.onInvalidated(() => { this.blocked = true; this.clear(); });
    inject(DestroyRef).onDestroy(() => { this.active = false; route.unsubscribe(); remove(); this.clear(); });
  }
  private available(): boolean { return this.active && !this.blocked && !this.busy() && !this.uncertain(); }
  private current(generation: number): boolean { return this.active && generation === this.generation; }
  async load(): Promise<boolean> {
    if (!this.available()) return false;
    const value = this.filterForm.controls.state.value;
    if (this.notifications() && value !== '' && !Object.values(AdministratorNotificationStateEnum).includes(value as AdministratorNotificationStateEnum)) { this.message.set('Choose a delivery status.'); return false; }
    this.filter = value === '' ? undefined : value as AdministratorNotificationStateEnum;
    this.cancel(); this.refunds.set([]); this.jobs.set([]); this.cursor.set(null); this.loaded.set(false); return this.fetch();
  }
  async more(): Promise<void> { if (this.available() && this.loaded() && this.cursor()) await this.fetch(this.cursor()!); }
  private async fetch(cursor?: string): Promise<boolean> {
    this.busy.set(true); this.message.set(''); const generation = this.generation;
    try {
      if (this.notifications()) { const page = await firstValueFrom(this.api.getAdministratorNotifications(undefined, this.filter, 20, cursor).pipe(timeout(25000))); if (!this.current(generation)) return false; this.jobs.set(page.entries); this.cursor.set(page.nextCursor ?? null); }
      else { const page = await firstValueFrom(this.api.getAdministratorReconciliation(undefined, 20, cursor).pipe(timeout(25000))); if (!this.current(generation)) return false; this.refunds.set(page.entries); this.cursor.set(page.nextCursor ?? null); }
      this.selected.set(null); this.reasonForm.reset(); this.loaded.set(true); this.message.set('Page loaded.'); return true;
    } catch (error) { if (this.current(generation)) await this.failure(error, false); return false; }
    finally { if (this.current(generation)) this.busy.set(false); }
  }
  retryable(job: AdministratorNotification): boolean { return job.state === AdministratorNotificationStateEnum.Failed && Number.isSafeInteger(job.attempts) && job.attempts > 0 && job.attempts < 2147483647; }
  choose(id: string): void { if (!this.available() || !this.notifications() || !this.loaded()) return; const job = this.jobs().find(item => item.id === id); if (!job || !this.retryable(job)) return; this.selected.set(job); this.reasonForm.reset(); this.message.set('Review the failed notification and enter a reason for retry.'); }
  cancel(): void { if (!this.available()) return; this.command?.discard(); this.command = null; this.selected.set(null); this.reasonForm.reset(); }
  async submit(): Promise<void> {
    const job = this.selected(); if (!this.available() || !this.loaded() || !job) return;
    const reason = this.reasonForm.controls.reason.value.trim().normalize('NFC');
    if ([...reason].length < 10 || [...reason].length > 500 || /[\u0000-\u001f\u007f-\u009f\ud800-\udfff]/u.test(reason)) { this.message.set('Enter a reason of 10–500 characters without control characters.'); this.focus(); return; }
    try { this.command = this.commands.prepare('POST', '/api/v1/admin/notifications/' + job.id + '/retry', { reason }, '"' + job.attempts + '"'); }
    catch { this.message.set('This retry could not be submitted from this page.'); return; }
    await this.send();
  }
  async retrySame(): Promise<void> { if (this.active && !this.blocked && !this.busy() && this.uncertain() && this.command !== null) await this.send(); }
  private async send(): Promise<void> {
    this.busy.set(true); this.message.set(''); const generation = this.generation;
    try {
      await this.command!.attempt(); if (!this.current(generation)) return;
      this.command = null; this.uncertain.set(false); this.selected.set(null); this.reasonForm.reset(); this.busy.set(false);
      const refreshed = await this.load(); if (this.current(generation)) this.message.set(refreshed ? 'Retry queued. The worker will check whether the notification is still relevant.' : 'Retry queued, but the list could not be refreshed. Refresh before retrying another notification.');
    } catch (error) { if (this.current(generation)) await this.failure(error, true); }
    finally { if (this.current(generation)) this.busy.set(false); }
  }
  private async failure(error: unknown, mutation: boolean): Promise<void> {
    const failure = safeFailure(error, mutation);
    if (failure.kind === 'SESSION_EXPIRED') { this.blocked = true; this.clear(); this.sessions.invalidate(); await this.router.navigateByUrl('/admin/login').catch(() => false); return; }
    if (failure.status === 403) { await reauthenticate(this.sessions, this.router, this.notifications() ? '/admin/notifications' : '/admin/reconciliation'); return; }
    if (mutation && (failure.kind === 'UNCERTAIN' || failure.kind === 'THROTTLED')) { this.uncertain.set(true); this.message.set(failure.kind === 'THROTTLED' ? 'Please wait, then retry the same request.' : 'The response did not arrive. A retry may already be queued. Retry the same request.'); return; }
    if (mutation) {
      this.command?.discard(); this.command = null; this.uncertain.set(false); this.selected.set(null); this.reasonForm.reset(); this.loaded.set(false);
      if (failure.kind === 'VERSION_CONFLICT' || failure.status === 428) { this.busy.set(false); const generation = this.generation; const refreshed = await this.load(); if (this.current(generation)) this.message.set(refreshed ? 'The notification changed. Review the refreshed list before retrying.' : 'The notification changed, but the list could not be refreshed. Refresh before retrying.'); return; }
    }
    this.message.set(failure.code === 'DELIVERY_SUPPRESSED' ? 'Delivery is paused. No retry was queued. Refresh after delivery is enabled.' : failure.status === 409 ? 'The notification cannot be retried in its current state. Refresh and review it.' : failure.status === 404 ? 'That notification or booking could not be found. Refresh the list.' : 'Operations information could not be loaded or updated. Refresh and try again.'); this.focus();
  }
  private focus(): void { this.host.nativeElement.querySelector<HTMLElement>('#operations-message')?.focus(); }
  private clear(): void { this.generation++; this.command?.discard(); this.command = null; this.filterForm.reset(); this.reasonForm.reset(); this.refunds.set([]); this.jobs.set([]); this.selected.set(null); this.cursor.set(null); this.busy.set(false); this.loaded.set(false); this.uncertain.set(false); this.message.set(''); }
}
