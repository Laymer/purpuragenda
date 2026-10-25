import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app/app.component';

bootstrapApplication(AppComponent, { providers: [provideHttpClient(), provideRouter([
  {path:'',loadComponent:()=>import('./app/public/home.component').then(m=>m.HomeComponent)},
  {path:'book',loadComponent:()=>import('./app/booking/booking.component').then(m=>m.BookingComponent)},
  {path:'auth',loadComponent:()=>import('./app/account/auth.component').then(m=>m.AuthComponent)},
  {path:'account',loadComponent:()=>import('./app/account/account.component').then(m=>m.AccountComponent)},
  {path:'booking/:id',loadComponent:()=>import('./app/booking/manage.component').then(m=>m.ManageComponent)},
  {path:'admin',loadComponent:()=>import('./app/admin/admin.component').then(m=>m.AdminComponent)},
  {path:'**',redirectTo:''}
])] }).catch(()=>{document.body.textContent='The application could not start. Please reload the page.';});
