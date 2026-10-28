import { Component, DestroyRef, ElementRef, effect, inject, input, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { BookingsService } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';
import { safeFailure } from '../shared/access/access-failure';

@Component({
  selector: 'app-contact-request', imports: [ReactiveFormsModule, MatButtonModule],
  template: `<section aria-labelledby="contact-request-title" [attr.aria-busy]="busy()">
    <h2 id="contact-request-title">Delivery email</h2>
    <p>Send booking messages to another verified email address. This does not change booking ownership, account login or booking limits. Both addresses receive a notice after confirmation.</p>
    <form [formGroup]="form" (ngSubmit)="send()"><fieldset [disabled]="disabled() || busy() || cooling() || lost()">
      <legend>Verify a new delivery address</legend>
      <label for="contact-email">New delivery email</label><input id="contact-email" type="text" inputmode="email" autocomplete="email" formControlName="email" [attr.aria-invalid]="email.touched && email.invalid" aria-describedby="contact-request-message">
      <button mat-stroked-button type="submit">Send contact verification link</button>
    </fieldset></form><p id="contact-request-message" role="status" tabindex="-1">{{ message() }}</p>
  </section>`,
  styles: [`fieldset { display: grid; gap: .65rem; } input { font: inherit; min-height: 2.6rem; padding: .4rem; } section { margin-block: 1.5rem; }`],
})
export class ContactRequestComponent {
  readonly bookingId = input.required<string>(); readonly disabled = input(false);
  private readonly api = inject(BookingsService); private readonly sessions = inject(SessionAccess); private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private active = true; private generation = 0; private timer?: ReturnType<typeof setTimeout>;
  readonly busy = signal(false); readonly cooling = signal(false); readonly lost = signal(false); readonly message = signal('');
  readonly email = new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(254), Validators.pattern(/^\s*[^\s@]+@[^\s@]+\.[^\s@]+\s*$/)] });
  readonly form = new FormGroup({ email: this.email });
  constructor() {
    effect(() => { this.bookingId(); untracked(() => { this.clear(); this.lost.set(false); this.message.set(''); }); });
    const remove = this.sessions.onInvalidated(() => { this.clear(); this.lost.set(true); this.message.set('Restore access to this booking before requesting a contact link.'); });
    inject(DestroyRef).onDestroy(() => { this.active = false; remove(); this.clear(); });
  }
  async send(): Promise<void> {
    if (this.disabled() || this.busy() || this.cooling() || this.lost()) return;
    this.email.markAsTouched(); if (this.email.invalid) { this.message.set('Enter a valid email address.'); this.focus(); return; }
    const id = this.bookingId(), generation = this.generation, email = this.email.value.trim(); this.busy.set(true); this.message.set('');
    const current = () => this.active && generation === this.generation && id === this.bookingId();
    try { await this.sessions.perform(csrf => this.api.requestContactProof(id, csrf, { email })); if (current()) { this.message.set('Check the new inbox. Open the link in this browser, review the booking and explicitly confirm the change.'); this.cool(60); } }
    catch (error) {
      if (!current()) return; const failure = safeFailure(error, true);
      if (failure.kind === 'SESSION_EXPIRED' || failure.status === 403 || failure.status === 404) { this.clear(); this.lost.set(true); this.message.set('Restore access to this booking before requesting a contact link.'); }
      else if (failure.kind === 'THROTTLED') { this.cool(failure.retryAfterSeconds ?? 60); this.message.set('Please wait before requesting another contact link.'); }
      else if (failure.kind === 'REJECTED') this.message.set('Check the new email address and try again.');
      else { this.cool(60); this.message.set('Delivery could not be confirmed. Check the new inbox, then wait before requesting another link.'); }
      this.focus();
    } finally { if (current()) this.busy.set(false); }
  }
  private cool(seconds: number): void { this.cooling.set(true); clearTimeout(this.timer); const deadline = Date.now() + seconds * 1000; const wake = () => { const left = deadline - Date.now(); if (left <= 0) this.cooling.set(false); else this.timer = setTimeout(wake, Math.min(left, 2147483647)); }; wake(); }
  private clear(): void { this.generation++; clearTimeout(this.timer); this.cooling.set(false); this.busy.set(false); this.email.reset(); }
  private focus(): void { this.host.nativeElement.querySelector<HTMLElement>('#contact-request-message')?.focus(); }
}
