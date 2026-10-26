import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { AdministratorService, SessionStateRoleEnum } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { AccessFailure } from '../shared/access/access-failure';

@Component({
  imports: [ReactiveFormsModule, MatButtonModule],
  template: `
    <section aria-labelledby="admin-title" [attr.aria-busy]="busy()">
      <h1 id="admin-title">Administrator sign in</h1>
      @if (administrator()) {
        <p>You are signed in as an administrator.</p>
        <button mat-stroked-button (click)="logout()" [disabled]="busy()">Sign out</button>
      } @else {
        <p>Use your private administrator account and a fresh authenticator code.</p>
        <form [formGroup]="form" (ngSubmit)="login()">
          <label for="admin-email">Email address</label>
          <input id="admin-email" type="text" inputmode="email" autocomplete="username" formControlName="email" [attr.aria-invalid]="invalid('email')" [attr.aria-describedby]="invalid('email') ? 'admin-error' : null">
          <label for="admin-password">Password</label>
          <input id="admin-password" type="password" autocomplete="current-password" formControlName="password" [attr.aria-invalid]="invalid('password')" [attr.aria-describedby]="invalid('password') ? 'admin-error' : null">
          <label for="admin-totp">Authenticator code</label>
          <input id="admin-totp" type="text" inputmode="numeric" autocomplete="one-time-code" formControlName="totp" maxlength="6" [attr.aria-invalid]="invalid('totp')" [attr.aria-describedby]="invalid('totp') ? 'admin-error' : null">
          <button mat-flat-button type="submit" [disabled]="busy() || cooling()">{{ busy() ? 'Signing in…' : 'Sign in' }}</button>
        </form>
        <p>Lost access? Use the private administrator recovery procedure. Recovery permits reenrollment; signing in still requires fresh multifactor authentication.</p>
        <button mat-stroked-button (click)="checkSession()" [disabled]="busy()">Check browser session</button>
      }
      <p id="admin-error" role="alert" tabindex="-1">{{ message() }}</p>
      @if (cooling()) { <p role="status">Please wait before trying again.</p> }
    </section>`,
  styles: [`section { max-width: 34rem; margin: 2rem auto; padding: 1rem; } form { display: grid; gap: .65rem; } input { font: inherit; min-height: 2.6rem; padding: .4rem; border: 1px solid #756779; border-radius: .3rem; } button { margin-top: .5rem; } input:focus-visible { outline: 3px solid #704482; outline-offset: 2px; }`],
})
export class AdminAuthComponent {
  readonly sessions = inject(SessionAccess);
  private readonly api = inject(AdministratorService);
  private active = true;
  private timer?: ReturnType<typeof setTimeout>;
  readonly busy = signal(false);
  readonly cooling = signal(false);
  readonly message = signal('');
  readonly form = new FormGroup({
    email: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(/^[^\s@]+@[^\s@]+\.[^\s@]+$/)] }),
    password: new FormControl('', { nonNullable: true, validators: Validators.required }),
    totp: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(/^\d{6}$/)] }),
  });
  constructor() {
    const remove = this.sessions.onInvalidated(() => { this.clearSecrets(); });
    inject(DestroyRef).onDestroy(() => { this.active = false; remove(); clearTimeout(this.timer); this.form.reset(); });
  }
  administrator(): boolean { return this.sessions.state()?.role === SessionStateRoleEnum.Admin; }
  invalid(name: 'email' | 'password' | 'totp'): boolean { const control = this.form.controls[name]; return control.touched && control.invalid; }
  private clearSecrets(): void { this.form.controls.password.reset(); this.form.controls.totp.reset(); }
  async login(): Promise<void> {
    if (this.busy() || this.cooling()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) { this.message.set('Enter an email address, password and six-digit authenticator code.'); return; }
    const request = this.form.getRawValue(); this.busy.set(true); this.message.set(''); this.clearSecrets();
    try {
      const state = await this.sessions.authenticate(csrf => this.api.loginAdministrator(csrf, request));
      if (this.active) this.message.set(state === this.sessions.state() && this.administrator() ? 'Administrator sign in complete.' : 'Your session changed. Check the browser session before continuing.');
    } catch (error) { if (this.active) this.failure(error); }
    finally { if (this.active) { this.clearSecrets(); this.busy.set(false); } }
  }
  async checkSession(): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true); this.clearSecrets();
    try { const state = await this.sessions.bootstrap(); if (this.active) this.message.set(state === this.sessions.state() && this.administrator() ? 'Administrator session is active.' : 'Sign in with your password and a fresh authenticator code.'); }
    catch (error) { if (this.active) this.failure(error); }
    finally { if (this.active) this.busy.set(false); }
  }
  async logout(): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true); this.clearSecrets();
    try { await this.sessions.logout(); if (this.active) this.message.set('You are signed out.'); }
    catch { if (this.active) this.message.set('Private browser state was cleared. Sign out could not be confirmed; check the browser session before continuing.'); }
    finally { if (this.active) this.busy.set(false); }
  }
  private failure(error: unknown): void {
    const failure = error instanceof AccessFailure ? error : new AccessFailure('UNCERTAIN');
    if (failure.kind === 'THROTTLED') {
      this.cooling.set(true); clearTimeout(this.timer);
      const deadline = Date.now() + (failure.retryAfterSeconds ?? 60) * 1000;
      const wake = () => { const remaining = deadline - Date.now(); if (remaining <= 0) this.cooling.set(false); else this.timer = setTimeout(wake, Math.min(remaining, 2147483647)); }; wake();
      this.message.set('Too many attempts. Wait before using a fresh authenticator code.');
    } else if (failure.kind === 'REJECTED') this.message.set('Sign in failed. Check your credentials and use a fresh authenticator code.');
    else if (failure.kind === 'SESSION_EXPIRED') this.message.set('Your session expired or changed. Check the browser session, then sign in again.');
    else this.message.set('Sign in could not be confirmed. Check the browser session before trying again.');
  }
}
