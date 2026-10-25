import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HomeComponent } from './home.component';
import { Api } from '../shared/api';
describe('Public service truth',()=>{
  afterEach(()=>TestBed.resetTestingModule());
  it('renders server prices and durations',async()=>{
    const api={get:vi.fn().mockResolvedValue([{id:'one',version:1,name:'Karmic reading',durationMinutes:30,priceMinor:1500,currency:'EUR',active:true}])};
    TestBed.configureTestingModule({imports:[HomeComponent],providers:[provideRouter([]),{provide:Api,useValue:api}]});const fixture=TestBed.createComponent(HomeComponent);await fixture.whenStable();fixture.detectChanges();expect(fixture.nativeElement.textContent).toContain('Karmic reading');expect(fixture.nativeElement.textContent).toContain('30 minutes');expect(fixture.nativeElement.textContent).toContain('15.00');
  });
  it('provides recovery when services are unavailable',async()=>{
    const api={get:vi.fn().mockRejectedValue(Error('offline'))};TestBed.configureTestingModule({imports:[HomeComponent],providers:[provideRouter([]),{provide:Api,useValue:api}]});const fixture=TestBed.createComponent(HomeComponent);await fixture.whenStable();fixture.detectChanges();expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();expect(fixture.nativeElement.textContent).toContain('Try again');
  });
});
