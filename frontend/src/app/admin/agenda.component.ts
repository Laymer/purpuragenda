import { NgTemplateOutlet } from '@angular/common';
import { Component, DestroyRef, ElementRef, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { AdministratorAgendaEntry, AdministratorAgendaEntryStateEnum, AdministratorService } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';
import { brusselsDate, brusselsDisplay, rangeProblem, weekOf } from './admin-time';

@Component({
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, NgTemplateOutlet],
  template: `
    <section aria-labelledby="agenda-title" [attr.aria-busy]="busy()">
      <h1 id="agenda-title" tabindex="-1">Agenda</h1>
      <form [formGroup]="range" (ngSubmit)="load()">
        <label for="agenda-from">From (Brussels date)</label>
        <input id="agenda-from" type="date" formControlName="from">
        <label for="agenda-through">Through (Brussels date)</label>
        <input id="agenda-through" type="date" formControlName="through">
        <div class="actions">
          <button mat-stroked-button type="button" (click)="today()">Today</button>
          <button mat-stroked-button type="button" (click)="thisWeek()">This week</button>
          <button mat-flat-button type="submit" [disabled]="busy()">Show</button>
        </div>
      </form>
      <p id="agenda-error" role="alert" tabindex="-1">{{ message() }}</p>
      <h2>Awaiting confirmation ({{ pending().length }})</h2>
      @if (pending().length === 0) { <p>Nothing is awaiting confirmation in this range.</p> }
      <ul>@for (entry of pending(); track entry.id) { <li><ng-container [ngTemplateOutlet]="row" [ngTemplateOutletContext]="{ $implicit: entry }" /></li> }</ul>
      <h2>All bookings ({{ entries().length }})</h2>
      @if (loaded() && entries().length === 0) { <p>No bookings in this range.</p> }
      <ul>@for (entry of entries(); track entry.id) { <li><ng-container [ngTemplateOutlet]="row" [ngTemplateOutletContext]="{ $implicit: entry }" /></li> }</ul>
      @if (cursor() !== null) { <button mat-stroked-button type="button" (click)="more()" [disabled]="busy()">Load more</button> }
    </section>
    <ng-template #row let-entry>
      <a [routerLink]="['/admin/bookings', entry.id]">{{ entry.reference }}</a>
      <span> · {{ entry.serviceName }} · {{ display(entry.startInstant) }} · {{ label(entry.state) }}</span>
    </ng-template>`,
  styles: [`section { max-width: 60rem; margin: 1rem auto; padding: 1rem; } form { display: grid; gap: .5rem; max-width: 24rem; } .actions { display: flex; flex-wrap: wrap; gap: .5rem; } input { font: inherit; min-height: 2.6rem; padding: .4rem; } ul { padding-left: 1.2rem; } li { margin: .4rem 0; }`],
})
export class AgendaComponent {
  private readonly api = inject(AdministratorService);
  private readonly sessions = inject(SessionAccess);
  private readonly router = inject(Router);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private generation = 0;
  private active = true;
  readonly range = new FormGroup({ from: new FormControl('', { nonNullable: true }), through: new FormControl('', { nonNullable: true }) });
  readonly entries = signal<AdministratorAgendaEntry[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly busy = signal(false);
  readonly loaded = signal(false);
  readonly message = signal('');
  readonly pending = computed(() => this.entries().filter(entry => entry.state === AdministratorAgendaEntryStateEnum.PendingConfirmation));

  constructor() {
    const remove = this.sessions.onInvalidated(() => { this.generation++; this.entries.set([]); this.cursor.set(null); this.loaded.set(false); });
    inject(DestroyRef).onDestroy(() => { this.active = false; this.generation++; remove(); });
    this.today();
  }

  display(instant: string): string { return brusselsDisplay(instant); }
  label(state: string): string { return state.toLowerCase().replaceAll('_', ' '); }
  today(): void { const day = brusselsDate(new Date()); this.range.setValue({ from: day, through: day }); void this.load(); }
  thisWeek(): void { const week = weekOf(brusselsDate(new Date())); this.range.setValue(week); void this.load(); }

  load(): Promise<void> { this.entries.set([]); this.cursor.set(null); return this.fetch(null); }
  more(): Promise<void> { const cursor = this.cursor(); return cursor === null ? Promise.resolve() : this.fetch(cursor); }

  private async fetch(cursor: string | null): Promise<void> {
    if (this.busy()) return;
    const { from, through } = this.range.getRawValue();
    const problem = rangeProblem(from, through);
    if (problem !== null) { this.message.set(problem); this.focusError(); return; }
    const generation = ++this.generation;
    this.busy.set(true); this.message.set('');
    try {
      const page = await firstValueFrom(this.api.listAdministratorBookings(from, through, 50, cursor ?? undefined).pipe(timeout(25000)));
      if (!this.active || generation !== this.generation) return;
      this.entries.update(current => [...current, ...page.entries]); this.cursor.set(page.nextCursor); this.loaded.set(true);
    } catch (error) {
      if (!this.active || generation !== this.generation) return;
      const failure = safeFailure(error, false);
      if (failure.kind === 'SESSION_EXPIRED' || failure.status === 403) { this.sessions.invalidate(); await this.router.navigateByUrl('/admin/login').catch(() => false); return; }
      this.message.set(failure.kind === 'REJECTED' ? 'Choose a valid range of at most 31 dates.' : 'The agenda could not be loaded. Try again.');
      this.focusError();
    } finally { if (this.active) this.busy.set(false); }
  }

  private focusError(): void { this.host.nativeElement.querySelector<HTMLElement>('#agenda-error')?.focus(); }
}
