import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { AdministratorExportPreview, AdministratorExportReceipt, AdministratorService } from '../shared/generated';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';
import { reauthenticate } from './admin-reauth';

@Component({
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule],
  template: `<section aria-labelledby="privacy-title" [attr.aria-busy]="busy()">
    <a routerLink="/admin/customers">Back to customers</a><h1 id="privacy-title">Customer data export</h1>
    <p>Prepare a private preview, review all customer-related notes for information about other people, then explicitly release the download. Authentication secrets are excluded.</p>
    <p>Temporary exports expire after one hour and are purged automatically. A downloaded copy remains under your control; keep it secure and remove it when no longer needed.</p>
    <p role="status">{{ message() }}</p>
    <label for="export-reason">Reason (10–500 characters; do not include sensitive details)</label>
    <textarea id="export-reason" [formControl]="reason" maxlength="1000" autocomplete="off" [readOnly]="busy() || uncertain()"></textarea>
    <button mat-flat-button type="button" (click)="prepare()" [disabled]="busy() || uncertain()">Prepare export preview</button>
    @if (uncertain()) { <button mat-stroked-button type="button" (click)="retry()" [disabled]="busy()">Retry the same request</button> }
    @if (preview(); as data) {
      <h2>Private preview</h2><p>Expires at {{ data.expiresAt }} (UTC).</p>
      <pre tabindex="0" aria-label="Customer export preview">{{ data.document }}</pre>
      @if (!data.released) {
        <label><input type="checkbox" [formControl]="reviewed" [attr.disabled]="busy() || uncertain() ? '' : null" />I reviewed all notes and other content for third-party information and confirm this export may be released.</label>
        <button mat-flat-button type="button" (click)="release()" [disabled]="!reviewed.value || busy() || uncertain()">Confirm and release export</button>
      } @else {
        <button mat-stroked-button type="button" (click)="download()" [disabled]="busy() || uncertain()">Prepare secure download</button>
      }
    }
    @if (url(); as file) { <p><a [href]="file" download="customer-export.json">Save reviewed customer export</a></p> }
  </section>`,
  styles: [`section { max-width: 48rem; margin: 1rem auto; padding: 1rem; } textarea { display: block; width: 100%; min-height: 5rem; font: inherit; } pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 24rem; overflow-y: auto; border: 1px solid; padding: .8rem; } button { margin: .5rem; }`],
})
export class PrivacyComponent {
  private readonly api = inject(AdministratorService);
  private readonly commands = inject(CommandAccess);
  private readonly sessions = inject(SessionAccess);
  private readonly router = inject(Router);
  private active = true;
  private blocked = false;
  private generation = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private command: FrozenCommand<AdministratorExportReceipt> | null = null;
  readonly customer = signal('');
  readonly reason = new FormControl('', { nonNullable: true });
  readonly reviewed = new FormControl(false, { nonNullable: true });
  readonly preview = signal<AdministratorExportPreview | null>(null);
  readonly url = signal<string | null>(null);
  readonly busy = signal(false);
  readonly uncertain = signal(false);
  readonly message = signal('');
  constructor() {
    const route = inject(ActivatedRoute).paramMap.subscribe(params => { this.clear(); this.blocked = false; this.customer.set(params.get('id') ?? ''); });
    const remove = this.sessions.onInvalidated(() => { this.clear(); this.blocked = true; });
    inject(DestroyRef).onDestroy(() => { this.active = false; route.unsubscribe(); remove(); this.clear(); });
  }
  private current(generation: number): boolean { return this.active && this.generation === generation; }
  private available(): boolean { return this.active && !this.blocked && !this.busy() && !this.uncertain(); }
  async prepare(): Promise<void> {
    if (!this.available()) return;
    const reason = this.reason.value.trim().normalize('NFC');
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(this.customer()) || [...reason].length < 10 || [...reason].length > 500 || /[\u0000-\u001f\u007f-\u009f\ud800-\udfff]/u.test(reason)) { this.message.set('Enter a reason of 10–500 characters for a valid customer.'); return; }
    this.clearDocument(); this.command?.discard();
    this.command = this.commands.prepare('POST', '/api/v1/admin/customers/' + this.customer() + '/exports', { reason });
    await this.send();
  }
  async release(): Promise<void> {
    const doc = this.preview(); if (!this.available() || !doc || doc.released || !this.reviewed.value) return;
    this.command?.discard(); this.command = this.commands.prepare('POST', '/api/v1/admin/exports/' + doc.id + '/release', { digest: doc.digest, reviewedThirdPartyInformation: true });
    await this.send();
  }
  async retry(): Promise<void> { if (this.active && !this.blocked && !this.busy() && this.uncertain() && this.command) await this.send(); }
  private async send(): Promise<void> {
    const generation = this.generation; this.busy.set(true); this.message.set(''); let committed = false;
    try {
      const result = await this.command!.attempt(); if (!this.current(generation)) return;
      committed = true; this.command = null; this.uncertain.set(false); this.reason.reset();
      const doc = await firstValueFrom(this.api.getAdministratorExport(result.exportId).pipe(timeout(25000))); if (!this.current(generation)) return;
      this.clearDocument(); this.preview.set(doc); this.message.set(doc.released ? 'Export released after review.' : 'Preview ready. Review it before release.');
      const remaining = Date.parse(doc.expiresAt) - Date.now();
      this.timer = setTimeout(() => { this.clear(); this.message.set('This temporary export has expired. Prepare a new preview if needed.'); }, Math.max(0, Math.min(3600000, remaining)));
    } catch (error) { if (this.current(generation)) { if (committed) this.clearDocument(); await this.failure(error, !committed); } }
    finally { if (this.current(generation)) this.busy.set(false); }
  }
  async download(): Promise<void> {
    const doc = this.preview(); if (!this.available() || !doc?.released) return;
    const generation = this.generation; this.busy.set(true);
    try {
      const blob = await firstValueFrom(this.api.downloadAdministratorExport(doc.id).pipe(timeout(25000))); if (!this.current(generation)) return;
      this.revoke(); this.url.set(URL.createObjectURL(blob)); this.message.set('Use Save reviewed customer export to save your copy.');
    } catch (error) { if (this.current(generation)) await this.failure(error, false); }
    finally { if (this.current(generation)) this.busy.set(false); }
  }
  private async failure(error: unknown, mutation: boolean): Promise<void> {
    const failure = safeFailure(error, mutation);
    if (failure.kind === 'SESSION_EXPIRED') { this.clear(); this.blocked = true; this.sessions.invalidate(); await this.router.navigateByUrl('/admin/login').catch(() => false); return; }
    if (failure.status === 403) { await reauthenticate(this.sessions, this.router, '/admin/customers/' + this.customer() + '/privacy'); return; }
    if (mutation && (failure.kind === 'UNCERTAIN' || failure.kind === 'THROTTLED')) { this.uncertain.set(true); this.message.set('The result is not yet known. Retry the same request before starting another.'); return; }
    this.command?.discard(); this.command = null; this.uncertain.set(false);
    if (failure.status === 404 || failure.status === 412) this.clearDocument();
    this.message.set(failure.status === 404 ? 'This export or customer is unavailable, or the export has expired.' : failure.status === 412 ? 'The preview changed. Prepare and review a new export.' : 'The export could not be loaded or changed. Prepare a new preview if needed.');
  }
  private revoke(): void { const url = this.url(); if (url) URL.revokeObjectURL(url); this.url.set(null); }
  private clearDocument(): void { if (this.timer !== undefined) clearTimeout(this.timer); this.timer = undefined; this.preview.set(null); this.reviewed.reset(); this.revoke(); }
  private clear(): void { this.generation++; this.command?.discard(); this.command = null; this.clearDocument(); this.reason.reset(); this.busy.set(false); this.uncertain.set(false); this.message.set(''); }
}
