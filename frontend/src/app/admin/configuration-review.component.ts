import { Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AdministratorConflictPreview } from '../shared/generated';
import { brusselsDisplay } from './admin-time';

@Component({
  selector: 'app-configuration-review',
  imports: [RouterLink],
  template: `
    <h2>Review affected bookings</h2><p>Assessed {{ display(preview().assessedAt) }}. Existing reservations and snapshots stay unchanged.</p>
    @if (!preview().conflicts.length) { <p>No new conflicts with existing future bookings were found.</p> }
    <ul>@for (conflict of preview().conflicts; track conflict.bookingId) { <li><a [routerLink]="'/admin/bookings/' + conflict.bookingId">{{ conflict.reference }}</a> — {{ display(conflict.startInstant) }} — {{ conflict.state }}
      <ul>@for (reason of conflict.reasons; track reason) { <li>{{ label(reason) }}</li> }</ul>
    </li> }</ul>
    <label><input type="checkbox" [checked]="acknowledged()" [disabled]="disabled()" (change)="change($event)">I reviewed these results and acknowledge every affected booking.</label>`,
})
export class ConfigurationReviewComponent {
  readonly preview = input.required<AdministratorConflictPreview>();
  readonly acknowledged = input(false);
  readonly disabled = input(false);
  readonly acknowledge = output<boolean>();
  display = brusselsDisplay;
  change(event: Event): void { this.acknowledge.emit((event.target as HTMLInputElement).checked); }
  label(reason: string): string { return ({ START_RULES: 'Outside the proposed start schedule', HARD_BLACKOUT: 'Intersects a proposed blackout', DAILY_CAPACITY: 'Exceeds the proposed daily capacity' } as Record<string, string>)[reason] ?? 'Requires review'; }
}
