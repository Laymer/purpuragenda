import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { AdministratorService, AdministratorPrivacyOverview, AdministratorPrivacyItem, AdministratorErasurePreview, AdministratorErasureReceipt } from '../shared/generated';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';
import { reauthenticate } from './admin-reauth';

@Component({
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule],
  template: `<section aria-labelledby="erasure-title" [attr.aria-busy]="busy()">
    <a routerLink="/admin/customers">Back to customers</a><h1 id="erasure-title">Review retained customer records</h1>
    <p>Intake and private notes prompt review after 12 months; identity, history and ordinary audit after 24 months. Dates never delete records automatically. Financial retention requires accounting confirmation. Service, policy and financial facts remain.</p>
    <p>Non-medical service: do not enter medical or sensitive information, or unnecessary information about other people.</p>
    <p role="status">{{ message() }}</p>
    @if (overview(); as o) {
      <label for="erasure-reason">Reason (10–500 characters; avoid personal details)</label><textarea id="erasure-reason" [formControl]="reason" autocomplete="off" [readOnly]="busy() || uncertain() || !!preview()"></textarea>
      <label for="hold-through">Hold through (inclusive Brussels date, optional)</label><input id="hold-through" type="date" [formControl]="through" [readOnly]="busy() || uncertain() || !!preview()" />
      <label><input type="checkbox" [formControl]="holdReviewed" />I reviewed the reason and exact record before creating or releasing a hold.</label>
      <ul>@for (item of o.items; track item.scope.kind + item.scope.id) {
        <li><label><input type="checkbox" [checked]="selected(item)" (change)="toggle(item)" [disabled]="busy() || uncertain() || !!preview() || item.holds.length > 0 || item.scope.kind === 'FINANCIAL'" />{{ item.scope.kind }} · {{ item.scope.id }}</label>
          <p>{{ item.status }} · review {{ item.reviewOn ?? 'requires confirmation' }}. {{ item.effect }}</p>
          <button mat-stroked-button type="button" (click)="hold(item)" [disabled]="busy() || uncertain() || !!preview() || !holdReviewed.value">Hold this record</button>
          @for (id of item.holds; track id) { <button mat-stroked-button type="button" (click)="release(id)" [disabled]="busy() || uncertain() || !!preview() || !holdReviewed.value">Release hold {{ id }}</button> }
        </li>
      }</ul>
      <button mat-flat-button type="button" (click)="prepare()" [disabled]="busy() || uncertain() || !!preview() || !selection().length">Prepare erasure preview</button>
    }
    @if (preview(); as p) {
      <h2>Review this exact erasure</h2><p>Expires {{ p.expiresAt }} (UTC). Temporary customer exports will be revoked. The selected personal copies will be removed; this cannot be undone by restoring a database backup.</p>
      <h3>Selected</h3><ul>@for (item of p.selected; track item.scope.kind + item.scope.id) { <li>{{ item.scope.kind }} · {{ item.scope.id }}: {{ item.effect }}</li> }</ul>
      <h3>Retained</h3><ul>@for (item of p.retained; track item.scope.kind + item.scope.id) { <li>{{ item.scope.kind }} · {{ item.scope.id }}: {{ item.effect }}</li> }</ul>
      <p>Reason: {{ p.reason }}</p><label><input type="checkbox" [formControl]="reviewed" />I reviewed every selected and retained record and confirm this exact erasure.</label>
      <button mat-flat-button type="button" (click)="confirm()" [disabled]="busy() || uncertain() || !reviewed.value">Confirm reviewed erasure</button>
      <button mat-stroked-button type="button" (click)="edit()" [disabled]="busy() || uncertain()">Revise selection</button>
    }
    @if (uncertain()) { <button mat-stroked-button type="button" (click)="retry()" [disabled]="busy()">Retry the same request</button> }
  </section>`,
  styles: [`section { max-width: 56rem; margin: 1rem auto; padding: 1rem; } textarea { display: block; width: 100%; min-height: 5rem; } li { margin: 1rem 0; overflow-wrap: anywhere; } button { margin: .5rem; }`],
})
export class ErasureComponent {
  private readonly api = inject(AdministratorService); private readonly commands = inject(CommandAccess); private readonly sessions = inject(SessionAccess); private readonly router = inject(Router);
  private customer = ''; private active = true; private blocked = false; private generation = 0; private timer: ReturnType<typeof setTimeout> | undefined;
  private command: FrozenCommand<AdministratorErasureReceipt> | null = null; private action: 'prepare' | 'confirm' | 'hold' = 'prepare';
  readonly overview = signal<AdministratorPrivacyOverview | null>(null); readonly preview = signal<AdministratorErasurePreview | null>(null); readonly selection = signal<AdministratorPrivacyItem[]>([]);
  readonly busy = signal(false); readonly uncertain = signal(false); readonly message = signal('');
  readonly reason = new FormControl('', { nonNullable: true }); readonly through = new FormControl('', { nonNullable: true });
  readonly reviewed = new FormControl(false, { nonNullable: true }); readonly holdReviewed = new FormControl(false, { nonNullable: true });
  constructor() {
    const subscription = inject(ActivatedRoute).paramMap.subscribe(params => { this.clear(); this.blocked = false; this.customer = params.get('id') ?? ''; if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(this.customer)) void this.load(); else this.message.set('Choose a customer from the customer search.'); });
    const remove = this.sessions.onInvalidated(() => { this.clear(); this.blocked = true; });
    inject(DestroyRef).onDestroy(() => { this.active = false; subscription.unsubscribe(); remove(); this.clear(); });
  }
  private current(g: number): boolean { return this.active && this.generation === g; }
  private available(): boolean { return this.active && !this.blocked && !this.busy() && !this.uncertain(); }
  selected(item: AdministratorPrivacyItem): boolean { return this.selection().some(v => v.scope.kind === item.scope.kind && v.scope.id === item.scope.id); }
  toggle(item: AdministratorPrivacyItem): void { if (!this.available() || this.preview() || item.holds.length || item.scope.kind === 'FINANCIAL') return; this.selection.update(v => this.selected(item) ? v.filter(i => i !== item) : [...v, item]); }
  private validReason(): string | null { const value = this.reason.value.trim().normalize('NFC'); if ([...value].length < 10 || [...value].length > 500 || /[\u0000-\u001f\u007f-\u009f\ud800-\udfff]/u.test(value)) { this.message.set('Enter a reason of 10–500 characters without sensitive information.'); return null; } return value; }
  async load(): Promise<void> {
    if (!this.available()) return; const g = this.generation; this.busy.set(true);
    try { const o = await firstValueFrom(this.api.getAdministratorPrivacyReview(this.customer).pipe(timeout(25000))); if (this.current(g)) this.overview.set(o); }
    catch (error) { if (this.current(g)) await this.failure(error, false); } finally { if (this.current(g)) this.busy.set(false); }
  }
  async prepare(): Promise<void> { if (!this.available() || this.preview() || !this.selection().length) return; const reason = this.validReason(); if (!reason) return; this.action = 'prepare'; this.command = this.commands.prepare('POST', '/api/v1/admin/customers/' + this.customer + '/erasures', { scopes: this.selection().map(v => v.scope), reason }); await this.send(); }
  async confirm(): Promise<void> { const p = this.preview(); if (!this.available() || !p || !this.reviewed.value) return; this.action = 'confirm'; this.command = this.commands.prepare('POST', '/api/v1/admin/erasures/' + p.id + '/confirm', { digest: p.digest, confirmed: true }, '"' + p.version + '"'); await this.send(); }
  async hold(item: AdministratorPrivacyItem): Promise<void> { if (!this.available() || this.preview() || !this.holdReviewed.value) return; const reason = this.validReason(); if (!reason) return; this.action = 'hold'; this.command = this.commands.prepare('POST', '/api/v1/admin/customers/' + this.customer + '/privacy-holds', { kind: item.scope.kind, targetId: item.scope.id, reason, through: this.through.value || null, confirmed: true }); await this.send(); }
  async release(id: string): Promise<void> { if (!this.available() || this.preview() || !this.holdReviewed.value) return; const reason = this.validReason(); if (!reason) return; this.action = 'hold'; this.command = this.commands.prepare('POST', '/api/v1/admin/privacy-holds/' + id + '/release', { reason, confirmed: true }); await this.send(); }
  async retry(): Promise<void> { if (this.active && !this.blocked && !this.busy() && this.uncertain() && this.command) await this.send(); }
  private async send(): Promise<void> {
    const g = this.generation; this.busy.set(true); let committed = false;
    try {
      const result = await this.command!.attempt(); if (!this.current(g)) return; committed = true; this.command = null; this.uncertain.set(false);
      if (this.action === 'prepare') {
        const p = await firstValueFrom(this.api.getAdministratorErasure(result.erasureId).pipe(timeout(25000))); if (!this.current(g)) return;
        this.preview.set(p); this.reviewed.reset(); this.timer = setTimeout(() => { this.clear(); this.message.set('Preview expired. Return to customers and prepare a new review.'); }, Math.max(0, Math.min(3600000, Date.parse(p.expiresAt) - Date.now()))); this.message.set('Review all selected and retained records.');
      } else { this.edit(); this.selection.set([]); this.overview.set(null); this.reason.reset(); this.holdReviewed.reset(); this.message.set(this.action === 'confirm' ? 'Reviewed erasure applied. Temporary exports revoked.' : 'Record hold updated. Return to customers for a fresh review.'); }
    } catch (error) { if (this.current(g)) await this.failure(error, !committed); } finally { if (this.current(g)) this.busy.set(false); }
  }
  private async failure(error: unknown, mutation: boolean): Promise<void> {
    const failure = safeFailure(error, mutation);
    if (failure.kind === 'SESSION_EXPIRED') { this.clear(); this.blocked = true; this.sessions.invalidate(); await this.router.navigateByUrl('/admin/login').catch(() => false); return; }
    if (failure.status === 403) { await reauthenticate(this.sessions, this.router, '/admin/customers/' + this.customer + '/erasure'); return; }
    if (mutation && (failure.kind === 'UNCERTAIN' || failure.kind === 'THROTTLED')) { this.uncertain.set(true); this.message.set('The result is not yet known. Retry the same request.'); return; }
    this.command?.discard(); this.command = null; this.uncertain.set(false); this.edit(); this.message.set('The record or review is unavailable or changed. Return to customers for a fresh review.');
  }
  edit(): void { if (this.timer !== undefined) clearTimeout(this.timer); this.timer = undefined; this.preview.set(null); this.reviewed.reset(); }
  private clear(): void { this.generation++; this.command?.discard(); this.command = null; this.edit(); this.overview.set(null); this.selection.set([]); this.reason.reset(); this.through.reset(); this.holdReviewed.reset(); this.busy.set(false); this.uncertain.set(false); this.message.set(''); }
}
