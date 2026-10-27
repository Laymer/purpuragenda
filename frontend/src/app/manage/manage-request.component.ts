import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { GuestService } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';

@Component({
  imports: [ReactiveFormsModule, MatButtonModule],
  template: `
    <section aria-labelledby="manage-title" [attr.aria-busy]="busy()">
      <h1 id="manage-title" tabindex="-1">Manage your booking</h1>
      <p>Enter your booking reference and the email address used for the booking. If they match, we send a one-time link that works in this browser.</p>
      <form [formGroup]="form" (ngSubmit)="send()">
        <label for="manage-reference">Booking reference</label>
        <input id="manage-reference" type="text" autocomplete="off" autocapitalize="off" spellcheck="false" formControlName="reference" [attr.aria-invalid]="invalid('reference')" [attr.aria-describedby]="invalid('reference') ? 'manage-message' : null">
        <label for="manage-email">Email address</label>
        <input id="manage-email" type="email" autocomplete="email" autocapitalize="off" spellcheck="false" formControlName="email" [attr.aria-invalid]="invalid('email')" [attr.aria-describedby]="invalid('email') ? 'manage-message' : null">
        <button mat-flat-button type="submit" [disabled]="busy() || cooling()">{{ busy() ? 'Sending…' : 'Email me a link' }}</button>
      </form>
      <p id="manage-message" role="status" tabindex="-1">{{ message() }}</p>
    </section>`,
  styles: [`section { max-width: 34rem; margin: 2rem auto; padding: 1rem; } form { display: grid; gap: .65rem; } input { font: inherit; min-height: 2.6rem; padding: .4rem; border: 1px solid #756779; border-radius: .3rem; } input[aria-invalid="true"] { border: 2px solid #9b203c; }`],
})
export class ManageRequestComponent {
  private readonly sessions = inject(SessionAccess);
  private readonly guest = inject(GuestService);
  private active = true;
  private timer?: ReturnType<typeof setTimeout>;
  readonly busy = signal(false);
  readonly cooling = signal(false);
  readonly message = signal('');
  readonly form = new FormGroup({
    reference: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(64)] }),
    email: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(254), Validators.pattern(/^\s*[^\s@]+@[^\s@]+\.[^\s@]+\s*$/)] }),
  });
  constructor() {
    inject(DestroyRef).onDestroy(() => { this.active = false; clearTimeout(this.timer); this.form.reset(); });
  }
  invalid(name: 'reference' | 'email'): boolean { const control = this.form.controls[name]; return control.touched && control.invalid; }

  async send(): Promise<void> {
    if (this.busy() || this.cooling()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) { this.message.set('Enter your booking reference and a valid email address.'); return; }
    const value = this.form.getRawValue(); this.busy.set(true); this.message.set('');
    try {
      await this.sessions.perform(csrf => this.guest.requestManagementProof(csrf, { reference: value.reference.trim(), email: value.email.trim() }));
      // The reply is identical for known and unknown pairs, so the message must be too.
      if (this.active) { this.message.set('If those details match a booking, a link has been sent. Open it in this browser and choose Verify email.'); this.cool(60); }
    } catch (error) {
      if (!this.active) return;
      const failure = safeFailure(error, true);
      if (failure.kind === 'THROTTLED') { this.cool(failure.retryAfterSeconds ?? 60); this.message.set('Please wait before requesting another link.'); }
      else if (failure.kind === 'REJECTED') this.message.set('Check the booking reference and email address, then try again.');
      else if (failure.kind === 'SESSION_EXPIRED') this.message.set('Your browser session expired. Try again.');
      else this.message.set('We could not send a link right now. Wait a moment, then try again.');
    } finally { if (this.active) this.busy.set(false); }
  }

  private cool(seconds: number): void {
    this.cooling.set(true); clearTimeout(this.timer);
    this.timer = setTimeout(() => { if (this.active) this.cooling.set(false); }, Math.min(seconds * 1000, 2147483647));
  }
}
