import { Component, DestroyRef, OnChanges, computed, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { AdministratorService, BookingsService } from './generated';
import { SessionAccess } from './access/session-access';
import { safeFailure } from './access/access-failure';

interface CalendarBooking { id: string; state: string; startInstant: string; sessionEndInstant: string; }

@Component({
  selector: 'app-calendar-actions',
  imports: [MatButtonModule],
  template: `<section aria-label="Calendar" [attr.aria-busy]="busy()">
    <h2>Calendar</h2>
    <p>Calendar copies do not update automatically. After a change or cancellation, you may need to update or remove an earlier copy manually. Importing again may create duplicates.</p>
    <button mat-stroked-button type="button" (click)="download()" [disabled]="busy() || disabled() || blocked()">Download calendar file</button>
    @if (url(); as file) { <p><a [href]="file" download="consultation.ics">Save calendar file</a></p> }
    @if (google(); as link) {
      <p><a [href]="link" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer">Add to Google Calendar</a></p>
      <p>This opens Google with only a generic consultation title and session times. Review and save there, or use the calendar file.</p>
    }
    <p role="status">{{ message() }}</p>
  </section>`,
})
export class CalendarActionsComponent implements OnChanges {
  readonly booking = input.required<CalendarBooking>();
  readonly owner = input(false);
  readonly disabled = input(false);
  readonly busy = signal(false);
  readonly blocked = signal(false);
  readonly url = signal<string | null>(null);
  readonly message = signal('');
  private readonly customerApi = inject(BookingsService);
  private readonly ownerApi = inject(AdministratorService);
  private readonly sessions = inject(SessionAccess);
  private active = true;
  private generation = 0;
  readonly google = computed(() => {
    const b = this.booking();
    if (this.blocked() || this.disabled() || b.state !== 'CONFIRMED') return null;
    const start = new Date(b.startInstant), end = new Date(b.sessionEndInstant);
    if (!Number.isFinite(start.getTime()) || !Number.isFinite(end.getTime()) || end <= start) return null;
    const utc = (date: Date) => date.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z');
    const query = new URLSearchParams({ action: 'TEMPLATE', text: 'Consultation', dates: utc(start) + '/' + utc(end) });
    return 'https://calendar.google.com/calendar/r/eventedit?' + query.toString();
  });

  constructor() {
    const remove = this.sessions.onInvalidated(() => { this.clear(); this.blocked.set(true); this.message.set('Your session ended. Sign in again or request a new management link.'); });
    inject(DestroyRef).onDestroy(() => { this.active = false; remove(); this.clear(); });
  }
  ngOnChanges(): void { this.clear(); }
  private clear(): void {
    this.generation++;
    const url = this.url(); if (url !== null) URL.revokeObjectURL(url);
    this.url.set(null); this.busy.set(false); this.message.set('');
  }
  async download(): Promise<void> {
    if (!this.active || this.blocked() || this.disabled() || this.busy()) return;
    this.clear(); const generation = this.generation, booking = this.booking();
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(booking.id)) { this.message.set('This calendar is unavailable. Refresh the booking.'); return; }
    this.busy.set(true);
    try {
      const request = this.owner() ? this.ownerApi.downloadAdministratorBookingCalendar(booking.id) : this.customerApi.downloadBookingCalendar(booking.id);
      const blob = await firstValueFrom(request.pipe(timeout(25000)));
      if (this.generation !== generation) return;
      if (blob.size === 0 || !/^text\/calendar(?:;|$)/i.test(blob.type)) throw new Error('INVALID_CALENDAR_RESPONSE');
      this.url.set(URL.createObjectURL(blob)); this.message.set('Use Save calendar file to save your copy, then import it into your calendar.');
    } catch (error) {
      if (this.generation !== generation) return;
      const failure = safeFailure(error, false);
      if (failure.kind === 'SESSION_EXPIRED') { this.sessions.invalidate(); return; }
      this.message.set(failure.status === 403 || failure.status === 404 ? 'This calendar is unavailable. Refresh the booking or sign in again.' : 'The calendar could not be downloaded. Please try again.');
    } finally { if (this.generation === generation) this.busy.set(false); }
  }
}
