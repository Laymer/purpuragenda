import { Component } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';

@Component({selector:'app-root',imports:[RouterLink,RouterOutlet],template:`
  <a class="skip" href="#main">Skip to content</a>
  <header class="site-header"><a class="brand" routerLink="/" aria-label="Purpuragenda home"><span class="brand-mark" aria-hidden="true">✳</span> purpuragenda<span class="brand-dot">.</span></a>
    <nav aria-label="Main navigation"><a routerLink="/book">Book a consultation</a><a routerLink="/account">My appointments</a></nav>
  </header>
  <main id="main" tabindex="-1"><router-outlet/></main>
  <footer><a class="brand" routerLink="/">purpuragenda.</a><p>Private consultations with Medina · Europe/Brussels</p><a routerLink="/admin">Owner sign in</a></footer>`})
export class AppComponent {}
