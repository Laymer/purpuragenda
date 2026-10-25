import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AccountComponent } from './account.component';
import { Api,ApiError } from '../shared/api';
import { StubApi,booking } from '../shared/test-support';
describe('Optional account and guest separation',()=>{
  let api:StubApi;async function render(){TestBed.configureTestingModule({imports:[AccountComponent],providers:[provideRouter([]),{provide:Api,useValue:api}]});const f=TestBed.createComponent(AccountComponent);await f.whenStable();f.detectChanges();return f;}
  beforeEach(()=>api=new StubApi());afterEach(()=>TestBed.resetTestingModule());
  it('never loads history for a guest and requires explicit enrollment intent',async()=>{api.identity.set({authenticated:true,kind:'MANAGEMENT_GRANT',bookingId:booking.id});const f=await render(),p=f.componentInstance;expect(api.get).not.toHaveBeenCalledWith('/account/bookings');await p.account();expect(api.post).not.toHaveBeenCalled();p.accountEmail='synthetic@example.test';p.enroll=true;await p.account();expect(api.post).toHaveBeenCalledWith('/auth/account-enrollment',expect.objectContaining({email:p.accountEmail,purpose:'ACCOUNT'}));expect(p.message()).toContain('inbox');});
  it('recovers one booking without account enrollment',async()=>{const f=await render(),p=f.componentInstance;p.reference=booking.reference;p.recoveryEmail=booking.deliveryEmail;await p.recover();expect(api.post).toHaveBeenCalledWith('/auth/management-recovery',{email:booking.deliveryEmail,reference:booking.reference,purpose:'MANAGE'});expect(p.message()).toContain('If the reference');});
  it('loads account history and saves profile as a separate versioned choice',async()=>{api.identity.set({authenticated:true,kind:'ACCOUNT'});const f=await render(),p=f.componentInstance;expect(p.bookings()).toHaveLength(1);expect(p.profileVersion).toBe(2);expect(api.command).not.toHaveBeenCalled();await p.saveProfile();expect(api.command).toHaveBeenCalledWith('/account/profile',{fields:p.profile},2,'PUT');await p.logout();expect(p.bookings()).toEqual([]);expect(api.identity().authenticated).toBe(false);});
  it('keeps errors visible and exits the busy state',async()=>{const f=await render(),p=f.componentInstance;api.post.mockRejectedValueOnce(new ApiError('RATE_LIMITED',429));await p.recover();expect(p.error()).toContain('wait');expect(p.busy()).toBe(false);api.me.mockRejectedValueOnce(new ApiError('NETWORK',0));await p.load();expect(p.error()).toContain('response');});
});
