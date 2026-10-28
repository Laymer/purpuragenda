import { Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom, timeout } from 'rxjs';
import { AccountProfile, AccountService } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { CommandAccess, FrozenCommand } from '../shared/access/command-access';
import { AccessFailure, safeFailure } from '../shared/access/access-failure';
import { passwordPolicy } from './password-policy';

@Component({
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule],
  template: `
    <section aria-labelledby="settings-title" [attr.aria-busy]="busy()">
      <h1 id="settings-title">Account settings</h1>
      <a mat-stroked-button routerLink="/account">Back to your bookings</a>
      <button mat-stroked-button type="button" (click)="load()" [disabled]="busy() || uncertain() || lost()">Reload profile</button>
      <p id="settings-message" role="alert" tabindex="-1">{{ message() }}</p>
      @if (profile(); as account) {
        <p>Login email: {{ account.loginEmail }}. This login identity cannot be changed here.</p>
        <h2>Optional profile</h2><p>These fields are separate from your booking details. Updating them does not change existing bookings or their delivery email. Leave a field empty to clear it.</p>
        <form [formGroup]="form" (ngSubmit)="saveProfile()"><fieldset [disabled]="busy() || uncertain()">
          <legend>Profile details</legend>
          <label for="profile-first">First name (optional)</label><input id="profile-first" autocomplete="given-name" formControlName="firstName">
          <label for="profile-last">Last name (optional)</label><input id="profile-last" autocomplete="family-name" formControlName="lastName">
          <label for="profile-phone">Telephone (optional, international format)</label><input id="profile-phone" type="tel" autocomplete="tel" formControlName="telephone">
          <button mat-flat-button type="submit">Save profile</button>
        </fieldset></form>
        @if (uncertain()) { <button mat-stroked-button type="button" (click)="retry()" [disabled]="busy()">Retry the same profile change</button> }
        <h2>Change password</h2><p>Changing your password signs out every account session. You will need to sign in again.</p>
        <form [formGroup]="passwords" (ngSubmit)="changePassword()"><fieldset [disabled]="busy() || uncertain() || cooling()">
          <legend>Password credentials</legend>
          <label for="current-password">Current password</label><input id="current-password" type="password" autocomplete="current-password" formControlName="current" [attr.aria-invalid]="passwordInvalid('current')" aria-describedby="settings-message">
          <label for="replacement-password">New password (15–128 characters)</label><input id="replacement-password" type="password" autocomplete="new-password" formControlName="replacement" [attr.aria-invalid]="passwordInvalid('replacement')" aria-describedby="settings-message">
          <label for="repeat-password">Repeat new password</label><input id="repeat-password" type="password" autocomplete="new-password" formControlName="confirm" [attr.aria-invalid]="passwordInvalid('confirm')" aria-describedby="settings-message">
          <button mat-flat-button type="submit">Change password and sign out</button>
        </fieldset></form>
      }
      @if (lost()) { <a mat-flat-button routerLink="/account/login">Sign in again</a> }
    </section>`,
  styles: [`section { max-width: 42rem; margin: 1rem auto; padding: 1rem; } fieldset { display: grid; gap: .65rem; margin: 1rem 0; } input { font: inherit; min-height: 2.6rem; padding: .4rem; border: 1px solid #756779; border-radius: .3rem; } a, button { margin: .5rem .75rem .5rem 0; }`],
})
export class AccountSettingsComponent {
  private readonly api = inject(AccountService);
  private readonly sessions = inject(SessionAccess);
  private readonly commands = inject(CommandAccess);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private active = true;
  private generation = 0;
  private etag = '';
  private command: FrozenCommand<unknown> | null = null;
  private timer?: ReturnType<typeof setTimeout>;
  readonly profile = signal<AccountProfile | null>(null);
  readonly busy = signal(false);
  readonly uncertain = signal(false);
  readonly cooling = signal(false);
  readonly lost = signal(false);
  readonly message = signal('');
  readonly form = new FormGroup({ firstName: new FormControl('', { nonNullable: true }), lastName: new FormControl('', { nonNullable: true }), telephone: new FormControl('', { nonNullable: true }) });
  readonly passwords = new FormGroup({ current: new FormControl('', { nonNullable: true, validators: Validators.required }), replacement: new FormControl('', { nonNullable: true, validators: passwordPolicy }), confirm: new FormControl('', { nonNullable: true, validators: Validators.required }) });
  constructor() {
    const remove = this.sessions.onInvalidated(() => { this.clear(); this.lost.set(true); this.message.set('Your session expired or changed. Sign in again to view account settings.'); });
    inject(DestroyRef).onDestroy(() => { this.active = false; remove(); this.clear(); });
    void this.load();
  }
  passwordInvalid(name: 'current' | 'replacement' | 'confirm'): boolean { const field = this.passwords.controls[name]; return field.touched && (field.invalid || (name === 'confirm' && field.value !== this.passwords.controls.replacement.value)); }
  private current(generation: number): boolean { return this.active && generation === this.generation; }
  async load(): Promise<boolean> {
    if (this.busy() || this.uncertain() || this.lost()) return false;
    const generation = this.generation; this.busy.set(true);
    try {
      const response = await firstValueFrom(this.api.getAccountProfile('response').pipe(timeout(25000)));
      if (!this.current(generation)) return false;
      if (response.body === null) throw new AccessFailure('UNAVAILABLE');
      const value = response.body, tag = response.headers.get('ETag'); this.etag = tag !== null && /^"[1-9]\d{0,18}"$/.test(tag) ? tag : '"' + value.version + '"';
      this.profile.set(value); this.form.setValue({ firstName: value.firstName, lastName: value.lastName, telephone: value.telephone }); return true;
    } catch (error) {
      if (this.current(generation)) {
        const failure = safeFailure(error, false);
        if (failure.kind === 'SESSION_EXPIRED' || failure.status === 403) this.sessions.invalidate();
        else { this.profile.set(null); this.message.set('Your profile could not be loaded. Try again.'); this.focus(); }
      }
      return false;
    } finally { if (this.current(generation)) this.busy.set(false); }
  }
  async saveProfile(): Promise<void> {
    if (this.busy() || this.uncertain() || this.lost() || this.profile() === null) return;
    const raw = this.form.getRawValue(), fields = { firstName: raw.firstName.trim().normalize('NFC'), lastName: raw.lastName.trim().normalize('NFC'), telephone: raw.telephone.trim() };
    if ([fields.firstName, fields.lastName].some(value => [...value].length > 100 || /[\u0000-\u001f\u007f-\u009f]/.test(value)) || (fields.telephone !== '' && (fields.telephone.length > 40 || !/^\+[0-9 () .-]+$/.test(fields.telephone) || !/^\+[1-9][0-9]{6,14}$/.test(fields.telephone.replace(/[ () .-]/g, ''))))) {
      this.message.set('Use names of at most 100 characters and an international telephone number, or leave fields empty.'); this.focus(); return;
    }
    try { this.command = this.commands.prepare('POST', '/api/v1/account/profile', fields, this.etag); }
    catch { this.message.set('The profile change could not be prepared. Reload before trying again.'); return; }
    await this.sendProfile();
  }
  async retry(): Promise<void> { if (!this.busy() && this.uncertain() && this.command !== null) await this.sendProfile(); }
  private async sendProfile(): Promise<void> {
    const generation = this.generation; this.busy.set(true); this.message.set('');
    try {
      await this.command!.attempt(); if (!this.current(generation)) return;
      this.command = null; this.uncertain.set(false); this.busy.set(false);
      const loaded = await this.load(); if (this.current(generation)) this.message.set(loaded ? 'Your optional profile was saved.' : 'Your profile was saved, but the latest details could not be loaded. Reload before editing again.');
    } catch (error) {
      if (!this.current(generation)) return;
      const failure = safeFailure(error, true);
      if (failure.kind === 'SESSION_EXPIRED' || failure.status === 403) { this.sessions.invalidate(); return; }
      if (failure.kind === 'UNCERTAIN' || failure.kind === 'THROTTLED') { this.uncertain.set(true); this.message.set(failure.kind === 'THROTTLED' ? 'Please wait, then retry the same profile change.' : 'The profile change may have succeeded. Retry the same change before starting another.'); return; }
      this.command?.discard(); this.command = null; this.uncertain.set(false);
      if (failure.kind === 'VERSION_CONFLICT' || failure.status === 428) {
        this.profile.set(null); this.form.reset(); this.busy.set(false); const loaded = await this.load();
        if (this.current(generation)) this.message.set(loaded ? 'Your profile changed. Review the latest values before editing again.' : 'Your profile changed. Reload its latest values before editing again.');
      } else this.message.set('The profile could not be saved. Check the fields and try again.');
      if (this.current(generation)) this.focus();
    } finally { if (this.current(generation)) this.busy.set(false); }
  }
  async changePassword(): Promise<void> {
    const account = this.profile(); if (this.busy() || this.uncertain() || this.cooling() || this.lost() || account === null) return;
    this.passwords.markAllAsTouched();
    if (this.passwords.invalid || this.passwords.controls.confirm.value !== this.passwords.controls.replacement.value) { this.message.set('Enter your current password and repeat a new password of 15–128 characters.'); this.focus(); return; }
    const value = this.passwords.getRawValue(), request = { email: account.loginEmail, currentPassword: value.current, newPassword: value.replacement }, generation = this.generation;
    this.passwords.reset(); this.busy.set(true); this.message.set('');
    try {
      await this.sessions.perform(csrf => this.api.changeAccountPassword(csrf, request));
      if (this.current(generation)) { this.sessions.invalidate(); this.message.set('Your password was changed and all account sessions were signed out. Sign in again.'); }
    } catch (error) {
      if (!this.current(generation)) return;
      const failure = safeFailure(error, true);
      if (failure.kind === 'UNCERTAIN') { this.sessions.invalidate(); this.message.set('The password change could not be confirmed. Private browser state was cleared. Try signing in with the new password before trying the old one.'); }
      else if (failure.kind === 'SESSION_EXPIRED' || failure.status === 403) this.sessions.invalidate();
      else if (failure.kind === 'THROTTLED') { this.cool(failure.retryAfterSeconds ?? 60); this.message.set('Too many attempts. Wait before entering your credentials again.'); }
      else this.message.set('The password could not be changed. Check your current password and try again.');
      this.focus();
    } finally { request.currentPassword = ''; request.newPassword = ''; value.current = ''; value.replacement = ''; value.confirm = ''; if (this.current(generation)) { this.passwords.reset(); this.busy.set(false); } }
  }
  private cool(seconds: number): void { this.cooling.set(true); clearTimeout(this.timer); const deadline = Date.now() + seconds * 1000; const wake = () => { const remaining = deadline - Date.now(); if (remaining <= 0) this.cooling.set(false); else this.timer = setTimeout(wake, Math.min(remaining, 2147483647)); }; wake(); }
  private clear(): void { this.generation++; clearTimeout(this.timer); this.cooling.set(false); this.command?.discard(); this.command = null; this.profile.set(null); this.form.reset(); this.passwords.reset(); this.etag = ''; this.uncertain.set(false); this.busy.set(false); }
  private focus(): void { this.host.nativeElement.querySelector<HTMLElement>('#settings-message')?.focus(); }
}
