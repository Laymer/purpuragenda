import { Component, DestroyRef, ElementRef, computed, inject, output, signal } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { Subscription } from 'rxjs';
import { Availability, CatalogService, Service, Slot } from '../shared/generated';
import { addDays, canonicalZone, civilDate, ConsultationSelection, retryDelay, selected, slotDuration, slotLabel, timeLabel, dateLabel, offsetLabel, validRange } from './selection-model';

@Component({
  selector: 'app-service-time',
  imports: [CurrencyPipe, ReactiveFormsModule, MatButtonModule, MatCardModule],
  templateUrl: './service-time.component.html',
  styleUrl: './service-time.component.css',
})
export class ServiceTimeComponent {
  private readonly catalog = inject(CatalogService);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly destroy = inject(DestroyRef);
  readonly selectionChanged = output<ConsultationSelection | null>();
  readonly services = signal<Service[]>([]);
  readonly catalogLoading = signal(false);
  readonly catalogError = signal<string | null>(null);
  readonly service = signal<Service | null>(null);
  readonly availability = signal<Availability | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly formError = signal<string | null>(null);
  readonly choice = signal<Slot | null>(null);
  readonly days = signal<string[]>([]);
  readonly day = signal('');
  readonly shownSlots = computed(() => this.availability()?.slots.filter(slot => slot.displayLocalTime.startsWith(this.day())) ?? []);
  readonly retrySeconds = signal(0);
  readonly zones = ['Europe/Brussels', 'UTC', 'America/New_York', 'America/Los_Angeles', 'Asia/Tokyo', 'Australia/Sydney'];
  readonly form = new FormGroup({
    from: new FormControl(civilDate(new Date(), 'Europe/Brussels'), { nonNullable: true }),
    to: new FormControl(addDays(civilDate(new Date(), 'Europe/Brussels'), 6), { nonNullable: true }),
    zone: new FormControl('Europe/Brussels', { nonNullable: true }),
  });
  readonly label = slotLabel;
  readonly time = timeLabel;
  readonly date = dateLabel;
  readonly duration = slotDuration;
  readonly offset = offsetLabel;
  private catalogRequest?: Subscription;
  private timesRequest?: Subscription;
  private timer?: ReturnType<typeof setInterval>;

  constructor() {
    const changes = this.form.valueChanges.subscribe(() => this.clearTimes());
    this.destroy.onDestroy(() => {
      changes.unsubscribe(); this.catalogRequest?.unsubscribe(); this.timesRequest?.unsubscribe(); clearInterval(this.timer);
    });
    this.loadServices();
  }

  loadServices(): void {
    if (this.retrySeconds() > 0) return;
    this.clearTimes(); this.service.set(null); this.services.set([]);
    this.catalogRequest?.unsubscribe(); this.catalogLoading.set(true); this.catalogError.set(null);
    this.catalogRequest = this.catalog.listServices().subscribe({
      next: values => { this.services.set(values.filter(value => value.active)); this.catalogLoading.set(false); },
      error: (failure: HttpErrorResponse) => {
        this.catalogLoading.set(false); this.catalogError.set('We could not load consultations. Please try again.'); this.throttled(failure);
      },
    });
  }

  chooseService(value: Service): void {
    this.clearTimes(); this.service.set(value); this.search();
  }

  search(): void {
    if (this.retrySeconds() > 0) return;
    this.clearTimes();
    const service = this.service();
    if (service === null) return;
    const { from, to, zone } = this.form.getRawValue();
    const canonical = canonicalZone(zone);
    if (!validRange(from, to) || canonical === null) {
      this.formError.set('Choose valid dates covering no more than 31 days, and a valid IANA time zone.');
      this.host.nativeElement.querySelector<HTMLInputElement>(canonical === null ? '#display-zone' : '#from-date')?.focus();
      return;
    }
    this.form.controls.zone.setValue(canonical, { emitEvent: false });
    this.loading.set(true);
    this.timesRequest = this.catalog.availability(service.id, from, to, canonical).subscribe({
      next: values => {
        const days = [...new Set(values.slots.map(slot => slot.displayLocalTime.slice(0, 10)))].sort();
        this.days.set(days); this.day.set(days[0] ?? ''); this.availability.set(values); this.loading.set(false);
      },
      error: (failure: HttpErrorResponse) => {
        this.loading.set(false);
        this.error.set(failure.status === 404 ? 'This consultation is no longer available. Reload consultations and choose again.' : 'We could not load times. Please try again.');
        this.throttled(failure);
      },
    });
  }

  chooseDay(day: string): void {
    this.day.set(day); this.choice.set(null); this.selectionChanged.emit(null);
  }

  chooseTime(slot: Slot): void {
    this.choice.set(slot);
    this.selectionChanged.emit(selected(this.service()!, this.availability()!, slot));
  }

  private clearTimes(): void {
    this.timesRequest?.unsubscribe(); this.loading.set(false); this.availability.set(null);
    this.error.set(null); this.formError.set(null); this.choice.set(null); this.days.set([]); this.day.set(''); this.selectionChanged.emit(null);
  }

  private throttled(failure: HttpErrorResponse): void {
    if (failure.status !== 429) return;
    const until = Date.now() + retryDelay(failure.headers.get('Retry-After'), Date.now()) * 1000;
    const update = () => {
      this.retrySeconds.set(Math.max(0, Math.ceil((until - Date.now()) / 1000)));
      if (this.retrySeconds() === 0) clearInterval(this.timer);
    };
    clearInterval(this.timer); update(); this.timer = setInterval(update, 1000);
  }
}
