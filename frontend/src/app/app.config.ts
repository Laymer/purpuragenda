import { ApplicationConfig } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter, Routes } from '@angular/router';
import { provideApi } from './shared/generated/provide-api';
import { adminGuard } from './admin/admin-guard';
import { accountGuard } from './account/account-guard';
import { HomeComponent } from './home.component';
import { NotFoundComponent } from './not-found.component';

export const routes: Routes = [
  { path: '', component: HomeComponent, title: 'Purpuragenda · Consultations with Medina' },
  { path: 'book', loadComponent: () => import('./booking/booking-flow.component').then(module => module.BookingFlowComponent), title: 'Choose a consultation · Purpuragenda' },
  { path: 'verify', loadComponent: () => import('./booking/verify-email.component').then(module => module.VerifyEmailComponent), title: 'Verify your email · Purpuragenda' },
  { path: 'manage', loadComponent: () => import('./manage/manage-request.component').then(module => module.ManageRequestComponent), title: 'Manage your booking · Purpuragenda' },
  { path: 'manage/booking', loadComponent: () => import('./manage/manage-booking.component').then(module => module.ManageBookingComponent), title: 'Your booking · Purpuragenda' },
  { path: 'account/create', loadComponent: () => import('./account/enrollment-request.component').then(module => module.EnrollmentRequestComponent), title: 'Create an optional account · Purpuragenda' },
  { path: 'account/login', loadComponent: () => import('./account/account-login.component').then(module => module.AccountLoginComponent), title: 'Account sign in · Purpuragenda' },
  { path: 'account/settings', canActivate: [accountGuard], loadComponent: () => import('./account/account-settings.component').then(module => module.AccountSettingsComponent), title: 'Account settings · Purpuragenda' },
  { path: 'account', canActivate: [accountGuard], loadComponent: () => import('./account/account-history.component').then(module => module.AccountHistoryComponent), title: 'Your account · Purpuragenda' },
  { path: 'account/bookings/:id', canActivate: [accountGuard], loadComponent: () => import('./manage/manage-booking.component').then(module => module.ManageBookingComponent), title: 'Your booking · Purpuragenda' },
  { path: 'admin/login', loadComponent: () => import('./admin/admin-auth.component').then(module => module.AdminAuthComponent), title: 'Administrator sign in · Purpuragenda' },
  {
    path: 'admin', canActivate: [adminGuard], loadComponent: () => import('./admin/admin-shell.component').then(module => module.AdminShellComponent),
    children: [
      { path: '', loadComponent: () => import('./admin/agenda.component').then(module => module.AgendaComponent), title: 'Agenda · Purpuragenda' },
      { path: 'calendar', loadComponent: () => import('./admin/calendar.component').then(module => module.CalendarComponent), title: 'Calendar · Purpuragenda' },
      { path: 'policy', loadComponent: () => import('./admin/policy.component').then(module => module.PolicyComponent), title: 'Booking policy · Purpuragenda' },
      { path: 'services', loadComponent: () => import('./admin/services.component').then(module => module.ServicesComponent), title: 'Services · Purpuragenda' },
      { path: 'bookings/new', loadComponent: () => import('./admin/manual-booking.component').then(module => module.ManualBookingComponent), title: 'Create booking · Purpuragenda' },
      { path: 'bookings/:id/reschedule', loadComponent: () => import('./admin/reschedule-booking.component').then(module => module.RescheduleBookingComponent), title: 'Reschedule booking · Purpuragenda' },
      { path: 'bookings/:id/finance', loadComponent: () => import('./admin/booking-finance.component').then(module => module.BookingFinanceComponent), title: 'Receipts and refunds · Purpuragenda' },
      { path: 'bookings/:id/history', loadComponent: () => import('./admin/history.component').then(module => module.HistoryComponent), title: 'Private booking history · Purpuragenda' },
      { path: 'reconciliation', data: { mode: 'reconciliation' }, loadComponent: () => import('./admin/operations.component').then(module => module.OperationsComponent), title: 'Outstanding refunds · Purpuragenda' },
      { path: 'notifications', data: { mode: 'notifications' }, loadComponent: () => import('./admin/operations.component').then(module => module.OperationsComponent), title: 'Notification follow-up · Purpuragenda' },
      { path: 'customers/:id/privacy', loadComponent: () => import('./admin/privacy.component').then(module => module.PrivacyComponent), title: 'Customer privacy · Purpuragenda' },
      { path: 'customers', loadComponent: () => import('./admin/customers.component').then(module => module.CustomersComponent), title: 'Customers · Purpuragenda' },
      { path: 'audit', loadComponent: () => import('./admin/history.component').then(module => module.HistoryComponent), title: 'Administrator audit · Purpuragenda' },
      { path: 'bookings/:id', loadComponent: () => import('./admin/booking-detail.component').then(module => module.BookingDetailComponent), title: 'Booking · Purpuragenda' },
    ],
  },
  { path: '**', component: NotFoundComponent, title: 'Page not found · Purpuragenda' },
];

export const appConfig: ApplicationConfig = {
  providers: [provideHttpClient(), provideRouter(routes), provideApi('/api/v1')],
};
