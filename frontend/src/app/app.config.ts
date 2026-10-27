import { ApplicationConfig } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter, Routes } from '@angular/router';
import { provideApi } from './shared/generated/provide-api';
import { adminGuard } from './admin/admin-guard';
import { HomeComponent } from './home.component';
import { NotFoundComponent } from './not-found.component';

export const routes: Routes = [
  { path: '', component: HomeComponent, title: 'Purpuragenda · Consultations with Medina' },
  { path: 'book', loadComponent: () => import('./booking/booking-flow.component').then(module => module.BookingFlowComponent), title: 'Choose a consultation · Purpuragenda' },
  { path: 'verify', loadComponent: () => import('./booking/verify-email.component').then(module => module.VerifyEmailComponent), title: 'Verify your email · Purpuragenda' },
  { path: 'manage', loadComponent: () => import('./manage/manage-request.component').then(module => module.ManageRequestComponent), title: 'Manage your booking · Purpuragenda' },
  { path: 'manage/booking', loadComponent: () => import('./manage/manage-booking.component').then(module => module.ManageBookingComponent), title: 'Your booking · Purpuragenda' },
  { path: 'admin/login', loadComponent: () => import('./admin/admin-auth.component').then(module => module.AdminAuthComponent), title: 'Administrator sign in · Purpuragenda' },
  {
    path: 'admin', canActivate: [adminGuard], loadComponent: () => import('./admin/admin-shell.component').then(module => module.AdminShellComponent),
    children: [
      { path: '', loadComponent: () => import('./admin/agenda.component').then(module => module.AgendaComponent), title: 'Agenda · Purpuragenda' },
      { path: 'bookings/:id', loadComponent: () => import('./admin/booking-detail.component').then(module => module.BookingDetailComponent), title: 'Booking · Purpuragenda' },
    ],
  },
  { path: '**', component: NotFoundComponent, title: 'Page not found · Purpuragenda' },
];

export const appConfig: ApplicationConfig = {
  providers: [provideHttpClient(), provideRouter(routes), provideApi('/api/v1')],
};
