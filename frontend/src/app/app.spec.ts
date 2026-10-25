import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app.component';
describe('Application landmarks',()=>{it('provides a keyboard skip link, main landmark and owner entry',()=>{TestBed.configureTestingModule({imports:[AppComponent],providers:[provideRouter([])]});const f=TestBed.createComponent(AppComponent);f.detectChanges();expect(f.nativeElement.querySelector('.skip').getAttribute('href')).toBe('#main');expect(f.nativeElement.querySelector('main#main')).toBeTruthy();expect(f.nativeElement.textContent).toContain('Owner sign in');TestBed.resetTestingModule();});});
