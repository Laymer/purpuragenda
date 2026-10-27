import { Component, input } from '@angular/core';
import { CustomerBookingStatus } from './generated';

/** Display zone wall time with an explicit UTC offset label. */
export function zonedInstant(instant: string, zone: string): string {
  const date = new Date(instant);
  const offset = new Intl.DateTimeFormat('en', { timeZone: zone, timeZoneName: 'longOffset' }).formatToParts(date).find(part => part.type === 'timeZoneName')!.value.replace(/^GMT$/, 'UTC+00:00').replace(/^GMT/, 'UTC');
  return new Intl.DateTimeFormat('en', { dateStyle: 'full', timeStyle: 'short', timeZone: zone }).format(date) + ' · ' + offset;
}

/** Presentational customer booking status; every value is rendered as text. */
@Component({
  selector: 'app-booking-status',
  template: `
    @let b = booking();
    <p>Reference: <strong>{{ b.reference }}</strong></p>
    <p>{{ b.service.name }} · {{ b.service.durationMinutes }} minutes</p>
    <p>{{ at(b.startInstant) }} · {{ b.displayZone }}</p>
    <p>Status: {{ b.state.replaceAll('_', ' ').toLowerCase() }}.</p>
    @if (b.pendingExpired) { <p>The hold has expired and is awaiting processing.</p> }
    @if (b.state === 'PENDING_CONFIRMATION' && b.pendingExpiresAt) { <p>Hold expires: {{ at(b.pendingExpiresAt) }} · {{ b.displayZone }}.</p> }
    @if (b.allowedActions.length > 0) { <p>Customer changes close: {{ at(b.customerChangesUntil) }} · {{ b.displayZone }}.</p> }
    <p>Payment: {{ b.payment.status.replaceAll('_', ' ').toLowerCase() }}. Confirmation does not imply payment.</p>
    <p>Updates are sent to {{ b.deliveryEmail }}.</p>
    @if (b.meetingUrl) { <a [href]="b.meetingUrl" target="_blank" rel="noopener noreferrer">Open meeting link</a> }`,
})
export class BookingStatusComponent {
  readonly booking = input.required<CustomerBookingStatus>();
  at(instant: string): string { return zonedInstant(instant, this.booking().displayZone); }
}
