import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { AuthComponent } from './auth.component';
import { Api } from '../shared/api';
describe('Inert email landing',()=>{
  afterEach(()=>TestBed.resetTestingModule());
  it('removes the fragment and redeems only after an explicit action',async()=>{
    const api={identity:signal({authenticated:false}),refreshCsrf:vi.fn().mockResolvedValue(undefined),post:vi.fn().mockResolvedValue({}),me:vi.fn().mockResolvedValue({})};
    TestBed.configureTestingModule({imports:[AuthComponent],providers:[provideRouter([]),{provide:Api,useValue:api}]});
    history.replaceState(null,'','#token=synthetic-fragment');const fixture=TestBed.createComponent(AuthComponent);fixture.detectChanges();expect(location.hash).toBe('');expect(api.post).not.toHaveBeenCalled();
    await fixture.componentInstance.redeem();expect(api.post).toHaveBeenCalledWith('/auth/email/redemptions',{token:'synthetic-fragment'});expect(fixture.componentInstance.token).toBe('');expect(fixture.componentInstance.done()).toBe(true);
  });
});
