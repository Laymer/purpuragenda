import { ApplicationConfig } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter, Routes } from '@angular/router';
import { provideApi } from './shared/generated/provide-api';
import { HomeComponent } from './home.component';
import { NotFoundComponent } from './not-found.component';

export const routes: Routes = [
  { path: '', component: HomeComponent, title: 'Purpuragenda · Consultations with Medina' },
  { path: '**', component: NotFoundComponent, title: 'Page not found · Purpuragenda' },
];

export const appConfig: ApplicationConfig = {
  providers: [provideHttpClient(), provideRouter(routes), provideApi('/api/v1')],
};
