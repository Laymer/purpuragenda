import { Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { AccountService } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';

@Component({
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule],
  template: `
    <section aria-labelledby="enrollment-title" [attr.aria-busy]="busy()">
      <h1 id="enrollment-title">Create an optional account</h1>
      <p>An account lets you see your booking history and save an optional profile. You can book and manage individual consultations without one.</p>
      <p>Verify your email first, then choose a password. Your login email stays linked to this account.</p>
      <form [formGroup]="form" (ngSubmit)="send()">
        <label for="enrollment-email">Email address</label>
        <input id="enrollment-email" type="text" inputmode="email" autocomplete="email" autocapitalize="off" spellcheck="false" formControlName="email" [attr.aria-invalid]="form.controls.email.touched && form.invalid" aria-describedby="enrollment-message">
        <button mat-flat-button type="submit" [disabled]="busy() || cooling()">{{ busy() ? 'Sending…' : 'Email me an account link' }}</button>
      </form>
      <p id="enrollment-message" role="status" tabindex="-1">{{ message() }}</p>
      <a mat-stroked-button routerLink="/account/login">Already have an account? Sign in</a>
      <a mat-stroked-button routerLink="/book">Book without an account</a>
    </section>`,
  styles: [`section { max-width: 36rem; margin: 2rem auto; padding: 1rem; } form { display: grid; gap: .65rem; } input { font: inherit; min-height: 2.6rem; padding: .4rem; border: 1px solid #756779; border-radius: .3rem; } a { display: inline-block; margin: .5rem .75rem .5rem 0; }`],
})
export class EnrollmentRequestComponent {
  private readonly api = inject(AccountService);
  private readonly sessions = inject(SessionAccess);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private active = true;
  private timer?: ReturnType<typeof setTimeout>;
  readonly busy = signal(false);
  readonly cooling = signal(false);
  readonly message = signal('');
  readonly form = new FormGroup({ email: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(254), Validators.pattern(/^\s*[^\s@]+@[^\s@]+\.[^\s@]+\s*$/)] }) });
  constructor() {
    const remove = this.sessions.onInvalidated(() => this.form.reset());
    inject(DestroyRef).onDestroy(() => { this.active = false; remove(); clearTimeout(this.timer); this.form.reset(); });
  }
  async send(): Promise<void> {
    if (this.busy() || this.cooling()) return;
    this.form.markAllAsTouched(); if (this.form.invalid) { this.message.set('Enter a valid email address.'); this.focus(); return; }
    const email = this.form.controls.email.value.trim(); this.busy.set(true); this.message.set('');
    try {
      await this.sessions.perform(csrf => this.api.requestAccountEnrollmentProof(csrf, { email }));
      if (this.active) { this.message.set('Check your inbox for an account creation link. Open it in this browser and choose a password.'); this.cool(60); }
    } catch (error) {
      if (!this.active) return;
      const failure = safeFailure(error, true);
      if (failure.kind === 'THROTTLED') { this.cool(failure.retryAfterSeconds ?? 60); this.message.set('Please wait before requesting another account link.'); }
      else if (failure.kind === 'REJECTED') this.message.set('Check the email address, then try again.');
      else if (failure.kind === 'SESSION_EXPIRED') this.message.set('Your browser session expired or changed. Enter your email and try again.');
      else { this.cool(60); this.message.set('Delivery could not be confirmed. Check your inbox, then wait before requesting another link.'); }
      this.focus();
    } finally { if (this.active) this.busy.set(false); }
  }
  private focus(): void { this.host.nativeElement.querySelector<HTMLElement>('#enrollment-message')?.focus(); }
  private cool(seconds: number): void {
    this.cooling.set(true); clearTimeout(this.timer); const deadline = Date.now() + seconds * 1000;
    const wake = () => { const remaining = deadline - Date.now(); if (remaining <= 0) this.cooling.set(false); else this.timer = setTimeout(wake, Math.min(remaining, 2147483647)); }; wake();
  }
}
