import { Component, DestroyRef, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { GuestService } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { VERIFICATION_PROOF } from './verification-proof';
import { EnrollmentCompletionComponent } from '../account/enrollment-completion.component';

@Component({
  imports: [RouterLink, MatButtonModule, EnrollmentCompletionComponent],
  template: `
    @if (accountProof) { <app-enrollment-completion /> } @else {
    <section class="verification" aria-labelledby="verify-title" [attr.aria-busy]="busy()">
      <h1 id="verify-title">Verify your email</h1>
      @if (available()) {
        <p>Confirm this link in the browser that requested it. The link can be used once.</p>
        <button mat-flat-button (click)="verify()" [disabled]="busy()">{{ busy() ? 'Verifying…' : 'Verify email' }}</button>
      }
      <p role="status">{{ message() }}</p>
      @if (!busy()) { <a mat-stroked-button [routerLink]="management() ? (verified() ? '/manage/booking' : '/manage') : '/book'">{{ management() ? (verified() ? 'View your booking' : 'Request a new link') : verified() ? 'Continue booking' : 'Request a new link' }}</a> }
    </section> }`,
  styles: [`.verification { max-width: 40rem; margin: 2rem auto; padding: 1rem; }`],
})
export class VerifyEmailComponent {
  private readonly proof = inject(VERIFICATION_PROOF);
  private readonly guest = inject(GuestService);
  private readonly sessions = inject(SessionAccess);
  readonly accountProof = this.proof.account;
  private active = true;
  readonly available = signal(this.proof.available);
  readonly busy = signal(false);
  readonly verified = signal(false);
  readonly management = signal(false);
  readonly message = signal(this.proof.available ? '' : 'This link is missing or invalid. Request a new email in this browser.');
  constructor() { inject(DestroyRef).onDestroy(() => { this.active = false; this.proof.clear(); }); }
  async verify(): Promise<void> {
    if (this.busy()) return;
    const request = this.proof.take();
    if (request === null) return;
    this.management.set(request.purpose === 'MANAGE'); this.busy.set(true); this.available.set(false);
    try {
      await this.sessions.authenticate(csrf => this.guest.redeemGuestProof(csrf, request));
      if (this.active) { this.verified.set(true); this.message.set(this.management() ? 'Your email is verified. You can now view and manage your booking in this browser.' : 'Your email is verified for this browser.'); }
    } catch {
      if (this.active) this.message.set('We could not verify this link. It may have expired or been used. Request a new link in this browser.');
    } finally { if (this.active) this.busy.set(false); }
  }
}
