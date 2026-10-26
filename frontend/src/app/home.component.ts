import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';

@Component({
  imports: [RouterLink, MatButtonModule],
  template: `
    <section class="welcome" aria-labelledby="welcome-title">
      <p class="eyebrow">Medina’s practice</p>
      <h1 id="welcome-title">A little time for clarity.</h1>
      <p>Private consultations with Medina.</p>
      <a mat-flat-button routerLink="/book">Choose a consultation</a>
      <p class="business-zone">Business time · Europe/Brussels</p>
    </section>
  `,
})
export class HomeComponent {}
