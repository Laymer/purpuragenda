import { Component } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';

@Component({
  selector: 'app-root',
  imports: [RouterLink, RouterOutlet, MatToolbarModule],
  template: `
    <a class="skip-link" href="#main">Skip to content</a>
    <header><mat-toolbar class="site-header">
      <a class="brand" routerLink="/" aria-label="Purpuragenda home">purpuragenda<span>.</span></a>
      <a class="account-link" routerLink="/account">Your account</a>
    </mat-toolbar></header>
    <main id="main" tabindex="-1"><router-outlet /></main>
    <footer>Private consultations with Medina · Europe/Brussels</footer>
  `,
})
export class AppComponent {}
