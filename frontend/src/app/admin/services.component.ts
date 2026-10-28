import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { AdministratorService, AdministratorServiceDefinition } from '../shared/generated';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';
import { displayMoney, minorAmount } from './finance-money';
import { reauthenticate } from './admin-reauth';

@Component({
  imports: [ReactiveFormsModule, MatButtonModule],
  template: `
    <section aria-labelledby="services-title" [attr.aria-busy]="busy()">
      <h1 id="services-title">Services</h1><p role="alert">{{ message() }}</p>
      <p>Changes apply to future bookings. Existing bookings retain their service name, duration and price. Every booking reserves 120 minutes.</p>
      <button mat-stroked-button type="button" (click)="load()" [disabled]="busy() || uncertain()">Refresh services</button>
      @if (services(); as list) {
        <ul>@for (service of list; track service.id) { <li><strong>{{ service.name }}</strong> — {{ service.durationMinutes }} minutes — {{ money(service.priceMinor, 'EUR') }} — {{ service.active ? 'Active' : 'Archived' }}
          <button mat-stroked-button type="button" (click)="edit(service)" [disabled]="busy() || uncertain()">Manage {{ service.name }}</button></li> }</ul>
        @if (!list.length) { <p>No services yet.</p> }
        <button mat-stroked-button type="button" (click)="create()" [disabled]="busy() || uncertain()">New service</button>
        <h2>{{ selected() ? 'Manage service' : 'Create service' }}</h2>
        <form [formGroup]="form" (ngSubmit)="save()"><fieldset [disabled]="busy() || uncertain()">
          <legend>{{ selected()?.name ?? 'New service' }}</legend>
          <label for="service-name">Name</label><input id="service-name" formControlName="name" maxlength="120">
          <label for="service-duration">Session duration (1–120 minutes)</label><input id="service-duration" formControlName="duration" type="number" min="1" max="120" step="1">
          <label for="service-price">Price (EUR, 0–1,000,000)</label><input id="service-price" formControlName="price" inputmode="decimal">
          <label for="service-reason">Reason (10–500 characters)</label><textarea id="service-reason" formControlName="reason" maxlength="500"></textarea>
          <button mat-flat-button type="submit" [disabled]="busy() || uncertain()">{{ selected() ? 'Save service changes' : 'Create service' }}</button>
          @if (selected(); as service) {
            <p>{{ service.active ? 'Archiving removes this service from new public bookings.' : 'Restoring makes this service available for new public bookings.' }} Existing bookings stay unchanged. No customer email is sent.</p>
            <button mat-stroked-button type="button" (click)="setActive()" [disabled]="busy() || uncertain()">{{ service.active ? 'Archive service' : 'Restore service' }}</button>
          }
        </fieldset></form>
        @if (uncertain()) { <button mat-stroked-button type="button" (click)="retry()" [disabled]="busy()">Retry the same service change</button> }
      }
    </section>`,
  styles: [`section { max-width: 50rem; margin: 1rem auto; padding: 1rem; } fieldset { display: grid; gap: .5rem; margin: 1rem 0; } input, textarea { font: inherit; min-height: 2.5rem; padding: .3rem; } li { margin: 1rem 0; overflow-wrap: anywhere; }`],
})
export class ServicesComponent {
  private readonly api = inject(AdministratorService);
  private readonly commands = inject(CommandAccess);
  private readonly sessions = inject(SessionAccess);
  private readonly router = inject(Router);
  private active = true;
  private generation = 0;
  private command: FrozenCommand<unknown> | null = null;
  readonly services = signal<AdministratorServiceDefinition[] | null>(null);
  readonly selected = signal<AdministratorServiceDefinition | null>(null);
  readonly busy = signal(false);
  readonly uncertain = signal(false);
  readonly message = signal('');
  readonly form = new FormGroup({ name: new FormControl('', { nonNullable: true }), duration: new FormControl(60, { nonNullable: true }), price: new FormControl('', { nonNullable: true }), reason: new FormControl('', { nonNullable: true }) });
  money = displayMoney;
  constructor() {
    const remove = this.sessions.onInvalidated(() => { this.generation++; this.clear(); });
    inject(DestroyRef).onDestroy(() => { this.active = false; this.generation++; remove(); this.clear(); });
    void this.load();
  }
  edit(service: AdministratorServiceDefinition): void {
    if (this.busy() || this.uncertain()) return;
    this.selected.set(service); this.form.setValue({ name: service.name, duration: service.durationMinutes, price: (service.priceMinor / 100).toFixed(2), reason: '' }); this.message.set('');
  }
  create(): void { if (!this.busy() && !this.uncertain()) { this.selected.set(null); this.form.reset(); this.message.set(''); } }
  private valid(value: string, min: number, max: number): boolean { return [...value].length >= min && [...value].length <= max && !/[\u0000-\u001f\u007f]/.test(value); }
  async load(): Promise<boolean> {
    if (this.busy() || this.uncertain()) return false;
    this.busy.set(true); const generation = this.generation;
    try {
      const configuration = await firstValueFrom(this.api.getAdministratorConfiguration().pipe(timeout(25000)));
      if (!this.active || generation !== this.generation) return false;
      this.services.set(configuration.services); this.selected.set(null); this.form.reset(); return true;
    } catch (error) { if (this.active && generation === this.generation) { this.services.set(null); await this.failure(error, false); } return false; }
    finally { if (this.active) this.busy.set(false); }
  }
  async save(): Promise<void> {
    if (this.busy() || this.uncertain() || this.services() === null) return;
    const v = this.form.getRawValue(), name = v.name.trim().normalize('NFC');
    const priceMinor = /^0(?:\.0{1,2})?$/.test(v.price.trim()) ? 0 : minorAmount(v.price, 'EUR');
    if (!this.valid(name, 1, 120) || !Number.isInteger(v.duration) || v.duration < 1 || v.duration > 120 || priceMinor === null || priceMinor > 100000000) { this.message.set('Enter a name, a whole duration of 1–120 minutes and an exact EUR price from 0 to 1,000,000.'); return; }
    await this.prepare('', { name, durationMinutes: v.duration, priceMinor });
  }
  async setActive(): Promise<void> {
    if (this.busy() || this.uncertain() || this.selected() === null) return;
    await this.prepare(this.selected()!.active ? '/archive' : '/restore', {});
  }
  private async prepare(suffix: string, values: Record<string, unknown>): Promise<void> {
    const reason = this.form.controls.reason.value.trim().normalize('NFC');
    if (!this.valid(reason, 10, 500)) { this.message.set('Enter a reason of 10–500 characters.'); return; }
    const service = this.selected();
    try { this.command?.discard(); this.command = this.commands.prepare('POST', '/api/v1/admin/configuration/services' + (service === null ? '' : '/' + service.id) + suffix, { ...values, reason }, service === null ? undefined : '"' + service.version + '"'); }
    catch { this.message.set('This change could not be submitted from this page.'); return; }
    await this.send();
  }
  async retry(): Promise<void> { if (!this.busy() && this.uncertain() && this.command !== null) await this.send(); }
  private async send(): Promise<void> {
    this.busy.set(true); this.message.set(''); const generation = this.generation;
    try {
      await this.command!.attempt();
      if (!this.active || generation !== this.generation) return;
      this.command = null; this.uncertain.set(false); this.selected.set(null); this.form.reset(); this.busy.set(false);
      this.message.set(await this.load() ? 'Service change saved. The latest services are shown.' : 'Service change saved, but the latest services could not be loaded. Refresh before making another change.');
    } catch (error) { if (this.active && generation === this.generation) await this.failure(error, true); }
    finally { if (this.active) this.busy.set(false); }
  }
  private async failure(error: unknown, mutation: boolean): Promise<void> {
    const failure = safeFailure(error, mutation);
    if (failure.kind === 'SESSION_EXPIRED') { this.clear(); await this.router.navigateByUrl('/admin/login').catch(() => false); return; }
    if (failure.status === 403) { await reauthenticate(this.sessions, this.router, '/admin/services'); return; }
    if (this.uncertain() && failure.kind === 'THROTTLED') { this.message.set('Please wait, then retry the same service change. Its original result is still unknown.'); return; }
    this.uncertain.set(failure.kind === 'UNCERTAIN');
    if (this.uncertain()) { this.message.set('The response did not arrive. The change may have succeeded. Retry the same change before starting another.'); return; }
    this.command?.discard(); this.command = null;
    if (failure.kind === 'VERSION_CONFLICT' || failure.status === 428 || (mutation && (failure.status === 404 || failure.kind === 'CONFLICT'))) {
      this.selected.set(null); this.services.set(null); this.form.reset(); this.message.set('The service changed or is unavailable. Refresh services and review before submitting again.');
    } else if (failure.kind === 'THROTTLED') this.message.set('Please wait before trying again.');
    else this.message.set(mutation ? 'The service could not be saved. Check the fields and try again.' : 'Services could not be loaded. Try again.');
  }
  private clear(): void { this.command?.discard(); this.command = null; this.services.set(null); this.selected.set(null); this.form.reset(); this.uncertain.set(false); }
}
