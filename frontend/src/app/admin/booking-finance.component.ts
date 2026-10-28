import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { AdministratorBookingDetail, AdministratorService } from '../shared/generated';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';
import { brusselsDisplay, brusselsInstants } from './admin-time';
import { displayMoney, minorAmount } from './finance-money';
import { FinanceSummaryComponent } from './finance-summary.component';
import { reauthenticate } from './admin-reauth';

type Action = 'receipts' | 'refunds' | 'refund-requirements';
@Component({
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, FinanceSummaryComponent],
  template: `
    <section aria-labelledby="finance-title" [attr.aria-busy]="busy()">
      <p><a [routerLink]="'/admin/bookings/' + id">Back to booking</a></p><h1 id="finance-title">Receipts and refunds</h1>
      <p role="alert">{{ message() }}</p><button mat-stroked-button type="button" (click)="load()" [disabled]="busy() || uncertain()">Refresh money entries</button>
      @if (booking(); as b) {
        <p>{{ b.reference }} — {{ b.state }}</p><app-finance-summary [payment]="b.payment" />
        <form [formGroup]="form" (ngSubmit)="submit()">
          <fieldset [disabled]="busy() || uncertain()"><legend>Record external money or a refund requirement</legend>
            <label for="finance-action">Action</label><select id="finance-action" formControlName="action"><option value="receipts">Record money received</option><option value="refunds">Record money refunded</option><option value="refund-requirements">Require a refund</option></select>
            @if (form.controls.action.value !== 'receipts') { <label for="finance-receipt">Original receipt</label><select id="finance-receipt" formControlName="receiptId"><option value="">Choose a receipt</option>@for (receipt of receipts(); track receipt.id) { <option [value]="receipt.id">{{ money(receipt.amountMinor, receipt.currency) }} — {{ receipt.method }} — {{ receipt.reference }}</option> }</select> }
            @if (form.controls.action.value === 'refund-requirements') {
              <label for="finance-reason">Reason (10–500 characters)</label><textarea id="finance-reason" formControlName="reason" maxlength="500"></textarea><p>This records a requirement for the original receipt; it does not send money.</p>
            } @else {
              @if (form.controls.action.value === 'receipts') { <label for="finance-currency">Currency (three-letter code)</label><input id="finance-currency" formControlName="currency" maxlength="3" autocapitalize="characters"> } @else { <p>Refund currency: {{ currency() || 'Choose a receipt' }}. Refunds must use the original currency.</p> }
              <label for="finance-amount">Amount ({{ currency() }})</label><input id="finance-amount" formControlName="amount" inputmode="decimal" autocomplete="off"><p>Enter the exact amount using a decimal point, without grouping or rounding.</p>
              <label for="finance-method">Payment method</label><input id="finance-method" formControlName="method" maxlength="80" autocomplete="off">
              <label for="finance-reference">External transaction reference</label><input id="finance-reference" formControlName="reference" maxlength="120" autocomplete="off">
              <label for="finance-day">Verified date (Europe/Brussels)</label><input id="finance-day" type="date" formControlName="day">
              <label for="finance-time">Verified time (Europe/Brussels)</label><input id="finance-time" type="time" formControlName="time">
              @if (instants().length > 1) { <label for="finance-occurrence">Choose the UTC offset for this repeated time</label><select id="finance-occurrence" formControlName="verifiedAt"><option value="">Choose an occurrence</option>@for (instant of instants(); track instant) { <option [value]="instant">{{ display(instant) }}</option> }</select> }
              <p>Record only money already received or returned outside this application. This form never transfers funds.</p>
            }
            <button mat-flat-button type="submit" [disabled]="busy() || uncertain()">{{ form.controls.action.value === 'refund-requirements' ? 'Record refund requirement' : 'Record actual money' }}</button>
          </fieldset>
        </form>
        @if (uncertain()) { <button mat-stroked-button type="button" (click)="retry()" [disabled]="busy()">Retry the same entry</button> }
      }
    </section>`,
  styles: [`section { max-width: 42rem; margin: 1rem auto; padding: 1rem; } fieldset { display: grid; gap: .5rem; margin: 1rem 0; } input, select, textarea { font: inherit; min-height: 2.5rem; padding: .3rem; }`],
})
export class BookingFinanceComponent {
  private readonly api = inject(AdministratorService);
  private readonly commands = inject(CommandAccess);
  private readonly sessions = inject(SessionAccess);
  private readonly router = inject(Router);
  readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
  private active = true;
  private generation = 0;
  private command: FrozenCommand<unknown> | null = null;
  readonly booking = signal<AdministratorBookingDetail | null>(null);
  readonly busy = signal(false);
  readonly uncertain = signal(false);
  readonly message = signal('');
  readonly instants = signal<string[]>([]);
  readonly form = new FormGroup({ action: new FormControl<Action>('receipts', { nonNullable: true }), receiptId: new FormControl('', { nonNullable: true }), currency: new FormControl('EUR', { nonNullable: true }), amount: new FormControl('', { nonNullable: true }), method: new FormControl('', { nonNullable: true }), reference: new FormControl('', { nonNullable: true }), day: new FormControl('', { nonNullable: true }), time: new FormControl('', { nonNullable: true }), verifiedAt: new FormControl('', { nonNullable: true }), reason: new FormControl('', { nonNullable: true }) });
  constructor() {
    const changes = this.form.valueChanges.subscribe(() => {
      const candidates = brusselsInstants(this.form.controls.day.value, this.form.controls.time.value); this.instants.set(candidates);
      if (!candidates.includes(this.form.controls.verifiedAt.value)) this.form.controls.verifiedAt.setValue(candidates.length === 1 ? candidates[0] : '', { emitEvent: false });
    });
    const remove = this.sessions.onInvalidated(() => { this.generation++; this.clear(); });
    inject(DestroyRef).onDestroy(() => { this.active = false; this.generation++; changes.unsubscribe(); remove(); this.clear(); });
    void this.load();
  }
  display = brusselsDisplay;
  money = displayMoney;
  receipts() { return this.booking()!.payment.entries.filter(entry => entry.kind === 'RECEIPT'); }
  currency(): string { return this.form.controls.action.value === 'receipts' ? this.form.controls.currency.value.trim().toUpperCase() : this.receipts().find(r => r.id === this.form.controls.receiptId.value)?.currency ?? ''; }
  private validText(value: string, min: number, max: number): boolean { return [...value].length >= min && [...value].length <= max && !/[\u0000-\u001f\u007f]/.test(value); }
  async load(): Promise<boolean> {
    if (this.busy() || this.uncertain()) return false;
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(this.id)) { this.message.set('That booking could not be found.'); return false; }
    this.busy.set(true); const generation = this.generation;
    try {
      const detail = await firstValueFrom(this.api.getAdministratorBooking(this.id).pipe(timeout(25000)));
      if (!this.active || generation !== this.generation) return false;
      this.booking.set(detail); return true;
    } catch (error) { if (this.active && generation === this.generation) { this.booking.set(null); await this.failure(error, false); } return false; }
    finally { if (this.active) this.busy.set(false); }
  }
  async submit(): Promise<void> {
    if (this.busy() || this.uncertain() || this.booking() === null) return;
    const v = this.form.getRawValue(), currency = this.currency(), reason = v.reason.trim().normalize('NFC');
    if (v.action !== 'receipts' && !this.receipts().some(r => r.id === v.receiptId)) { this.message.set('Choose the original receipt.'); return; }
    let body: unknown;
    if (v.action === 'refund-requirements') {
      if (!this.validText(reason, 10, 500)) { this.message.set('Enter a refund reason of 10–500 characters.'); return; }
      body = { receiptId: v.receiptId, reason };
    } else {
      const amountMinor = minorAmount(v.amount, currency), method = v.method.trim().normalize('NFC'), reference = v.reference.trim().normalize('NFC');
      if (amountMinor === null || !this.validText(method, 1, 80) || !this.validText(reference, 1, 120) || !this.instants().includes(v.verifiedAt)) { this.message.set('Enter an exact positive amount, method, reference and unambiguous verified time.'); return; }
      body = { amountMinor, currency, method, reference, verifiedAt: v.verifiedAt, ...(v.action === 'refunds' ? { receiptId: v.receiptId } : {}) };
    }
    try { this.command?.discard(); this.command = this.commands.prepare('POST', '/api/v1/admin/bookings/' + this.id + '/' + v.action, body); }
    catch { this.message.set('This entry could not be submitted from this page.'); return; }
    await this.send();
  }
  async retry(): Promise<void> { if (!this.busy() && this.uncertain() && this.command !== null) await this.send(); }
  private async send(): Promise<void> {
    this.busy.set(true); this.message.set(''); const generation = this.generation;
    try {
      await this.command!.attempt();
      if (!this.active || generation !== this.generation) return;
      this.command = null; this.uncertain.set(false); this.form.reset(); this.busy.set(false);
      this.message.set(await this.load() ? 'Entry recorded. Updated money entries are shown.' : 'Entry recorded, but updated money entries could not be loaded. Refresh before entering more.');
    } catch (error) { if (this.active && generation === this.generation) await this.failure(error, true); }
    finally { if (this.active) this.busy.set(false); }
  }
  private async failure(error: unknown, mutation: boolean): Promise<void> {
    const failure = safeFailure(error, mutation);
    if (failure.kind === 'SESSION_EXPIRED') { this.clear(); await this.router.navigateByUrl('/admin/login').catch(() => false); return; }
    if (failure.status === 403) { await reauthenticate(this.sessions, this.router, '/admin/bookings/' + this.id + '/finance'); return; }
    if (this.uncertain() && failure.kind === 'THROTTLED') { this.message.set('Please wait, then retry the same entry. Its original result is still unknown.'); return; }
    this.uncertain.set(failure.kind === 'UNCERTAIN');
    if (this.uncertain()) { this.message.set('The response did not arrive. The entry may exist. Retry the same entry before recording another.'); return; }
    this.command?.discard(); this.command = null;
    if (failure.code === 'DUPLICATE_PAYMENT') this.message.set('That payment method and reference already exist. Refresh and reconcile the existing entry before recording more.');
    else if (failure.code === 'REFUND_EXCEEDS_RECEIPT') this.message.set('The refund exceeds the money remaining on that receipt. Refresh and review its recorded refunds.');
    else if (failure.kind === 'THROTTLED') this.message.set('Please wait before trying again.');
    else this.message.set(mutation ? 'The entry could not be recorded. Check the amount, currency, reference and verified time.' : 'Money entries could not be loaded. Try again.');
  }
  private clear(): void { this.command?.discard(); this.command = null; this.booking.set(null); this.form.reset(); this.uncertain.set(false); }
}
