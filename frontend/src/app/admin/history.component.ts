import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { AdministratorAuditEntry, AdministratorPrivateNote, AdministratorService } from '../shared/generated';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';
import { brusselsDisplay } from './admin-time';
import { reauthenticate } from './admin-reauth';

@Component({
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule],
  template: `
    <section aria-labelledby="history-title" [attr.aria-busy]="busy()">
      @if (id()) { <p><a [routerLink]="'/admin/bookings/' + id()">Back to booking</a></p> }
      <h1 id="history-title">{{ id() ? 'Private notes and audit' : 'Administrator audit' }}</h1>
      <p role="alert">{{ message() }}</p>
      <button mat-stroked-button type="button" (click)="load()" [disabled]="busy() || uncertain()">Show newest entries</button>
      @if (id() && loaded()) {
        <h2>Private notes</h2><p>Notes are visible only to the administrator. Adding a note does not notify the customer.</p>
        <form [formGroup]="form" (ngSubmit)="submit()">
          <p id="note-privacy-guidance">This is a non-medical service. Do not enter medical information or other sensitive personal information. Include only details needed for this consultation. Do not record unnecessary information about other people. Customer-related notes may need to be included in a customer data export after review.</p>
          <label for="private-note">New private note (up to 4000 characters)</label>
          <textarea aria-describedby="note-privacy-guidance" id="private-note" formControlName="text" maxlength="8000" autocomplete="off" [readOnly]="busy() || uncertain()"></textarea>
          <button mat-flat-button type="submit" [disabled]="busy() || uncertain()">Add private note</button>
        </form>
        @if (uncertain()) { <button mat-stroked-button type="button" (click)="retry()" [disabled]="busy()">Retry the same note</button> }
        @for (note of notes(); track note.id) { <article><p>{{ display(note.createdAt) }}</p><p class="private-text">{{ note.text }}</p></article> } @empty { <p>No notes on this page.</p> }
        @if (noteCursor()) { <button mat-stroked-button type="button" (click)="older('notes')" [disabled]="busy() || uncertain()">Older notes</button> }
      }
      @if (loaded()) {
        <h2>Audit history</h2>
        @for (event of events(); track event.id) {
          <article><p>{{ display(event.occurredAt) }} — {{ event.type.replaceAll('_', ' ') }} — {{ event.actorKind }}</p>
            @if (event.reason) { <p class="private-text">{{ event.reason }}</p> }
            @if (!id() && event.bookingId) { <a [routerLink]="'/admin/bookings/' + event.bookingId + '/history'">Open booking history</a> }
          </article>
        } @empty { <p>No audit events on this page.</p> }
        @if (auditCursor()) { <button mat-stroked-button type="button" (click)="older('audit')" [disabled]="busy() || uncertain()">Older audit events</button> }
      }
    </section>`,
  styles: [`section { max-width: 48rem; margin: 1rem auto; padding: 1rem; } form { display: grid; gap: .5rem; margin: 1rem 0; } textarea { font: inherit; min-height: 8rem; padding: .5rem; } article { border-top: 1px solid #ddd; padding: .5rem 0; } .private-text { white-space: pre-wrap; overflow-wrap: anywhere; }`],
})
export class HistoryComponent {
  private readonly api = inject(AdministratorService);
  private readonly commands = inject(CommandAccess);
  private readonly sessions = inject(SessionAccess);
  private readonly router = inject(Router);
  private active = true;
  private blocked = false;
  private generation = 0;
  private command: FrozenCommand<unknown> | null = null;
  readonly id = signal<string | null>(null);
  readonly notes = signal<AdministratorPrivateNote[]>([]);
  readonly events = signal<AdministratorAuditEntry[]>([]);
  readonly noteCursor = signal<string | null>(null);
  readonly auditCursor = signal<string | null>(null);
  readonly busy = signal(false);
  readonly loaded = signal(false);
  readonly uncertain = signal(false);
  readonly message = signal('');
  readonly form = new FormGroup({ text: new FormControl('', { nonNullable: true }) });
  readonly display = brusselsDisplay;
  constructor() {
    const route = inject(ActivatedRoute).paramMap.subscribe(params => { this.generation++; this.clear(); this.blocked = false; this.id.set(params.get('id')); void this.load(); });
    const remove = this.sessions.onInvalidated(() => { this.generation++; this.blocked = true; this.clear(); });
    inject(DestroyRef).onDestroy(() => { this.active = false; this.generation++; route.unsubscribe(); remove(); this.clear(); });
  }
  private current(generation: number): boolean { return this.active && generation === this.generation; }
  async load(): Promise<boolean> {
    if (!this.active || this.blocked || this.busy() || this.uncertain()) return false;
    const id = this.id();
    if (id !== null && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)) { this.message.set('That booking could not be found.'); return false; }
    this.busy.set(true); this.loaded.set(false); this.notes.set([]); this.events.set([]); this.noteCursor.set(null); this.auditCursor.set(null); const generation = this.generation;
    try {
      const [notes, audit] = await Promise.all([id === null ? Promise.resolve(null) : firstValueFrom(this.api.getAdministratorNotes(id, 20).pipe(timeout(25000))), firstValueFrom(this.api.getAdministratorAudit(id ?? undefined, 20).pipe(timeout(25000)))]);
      if (!this.current(generation)) return false;
      this.notes.set(notes?.entries ?? []); this.noteCursor.set(notes?.nextCursor ?? null); this.events.set(audit.entries); this.auditCursor.set(audit.nextCursor ?? null); this.loaded.set(true); this.message.set(''); return true;
    } catch (error) { if (this.current(generation)) await this.failure(error, false); return false; }
    finally { if (this.current(generation)) this.busy.set(false); }
  }
  async older(kind: 'notes' | 'audit'): Promise<void> {
    const cursor = kind === 'notes' ? this.noteCursor() : this.auditCursor(), id = this.id();
    if (this.blocked || this.busy() || this.uncertain() || !cursor || kind === 'notes' && id === null) return;
    this.busy.set(true); const generation = this.generation;
    try {
      if (kind === 'notes') { const page = await firstValueFrom(this.api.getAdministratorNotes(id!, 20, cursor).pipe(timeout(25000))); if (!this.current(generation)) return; this.notes.set(page.entries); this.noteCursor.set(page.nextCursor ?? null); }
      else { const page = await firstValueFrom(this.api.getAdministratorAudit(id ?? undefined, 20, cursor).pipe(timeout(25000))); if (!this.current(generation)) return; this.events.set(page.entries); this.auditCursor.set(page.nextCursor ?? null); }
      this.message.set('');
    } catch (error) { if (this.current(generation)) await this.failure(error, false); }
    finally { if (this.current(generation)) this.busy.set(false); }
  }
  async submit(): Promise<void> {
    if (this.blocked || this.busy() || this.uncertain() || !this.loaded() || this.id() === null) return;
    const text = this.form.controls.text.value.trim().normalize('NFC');
    if (!text || [...text].length > 4000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\ud800-\udfff]/u.test(text)) { this.message.set('Enter a private note of 1–4000 characters without control characters.'); return; }
    try { this.command?.discard(); this.command = this.commands.prepare('POST', '/api/v1/admin/bookings/' + this.id() + '/notes', { text }); }
    catch { this.message.set('This note could not be submitted from this page.'); return; }
    await this.send();
  }
  async retry(): Promise<void> { if (!this.blocked && !this.busy() && this.uncertain() && this.command !== null) await this.send(); }
  private async send(): Promise<void> {
    this.busy.set(true); this.message.set(''); const generation = this.generation;
    try {
      await this.command!.attempt(); if (!this.current(generation)) return;
      this.command = null; this.uncertain.set(false); this.form.reset(); this.busy.set(false);
      const refreshed = await this.load(); if (this.current(generation)) this.message.set(refreshed ? 'Private note added. The newest entries are shown.' : 'Private note added, but history could not be refreshed. Refresh before adding another note.');
    } catch (error) { if (this.current(generation)) await this.failure(error, true); }
    finally { if (this.current(generation)) this.busy.set(false); }
  }
  private async failure(error: unknown, mutation: boolean): Promise<void> {
    const failure = safeFailure(error, mutation);
    if (failure.kind === 'SESSION_EXPIRED') { this.generation++; this.blocked = true; this.clear(); await this.router.navigateByUrl('/admin/login').catch(() => false); return; }
    if (failure.status === 403) { await reauthenticate(this.sessions, this.router, this.id() === null ? '/admin/audit' : '/admin/bookings/' + this.id() + '/history'); return; }
    if (mutation && (failure.kind === 'UNCERTAIN' || failure.kind === 'THROTTLED')) { this.uncertain.set(true); this.message.set(failure.kind === 'THROTTLED' ? 'Please wait, then retry the same note. Keep its original request.' : 'The response did not arrive. The note may exist. Retry the same note before adding another.'); return; }
    this.command?.discard(); this.command = null; this.uncertain.set(false);
    this.message.set(failure.status === 404 ? 'That booking could not be found.' : mutation ? 'The note could not be added. Review it and refresh the history before trying again.' : 'History could not be loaded. Try again.');
  }
  private clear(): void { this.command?.discard(); this.command = null; this.form.reset(); this.notes.set([]); this.events.set([]); this.noteCursor.set(null); this.auditCursor.set(null); this.loaded.set(false); this.busy.set(false); this.uncertain.set(false); this.message.set(''); }
}
