import { Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { Subscription } from 'rxjs';
import { Availability, BookingsService, Slot } from '../shared/generated';
import { addDays, canonicalZone, civilDate, dateLabel, offsetLabel, retryDelay, timeLabel, validRange } from '../public/selection-model';

export interface RescheduleChoice { slot: Slot; policyVersion: number; displayZone: string; }

/** Slot picker for one booking. Availability is advisory; the reschedule command revalidates everything. */
@Component({
  selector: 'app-reschedule-picker',
  imports: [ReactiveFormsModule, MatButtonModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="search()" aria-label="Find new times">
      <label for="re-from">From</label><input id="re-from" type="date" formControlName="from">
      <label for="re-to">Through (at most 31 dates)</label><input id="re-to" type="date" formControlName="to">
      <label for="re-zone">Time zone</label><input id="re-zone" type="text" list="re-zones" formControlName="zone" autocomplete="off" spellcheck="false">
      <datalist id="re-zones">@for (zone of zones; track zone) { <option [value]="zone"></option> }</datalist>
      <button mat-flat-button type="submit" [disabled]="loading() || retrySeconds() > 0">{{ loading() ? 'Searching…' : 'Show available times' }}</button>
    </form>
    @if (retrySeconds() > 0) { <p role="status">Please wait {{ retrySeconds() }} seconds before searching again.</p> }
    <p role="alert">{{ problem() }}</p>
    @if (availability(); as found) {
      <p>Times are shown in {{ found.displayZone }}. Each time shows its UTC offset.</p>
      @if (found.slots.length === 0) { <p>No times are available in this range. If the change window has closed, this booking can no longer be moved.</p> }
      @else {
        <div role="group" aria-label="Days">
          @for (day of days(); track day) { <button mat-stroked-button type="button" [attr.aria-pressed]="day === shownDay()" (click)="shownDay.set(day)">{{ date(day) }}</button> }
        </div>
        <ul class="times">
          @for (slot of shown(); track slot.startInstant) {
            <li><button mat-stroked-button type="button" [attr.aria-pressed]="chosen()?.startInstant === slot.startInstant" (click)="choose(slot)">{{ time(slot, found.displayZone) }} · {{ offset(slot.offset) }}</button></li>
          }
        </ul>
      }
    }`,
  styles: [`form { display: grid; gap: .5rem; max-width: 24rem; } input { font: inherit; min-height: 2.6rem; padding: .4rem; } .times { list-style: none; padding: 0; display: flex; flex-wrap: wrap; gap: .5rem; } button { margin: .25rem .5rem .25rem 0; }`],
})
export class ReschedulePickerComponent {
  private readonly bookings = inject(BookingsService);
  readonly bookingId = input.required<string>();
  readonly zone = input<string>('Europe/Brussels');
  readonly chosenSlot = output<RescheduleChoice | null>();
  readonly sessionLost = output<void>();
  readonly availability = signal<Availability | null>(null);
  readonly loading = signal(false);
  readonly problem = signal('');
  readonly retrySeconds = signal(0);
  readonly chosen = signal<Slot | null>(null);
  readonly shownDay = signal('');
  readonly days = computed(() => [...new Set((this.availability()?.slots ?? []).map(slot => slot.displayLocalTime.slice(0, 10)))].sort());
  readonly shown = computed(() => this.availability()?.slots.filter(slot => slot.displayLocalTime.startsWith(this.shownDay())) ?? []);
  readonly zones = ['Europe/Brussels', 'UTC', 'America/New_York', 'America/Los_Angeles', 'Asia/Tokyo', 'Australia/Sydney'];
  readonly form = new FormGroup({
    from: new FormControl(civilDate(new Date(), 'Europe/Brussels'), { nonNullable: true }),
    to: new FormControl(addDays(civilDate(new Date(), 'Europe/Brussels'), 6), { nonNullable: true }),
    zone: new FormControl('Europe/Brussels', { nonNullable: true }),
  });
  readonly date = dateLabel;
  readonly offset = offsetLabel;
  readonly time = timeLabel;
  private request?: Subscription;
  private timer?: ReturnType<typeof setInterval>;

  constructor() {
    queueMicrotask(() => { const zone = canonicalZone(this.zone()); if (zone !== null) this.form.controls.zone.setValue(zone, { emitEvent: false }); });
    const changes = this.form.valueChanges.subscribe(() => this.reset());
    inject(DestroyRef).onDestroy(() => { changes.unsubscribe(); this.request?.unsubscribe(); clearInterval(this.timer); });
  }

  search(): void {
    if (this.loading() || this.retrySeconds() > 0) return;
    this.reset();
    const { from, to, zone } = this.form.getRawValue(), canonical = canonicalZone(zone);
    if (!validRange(from, to) || canonical === null) { this.problem.set('Choose valid dates covering no more than 31 days, and a valid time zone.'); return; }
    this.form.controls.zone.setValue(canonical, { emitEvent: false });
    this.loading.set(true);
    this.request = this.bookings.bookingReschedulingAvailability(this.bookingId(), from, to, canonical).subscribe({
      next: value => { this.availability.set(value); this.shownDay.set([...value.slots].map(slot => slot.displayLocalTime.slice(0, 10)).sort()[0] ?? ''); this.loading.set(false); },
      error: (failure: HttpErrorResponse) => {
        this.loading.set(false);
        if (failure.status === 401 || failure.status === 404) { this.sessionLost.emit(); return; }
        this.problem.set(failure.status === 400 ? 'Check the dates and time zone, then try again.' : 'Times could not be loaded. Try again.');
        if (failure.status === 429) this.throttle(retryDelay(failure.headers.get('Retry-After'), Date.now()));
      },
    });
  }

  choose(slot: Slot): void {
    this.chosen.set(slot);
    this.chosenSlot.emit({ slot, policyVersion: this.availability()!.policyVersion, displayZone: this.availability()!.displayZone });
  }

  private reset(): void {
    this.request?.unsubscribe(); this.loading.set(false); this.availability.set(null); this.problem.set(''); this.chosen.set(null); this.shownDay.set(''); this.chosenSlot.emit(null);
  }

  private throttle(seconds: number): void {
    const until = Date.now() + seconds * 1000;
    const update = () => { this.retrySeconds.set(Math.max(0, Math.ceil((until - Date.now()) / 1000))); if (this.retrySeconds() === 0) clearInterval(this.timer); };
    clearInterval(this.timer); update(); this.timer = setInterval(update, 1000);
  }
}
