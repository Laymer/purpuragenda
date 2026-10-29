import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { combineLatest, firstValueFrom, timeout } from 'rxjs';
import { AdministratorCorrectionPreview, AdministratorCorrectionReceipt, AdministratorCorrectionTarget, AdministratorCorrectionValues, AdministratorPrivateNote, AdministratorService } from '../shared/generated';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';
import { reauthenticate } from './admin-reauth';

type Kind = 'PROFILE' | 'NOTE' | 'INTAKE';
type Field = keyof AdministratorCorrectionValues;
const FIELDS: Record<Kind, Field[]> = { PROFILE: ['firstName', 'lastName', 'telephone'], NOTE: ['text'], INTAKE: ['firstName', 'lastName', 'telephone', 'birthDate', 'birthTime', 'birthplace', 'message'] };
const LABELS: Record<Field, string> = { firstName: 'First name', lastName: 'Last name', telephone: 'Telephone', birthDate: 'Birth date', birthTime: 'Birth time', birthplace: 'Birthplace', message: 'Customer message', text: 'Private note' };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
@Component({
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule],
  template: `<section aria-labelledby="correction-title" [attr.aria-busy]="busy()">
    <a routerLink="/admin/customers">Back to customers</a><h1 id="correction-title">Review a customer correction</h1>
    <p id="correction-guidance">Non-medical service: do not enter medical or other sensitive information, or unnecessary information about other people. Corrections do not change identity, booking ownership, verified email, service, policy or payment facts. Original intake remains explicitly historical; corrected intake is shown as current.</p>
    <p role="status">{{ message() }}</p>
    @if (kind() === 'NOTE') {
      <h2>Select a private note</h2>
      @for (note of notes(); track note.id) { <article><p>{{ note.text }}</p><button mat-stroked-button type="button" (click)="select(note.id)" [disabled]="busy() || uncertain()">Correct this note</button></article> }
      @if (cursor()) { <button mat-stroked-button type="button" (click)="loadNotes(true)" [disabled]="busy() || uncertain()">Older notes</button> }
    }
    @if (target(); as value) {
      <h2>{{ kind() === 'PROFILE' ? 'Profile' : kind() === 'INTAKE' ? 'Current intake' : 'Private note' }}</h2>
      <form [formGroup]="form" (ngSubmit)="prepare()">
        @for (field of fields(); track field) {
          <label [for]="'correction-' + field">{{ label(field) }}</label>
          @if (field === 'text' || field === 'message') { <textarea [id]="'correction-' + field" [formControlName]="field" autocomplete="off" aria-describedby="correction-guidance" [readOnly]="busy() || uncertain() || !!preview()"></textarea> }
          @else { <input [id]="'correction-' + field" [formControlName]="field" [type]="field === 'birthDate' ? 'date' : field === 'birthTime' ? 'time' : 'text'" step="1" autocomplete="off" [readOnly]="busy() || uncertain() || !!preview()" /> }
        }
        <label for="correction-reason">Reason (10–500 characters; avoid personal details)</label><textarea id="correction-reason" formControlName="reason" autocomplete="off" [readOnly]="busy() || uncertain() || !!preview()"></textarea>
        <button mat-flat-button type="submit" [disabled]="busy() || uncertain() || !!preview()">Prepare correction preview</button>
      </form>
    }
    @if (preview(); as p) {
      <h2>Review before confirming</h2><p>Expires at {{ p.expiresAt }} (UTC). Existing temporary customer exports will be revoked.</p>
      <table><thead><tr><th>Field</th><th>Before</th><th>After</th></tr></thead><tbody>@for (field of fields(); track field) { <tr><th>{{ label(field) }}</th><td>{{ p.before.values[field] }}</td><td>{{ p.after[field] }}</td></tr> }</tbody></table>
      <p>Reason: {{ p.reason }}</p><label><input type="checkbox" [formControl]="reviewed" [attr.disabled]="busy() || uncertain() ? '' : null" />I reviewed this exact correction and confirm it should be applied.</label>
      <button mat-flat-button type="button" (click)="confirm()" [disabled]="!reviewed.value || busy() || uncertain()">Confirm correction</button>
      <button mat-stroked-button type="button" (click)="edit()" [disabled]="busy() || uncertain()">Revise proposal</button>
    }
    @if (uncertain()) { <button mat-stroked-button type="button" (click)="retry()" [disabled]="busy()">Retry the same request</button> }
  </section>`,
  styles: [`section { max-width: 52rem; margin: 1rem auto; padding: 1rem; } form { display: grid; gap: .5rem; } input, textarea { font: inherit; padding: .5rem; } textarea { min-height: 5rem; } table { width: 100%; table-layout: fixed; border-collapse: collapse; } th, td { text-align: left; border: 1px solid; padding: .5rem; white-space: pre-wrap; overflow-wrap: anywhere; } article { border-top: 1px solid; } article p { white-space: pre-wrap; overflow-wrap: anywhere; } button { margin: .5rem; }`],
})
export class CorrectionsComponent {
  private readonly api = inject(AdministratorService); private readonly commands = inject(CommandAccess); private readonly sessions = inject(SessionAccess); private readonly router = inject(Router);
  private active = true; private blocked = false; private generation = 0; private timer: ReturnType<typeof setTimeout> | undefined;
  private command: FrozenCommand<AdministratorCorrectionReceipt> | null = null; private confirming = false; private booking = '';
  readonly customer = signal(''); readonly kind = signal<Kind>('PROFILE'); readonly target = signal<AdministratorCorrectionTarget | null>(null);
  readonly preview = signal<AdministratorCorrectionPreview | null>(null); readonly notes = signal<AdministratorPrivateNote[]>([]); readonly cursor = signal<string | null>(null);
  readonly busy = signal(false); readonly uncertain = signal(false); readonly message = signal(''); readonly reviewed = new FormControl(false, { nonNullable: true });
  readonly form = new FormGroup({ firstName: new FormControl('', { nonNullable: true }), lastName: new FormControl('', { nonNullable: true }), telephone: new FormControl('', { nonNullable: true }), birthDate: new FormControl('', { nonNullable: true }), birthTime: new FormControl('', { nonNullable: true }), birthplace: new FormControl('', { nonNullable: true }), message: new FormControl('', { nonNullable: true }), text: new FormControl('', { nonNullable: true }), reason: new FormControl('', { nonNullable: true }) });
  readonly fields = () => FIELDS[this.kind()]; readonly label = (field: Field) => LABELS[field];
  constructor() {
    const route = inject(ActivatedRoute); const subscription = combineLatest([route.paramMap, route.queryParamMap]).subscribe(([params, query]) => {
      this.clear(); this.blocked = false; this.customer.set(params.get('id') ?? ''); const kind = query.get('kind') ?? 'PROFILE'; this.booking = query.get('booking') ?? '';
      if (!UUID.test(this.customer()) || !['PROFILE', 'NOTE', 'INTAKE'].includes(kind) || kind !== 'PROFILE' && !UUID.test(this.booking)) { this.message.set('Choose a customer and correction from the customer booking list.'); return; }
      this.kind.set(kind as Kind); if (kind === 'NOTE') void this.loadNotes(false); else void this.select(kind === 'PROFILE' ? this.customer() : this.booking);
    });
    const remove = this.sessions.onInvalidated(() => { this.clear(); this.blocked = true; });
    inject(DestroyRef).onDestroy(() => { this.active = false; subscription.unsubscribe(); remove(); this.clear(); });
  }
  private current(g: number): boolean { return this.active && this.generation === g; }
  private available(): boolean { return this.active && !this.blocked && !this.busy() && !this.uncertain(); }
  async loadNotes(older: boolean): Promise<void> {
    if (!this.available() || older && !this.cursor()) return; const g = this.generation; this.busy.set(true);
    try { const page = await firstValueFrom(this.api.getAdministratorNotes(this.booking, 20, older ? this.cursor()! : undefined).pipe(timeout(25000))); if (!this.current(g)) return; this.notes.set(page.entries); this.cursor.set(page.nextCursor ?? null); if (!page.entries.length) this.message.set('No notes on this page.'); }
    catch (error) { if (this.current(g)) await this.failure(error, false); } finally { if (this.current(g)) this.busy.set(false); }
  }
  async select(id: string): Promise<void> {
    if (!this.available()) return; this.edit(); this.target.set(null); this.form.reset(); const g = this.generation; this.busy.set(true);
    try { const target = await firstValueFrom(this.api.getAdministratorCorrectionTarget(this.customer(), this.kind().toLowerCase() as 'profile' | 'note' | 'intake', id).pipe(timeout(25000))); if (!this.current(g)) return; this.target.set(target); this.form.patchValue(target.values); this.message.set('Edit the fields, then prepare a preview.'); }
    catch (error) { if (this.current(g)) await this.failure(error, false); } finally { if (this.current(g)) this.busy.set(false); }
  }
  async prepare(): Promise<void> {
    const target = this.target(); if (!this.available() || !target || this.preview()) return;
    const reason = this.form.controls.reason.value.trim().normalize('NFC'); const values: AdministratorCorrectionValues = {};
    for (const field of this.fields()) values[field] = this.form.controls[field].value.trim().normalize('NFC');
    if ([...reason].length < 10 || [...reason].length > 500 || /[\u0000-\u001f\u007f-\u009f\ud800-\udfff]/u.test(reason) || Object.values(values).some(v => /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\ud800-\udfff]/u.test(v))) { this.message.set('Enter valid correction fields and a reason of 10–500 characters.'); return; }
    this.confirming = false; this.command = this.commands.prepare('POST', '/api/v1/admin/customers/' + this.customer() + '/corrections/' + this.kind().toLowerCase() + '/' + target.targetId, { reason, values }); await this.send();
  }
  async confirm(): Promise<void> {
    const p = this.preview(); if (!this.available() || !p || !this.reviewed.value) return;
    this.confirming = true; this.command = this.commands.prepare('POST', '/api/v1/admin/corrections/' + p.id + '/confirm', { digest: p.digest, confirmed: true }, '"' + p.version + '"'); await this.send();
  }
  async retry(): Promise<void> { if (this.active && !this.blocked && !this.busy() && this.uncertain() && this.command) await this.send(); }
  private async send(): Promise<void> {
    const g = this.generation; this.busy.set(true); this.message.set(''); let committed = false;
    try {
      const result = await this.command!.attempt(); if (!this.current(g)) return; committed = true; this.command = null; this.uncertain.set(false);
      if (this.confirming) { this.edit(); this.target.set(null); this.notes.set([]); this.cursor.set(null); this.form.reset(); this.message.set('Correction applied. Affected temporary exports were revoked. Return to customers to review the updated records.'); return; }
      const p = await firstValueFrom(this.api.getAdministratorCorrection(result.correctionId).pipe(timeout(25000))); if (!this.current(g)) return;
      this.preview.set(p); this.reviewed.reset(); this.timer = setTimeout(() => { this.clear(); this.message.set('This correction preview expired. Return to customers and prepare a new preview.'); }, Math.max(0, Math.min(3600000, Date.parse(p.expiresAt) - Date.now()))); this.message.set('Review every before and after value before confirming.');
    } catch (error) { if (this.current(g)) await this.failure(error, !committed); } finally { if (this.current(g)) this.busy.set(false); }
  }
  edit(): void { if (this.timer !== undefined) clearTimeout(this.timer); this.timer = undefined; this.preview.set(null); this.reviewed.reset(); }
  private async failure(error: unknown, mutation: boolean): Promise<void> {
    const failure = safeFailure(error, mutation);
    if (failure.kind === 'SESSION_EXPIRED') { this.clear(); this.blocked = true; this.sessions.invalidate(); await this.router.navigateByUrl('/admin/login').catch(() => false); return; }
    if (failure.status === 403) { await reauthenticate(this.sessions, this.router, '/admin/customers/' + this.customer() + '/corrections' + (this.kind() === 'PROFILE' ? '' : '?kind=' + this.kind() + '&booking=' + this.booking)); return; }
    if (mutation && (failure.kind === 'UNCERTAIN' || failure.kind === 'THROTTLED')) { this.uncertain.set(true); this.message.set('The result is not yet known. Retry the same request before starting another.'); return; }
    this.command?.discard(); this.command = null; this.uncertain.set(false); this.edit();
    this.message.set(failure.status === 404 ? 'This record or preview is unavailable or changed. Return to customers and prepare a new preview.' : failure.status === 412 ? 'The record changed. Prepare and review a new preview.' : 'The correction could not be loaded or applied. Check the fields and try again.');
  }
  private clear(): void { this.generation++; this.command?.discard(); this.command = null; this.edit(); this.form.reset(); this.target.set(null); this.notes.set([]); this.cursor.set(null); this.busy.set(false); this.uncertain.set(false); this.message.set(''); }
}
