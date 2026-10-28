import { Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { AccountService } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';
import { VERIFICATION_PROOF } from '../booking/verification-proof';
import { passwordPolicy } from './password-policy';

@Component({
  selector: 'app-enrollment-completion',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule],
  template: `
    <section aria-labelledby="complete-account-title" [attr.aria-busy]="busy()">
      <h1 id="complete-account-title">Create your optional account</h1>
      @if (available()) {
        <p>This email link is for account creation in the browser that requested it. Choosing a password and submitting this form creates an account; opening the link does not.</p>
        <form [formGroup]="form" (ngSubmit)="enroll()">
          <label for="new-account-password">Choose a password (15–128 characters)</label>
          <input id="new-account-password" type="password" autocomplete="new-password" formControlName="password" [attr.aria-invalid]="invalid('password')" aria-describedby="complete-account-message">
          <label for="confirm-account-password">Repeat the password</label>
          <input id="confirm-account-password" type="password" autocomplete="new-password" formControlName="confirm" [attr.aria-invalid]="invalid('confirm')" aria-describedby="complete-account-message">
          <button mat-flat-button type="submit" [disabled]="busy()">Create my account</button>
        </form>
      }
      <p id="complete-account-message" role="alert" tabindex="-1">{{ message() }}</p>
      @if (!busy()) { <a mat-flat-button routerLink="/account/login">Sign in</a><a mat-stroked-button routerLink="/account/create">Request a new account link</a> }
    </section>`,
  styles: [`section { max-width: 36rem; margin: 2rem auto; padding: 1rem; } form { display: grid; gap: .65rem; } input { font: inherit; min-height: 2.6rem; padding: .4rem; border: 1px solid #756779; border-radius: .3rem; } a { margin: .5rem .75rem .5rem 0; }`],
})
export class EnrollmentCompletionComponent {
  private readonly proof = inject(VERIFICATION_PROOF);
  private readonly api = inject(AccountService);
  private readonly sessions = inject(SessionAccess);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private active = true;
  readonly available = signal(this.proof.account);
  readonly busy = signal(false);
  readonly message = signal(this.proof.account ? '' : 'This account link is missing or invalid. Request a new link in this browser.');
  readonly form = new FormGroup({
    password: new FormControl('', { nonNullable: true, validators: passwordPolicy }),
    confirm: new FormControl('', { nonNullable: true, validators: Validators.required }),
  });
  constructor() {
    const remove = this.sessions.onInvalidated(() => { this.proof.clear(); this.available.set(false); this.form.reset(); if (!this.busy()) this.message.set('Your browser session changed. Request a new account link.'); });
    inject(DestroyRef).onDestroy(() => { this.active = false; remove(); this.proof.clear(); this.form.reset(); });
  }
  invalid(name: 'password' | 'confirm'): boolean { const field = this.form.controls[name]; return field.touched && (field.invalid || (name === 'confirm' && field.value !== this.form.controls.password.value)); }
  async enroll(): Promise<void> {
    if (this.busy() || !this.available()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid || this.form.controls.password.value !== this.form.controls.confirm.value) { this.message.set('Choose a password of 15–128 characters and repeat it exactly.'); this.focus(); return; }
    const token = this.proof.takeAccount(); if (token === null) { this.available.set(false); this.message.set('This account link is no longer available. Request a new link.'); return; }
    const request = { token, password: this.form.controls.password.value }; this.form.reset(); this.available.set(false); this.busy.set(true); this.message.set('');
    try {
      await this.sessions.perform(csrf => this.api.enrollAccount(csrf, request));
      if (this.active) this.message.set('Your account was created. Sign in with your login email and password.');
    } catch (error) {
      if (!this.active) return;
      const failure = safeFailure(error, true);
      if (failure.kind === 'THROTTLED') this.message.set('Too many attempts. Wait before requesting a new link and trying again.');
      else if (failure.kind === 'REJECTED' || failure.kind === 'SESSION_EXPIRED') this.message.set('This account link could not be used. If you already have an account, sign in; otherwise request a new link in this browser.');
      else this.message.set('Account creation could not be confirmed. Try signing in with the password you chose. If sign-in fails, request a new link; do not repeat this submission.');
      this.focus();
    } finally { request.token = ''; request.password = ''; if (this.active) { this.form.reset(); this.busy.set(false); } }
  }
  private focus(): void { this.host.nativeElement.querySelector<HTMLElement>('#complete-account-message')?.focus(); }
}
