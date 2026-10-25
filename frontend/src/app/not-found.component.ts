import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';

@Component({
  imports: [RouterLink, MatButtonModule],
  template: `
    <section class="welcome" aria-labelledby="not-found-title">
      <p class="eyebrow">Page not found</p>
      <h1 id="not-found-title">This page is unavailable.</h1>
      <p>Return to the home page to continue.</p>
      <a mat-flat-button routerLink="/">Return home</a>
    </section>
  `,
})
export class NotFoundComponent {}
