import { Component, DestroyRef, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { SessionAccess } from '../shared/access/session-access';

@Component({
  imports: [RouterLink, RouterOutlet, MatButtonModule],
  template: `
    <nav aria-label="Administrator">
      <a routerLink="/admin">Agenda</a>
      <a routerLink="/admin/bookings/new">Create booking</a>
      <a routerLink="/admin/services">Services</a>
      <a routerLink="/admin/policy">Policy</a>
      <a routerLink="/admin/calendar">Schedule</a>
      <a routerLink="/admin/customers">Customers</a>
      <a routerLink="/admin/audit">Audit</a>
      <button mat-stroked-button type="button" (click)="signOut()" [disabled]="busy()">Sign out</button>
    </nav>
    <router-outlet />`,
  styles: [`nav { display: flex; flex-wrap: wrap; gap: 1rem; align-items: center; justify-content: space-between; max-width: 60rem; margin: 1rem auto 0; padding: 0 1rem; }`],
})
export class AdminShellComponent {
  private readonly sessions = inject(SessionAccess);
  private readonly router = inject(Router);
  readonly busy = signal(false);
  constructor() {
    const remove = this.sessions.onInvalidated(() => { void this.router.navigateByUrl('/admin/login').catch(() => false); });
    inject(DestroyRef).onDestroy(remove);
  }
  async signOut(): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    try { await this.sessions.logout(); } catch { /* Private state is cleared by the access layer. */ }
    finally { this.busy.set(false); }
    await this.router.navigateByUrl('/admin/login').catch(() => false);
  }
}
