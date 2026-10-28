import { Component, input } from '@angular/core';
import { AdministratorBookingPayment } from '../shared/generated';
import { brusselsDisplay } from './admin-time';
import { displayMoney } from './finance-money';

@Component({
  selector: 'app-finance-summary',
  template: `
    <h3>Money by currency</h3>
    @for (currency of currencies(); track currency) { <p>{{ currency }}: received {{ money(payment().receivedByCurrency[currency] ?? 0, currency) }}; outstanding refund {{ money(payment().refundDueByCurrency[currency] ?? 0, currency) }}.</p> }
    @if (!payment().entries.length) { <p>No actual money recorded.</p> }
    @for (entry of payment().entries; track entry.id) { <details><summary>{{ entry.kind }} — {{ money(entry.amountMinor, entry.currency) }} — {{ display(entry.verifiedAt) }}</summary>
      <dl><dt>Method</dt><dd>{{ entry.method }}</dd><dt>Reference</dt><dd>{{ entry.reference }}</dd><dt>Entry ID</dt><dd>{{ entry.id }}</dd>@if (entry.receiptId) { <dt>Original receipt</dt><dd>{{ entry.receiptId }}</dd> }</dl>
    </details> }
    @if (payment().refundObligations.length) { <h3>Refund requirements</h3><p>Each requirement records the full original receipt amount. Recorded refunds reduce the outstanding totals above.</p>
      @for (obligation of payment().refundObligations; track obligation.receiptId) { <p>Receipt {{ obligation.receiptId }} — {{ obligation.reason }}</p> }
    }
    <p>Money entries are immutable. Recording a receipt does not approve or revive a booking. Currencies are not converted.</p>`,
  styles: [`dd { margin: 0 0 .5rem; overflow-wrap: anywhere; } details { margin: .5rem 0; }`],
})
export class FinanceSummaryComponent {
  readonly payment = input.required<AdministratorBookingPayment>();
  money = displayMoney;
  display = brusselsDisplay;
  currencies(): string[] { return [...new Set([...Object.keys(this.payment().receivedByCurrency), ...Object.keys(this.payment().refundDueByCurrency)])].sort(); }
}
