import { Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { AccountService, SessionStateRoleEnum } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { AccessFailure } from '../shared/access/access-failure';

@Component({
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule],
  template: `
    <section aria-labelledby="account-login-title" [attr.aria-busy]="busy()">
      <h1 id="account-login-title">Account sign in</h1>
      @if (account()) {
        <p>Your account session is active.</p><a mat-flat-button routerLink="/account">View your bookings</a>
      } @else {
        <p>Use the email address and password you chose for your optional account.</p>
        <form [formGroup]="form" (ngSubmit)="login()">
          <label for="account-email">Login email</label>
          <input id="account-email" type="text" inputmode="email" autocomplete="username" autocapitalize="off" spellcheck="false" formControlName="email" [attr.aria-invalid]="invalid('email')" [attr.aria-describedby]="invalid('email') ? 'account-login-error' : null">
          <label for="account-password">Password</label>
          <input id="account-password" type="password" autocomplete="current-password" formControlName="password" [attr.aria-invalid]="invalid('password')" [attr.aria-describedby]="invalid('password') ? 'account-login-error' : null">
          <button mat-flat-button type="submit" [disabled]="busy() || cooling()">{{ busy() ? 'Signing in…' : 'Sign in' }}</button>
        </form>
        <p>You can still manage an individual booking using a new email link.</p><a mat-stroked-button routerLink="/manage">Request a booking link</a>
      }
      <button mat-stroked-button type="button" (click)="checkSession()" [disabled]="busy()">Check browser session</button>
      <p id="account-login-error" role="alert" tabindex="-1">{{ message() }}</p>
      @if (cooling()) { <p role="status">Please wait before trying again.</p> }
    </section>`,
  styles: [`section { max-width: 34rem; margin: 2rem auto; padding: 1rem; } form { display: grid; gap: .65rem; } input { font: inherit; min-height: 2.6rem; padding: .4rem; border: 1px solid #756779; border-radius: .3rem; } button, a { margin: .5rem .75rem .5rem 0; } input:focus-visible { outline: 3px solid #704482; outline-offset: 2px; }`],
})
export class AccountLoginComponent {
  readonly sessions = inject(SessionAccess);
  private readonly api = inject(AccountService);
  private readonly router = inject(Router);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private active = true;
  private timer?: ReturnType<typeof setTimeout>;
  readonly busy = signal(false);
  readonly cooling = signal(false);
  readonly message = signal('');
  readonly form = new FormGroup({
    email: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(/^[^\s@]+@[^\s@]+\.[^\s@]+$/)] }),
    password: new FormControl('', { nonNullable: true, validators: Validators.required }),
  });
  constructor() {
    const remove = this.sessions.onInvalidated(() => { this.form.reset(); if (!this.busy()) this.message.set('Your session expired or changed. Check the browser session, then sign in again.'); });
    inject(DestroyRef).onDestroy(() => { this.active = false; remove(); clearTimeout(this.timer); this.form.reset(); });
  }
  account(): boolean { return this.sessions.state()?.role === SessionStateRoleEnum.Account; }
  invalid(name: 'email' | 'password'): boolean { const field = this.form.controls[name]; return field.touched && field.invalid; }
  async login(): Promise<void> {
    if (this.busy() || this.cooling()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) { this.message.set('Enter your login email and password.'); this.focus(); return; }
    const request = this.form.getRawValue(); this.busy.set(true); this.message.set(''); this.form.controls.password.reset();
    try {
      const state = await this.sessions.authenticate(csrf => this.api.loginAccount(csrf, request));
      if (this.active) {
        this.message.set(state === this.sessions.state() && this.account() ? 'Account sign in complete.' : 'Your session changed. Check the browser session before continuing.');
        if (this.account()) await this.router.navigateByUrl('/account').catch(() => false);
      }
    } catch (error) { if (this.active) this.failure(error); }
    finally { request.password = ''; if (this.active) { this.form.controls.password.reset(); this.busy.set(false); } }
  }
  async checkSession(): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true); this.form.reset();
    try { const state = await this.sessions.bootstrap(); if (this.active) this.message.set(state === this.sessions.state() && this.account() ? 'Your account session is active.' : 'Sign in with your login email and password.'); }
    catch (error) { if (this.active) this.failure(error); }
    finally { if (this.active) this.busy.set(false); }
  }
  private focus(): void { this.host.nativeElement.querySelector<HTMLElement>('#account-login-error')?.focus(); }
  private failure(error: unknown): void {
    const failure = error instanceof AccessFailure ? error : new AccessFailure('UNCERTAIN');
    if (failure.kind === 'THROTTLED') {
      this.cooling.set(true); clearTimeout(this.timer); const deadline = Date.now() + (failure.retryAfterSeconds ?? 60) * 1000;
      const wake = () => { const remaining = deadline - Date.now(); if (remaining <= 0) this.cooling.set(false); else this.timer = setTimeout(wake, Math.min(remaining, 2147483647)); }; wake();
      this.message.set('Too many attempts. Wait before signing in again.');
    } else if (failure.kind === 'REJECTED') this.message.set('Sign in failed. Check your login email and password.');
    else if (failure.kind === 'SESSION_EXPIRED') this.message.set('Your session expired or changed. Check the browser session, then sign in again.');
    else this.message.set('Sign in could not be confirmed. Check the browser session before trying again.');
    this.focus();
  }
}
