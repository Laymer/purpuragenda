import { Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { AccountHistoryBooking, AdministratorCustomer, AdministratorService } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';
import { brusselsDisplay } from './admin-time';
import { reauthenticate } from './admin-reauth';

@Component({
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule],
  template: `
    <section aria-labelledby="customers-title" [attr.aria-busy]="busy()">
      <h1 id="customers-title">Customers</h1>
      <form [formGroup]="form" (ngSubmit)="search()">
        <label for="customer-query">Search by name, email or booking reference</label>
        <input id="customer-query" formControlName="query" maxlength="508" autocomplete="off" [readOnly]="busy()" />
        <button mat-flat-button type="submit" [disabled]="busy()">Search customers</button>
      </form>
      <p id="customer-message" role="status" tabindex="-1">{{ message() }}</p>
      @if (searched()) {
        <h2>Search results</h2>
        <p>Results identify the original verified customer. A delivery email match does not change booking ownership.</p>
        <ul>@for (customer of customers(); track customer.id) {
          <li><p>{{ customer.email }} — {{ customer.enrolled ? 'Account enrolled' : 'Guest customer' }}</p>
            <button mat-stroked-button type="button" (click)="open(customer.id)" [disabled]="busy()">View bookings for {{ customer.email }}</button></li>
        } @empty { <li>No customers on this page.</li> }</ul>
        @if (customerCursor()) { <button mat-stroked-button type="button" (click)="moreCustomers()" [disabled]="busy()">Next customer page</button> }
      }
      @if (selected(); as customer) {
        <h2>Bookings for {{ customer.email }}</h2><p>Times shown in Europe/Brussels, newest scheduled time first.</p>
        <button mat-stroked-button type="button" (click)="open(customer.id)" [disabled]="busy()">Show newest bookings</button>
        <ul>@for (booking of bookings(); track booking.id) {
          <li><a [routerLink]="'/admin/bookings/' + booking.id">{{ booking.reference }}</a>
            <p>{{ booking.serviceName }} — {{ display(booking.startInstant) }}</p>
            <p>{{ booking.state.replaceAll('_', ' ') }} — {{ (booking.priceMinor / 100).toFixed(2) }} {{ booking.currency }}</p>
            @if (booking.pendingExpired) { <p>The hold has expired and is awaiting processing.</p> }
            <a [routerLink]="'/admin/bookings/' + booking.id + '/history'">Private notes and audit</a></li>
        } @empty { <li>No bookings on this page.</li> }</ul>
        @if (bookingCursor()) { <button mat-stroked-button type="button" (click)="moreBookings()" [disabled]="busy()">Older bookings</button> }
      }
    </section>`,
  styles: [`section { max-width: 48rem; margin: 1rem auto; padding: 1rem; } form { display: grid; gap: .5rem; margin: 1rem 0; } input { font: inherit; padding: .6rem; min-width: 0; } ul { padding-left: 1.2rem; } li { margin: 1rem 0; overflow-wrap: anywhere; }`],
})
export class CustomersComponent {
  private readonly api = inject(AdministratorService);
  private readonly sessions = inject(SessionAccess);
  private readonly router = inject(Router);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private active = true;
  private blocked = false;
  private generation = 0;
  private query = '';
  readonly form = new FormGroup({ query: new FormControl('', { nonNullable: true }) });
  readonly customers = signal<AdministratorCustomer[]>([]);
  readonly selected = signal<AdministratorCustomer | null>(null);
  readonly bookings = signal<AccountHistoryBooking[]>([]);
  readonly customerCursor = signal<string | null>(null);
  readonly bookingCursor = signal<string | null>(null);
  readonly searched = signal(false);
  readonly busy = signal(false);
  readonly message = signal('');
  readonly display = brusselsDisplay;
  constructor() {
    const remove = this.sessions.onInvalidated(() => { this.blocked = true; this.clear(); });
    inject(DestroyRef).onDestroy(() => { this.active = false; remove(); this.clear(); });
  }
  private available(): boolean { return this.active && !this.blocked && !this.busy(); }
  private current(generation: number): boolean { return this.active && generation === this.generation; }
  async search(): Promise<void> {
    if (!this.available()) return;
    const query = this.form.controls.query.value.trim().normalize('NFC');
    if ([...query].length < 2 || [...query].length > 254 || /[\u0000-\u001f\u007f-\u009f\ud800-\udfff]/u.test(query)) { this.message.set('Enter 2–254 characters without control characters.'); this.focus(); return; }
    this.clear(); this.form.controls.query.setValue(query); this.query = query; await this.fetchCustomers();
  }
  async moreCustomers(): Promise<void> { if (this.available() && this.customerCursor()) await this.fetchCustomers(this.customerCursor()!); }
  private async fetchCustomers(cursor?: string): Promise<void> {
    this.busy.set(true); this.message.set(''); const generation = this.generation;
    try {
      const page = await firstValueFrom(this.api.searchAdministratorCustomers(this.query, 20, cursor).pipe(timeout(25000)));
      if (!this.current(generation)) return;
      this.customers.set(page.entries); this.customerCursor.set(page.nextCursor ?? null); this.searched.set(true); this.selected.set(null); this.bookings.set([]); this.bookingCursor.set(null); this.message.set(page.entries.length + ' customers on this page.');
    } catch (error) { if (this.current(generation)) await this.failure(error); }
    finally { if (this.current(generation)) this.busy.set(false); }
  }
  async open(id: string): Promise<void> {
    if (!this.available() || !this.customers().some(customer => customer.id === id)) return;
    this.selected.set(null); this.bookings.set([]); this.bookingCursor.set(null); await this.fetchBookings(id);
  }
  async moreBookings(): Promise<void> { const customer = this.selected(); if (this.available() && customer && this.bookingCursor()) await this.fetchBookings(customer.id, this.bookingCursor()!); }
  private async fetchBookings(id: string, cursor?: string): Promise<void> {
    this.busy.set(true); this.message.set(''); const generation = this.generation;
    try {
      const page = await firstValueFrom(this.api.getAdministratorCustomerBookings(id, 20, cursor).pipe(timeout(25000)));
      if (!this.current(generation)) return;
      this.selected.set(page.customer); this.bookings.set(page.bookings); this.bookingCursor.set(page.nextCursor ?? null); this.message.set(page.bookings.length + ' bookings on this page.');
    } catch (error) { if (this.current(generation)) await this.failure(error); }
    finally { if (this.current(generation)) this.busy.set(false); }
  }
  private async failure(error: unknown): Promise<void> {
    const failure = safeFailure(error, false);
    if (failure.kind === 'SESSION_EXPIRED') { this.blocked = true; this.clear(); this.sessions.invalidate(); await this.router.navigateByUrl('/admin/login').catch(() => false); return; }
    if (failure.status === 403) { await reauthenticate(this.sessions, this.router, '/admin/customers'); return; }
    this.message.set(failure.status === 404 ? 'That customer could not be found. Search again.' : failure.kind === 'THROTTLED' ? 'Please wait before trying again.' : 'Customer information could not be loaded. Try again.'); this.focus();
  }
  private focus(): void { this.host.nativeElement.querySelector<HTMLElement>('#customer-message')?.focus(); }
  private clear(): void { this.generation++; this.query = ''; this.form.reset(); this.customers.set([]); this.selected.set(null); this.bookings.set([]); this.customerCursor.set(null); this.bookingCursor.set(null); this.searched.set(false); this.busy.set(false); this.message.set(''); }
}
