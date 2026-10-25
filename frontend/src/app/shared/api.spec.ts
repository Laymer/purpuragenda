import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting,HttpTestingController } from '@angular/common/http/testing';
import { Api,ApiError,errorText,money } from './api';

describe('API session and uncertain-request handling',()=>{
  let api:Api,http:HttpTestingController;
  beforeEach(()=>{TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting()]});api=TestBed.inject(Api);http=TestBed.inject(HttpTestingController);});
  afterEach(()=>{try{http.verify();}finally{TestBed.resetTestingModule();}});
  it('bootstraps CSRF and preserves an idempotency key after a lost response',async()=>{
    const first=api.command('/bookings',{serviceId:'synthetic'});const rejected=expect(first).rejects.toMatchObject({code:'NETWORK'});
    http.expectOne('/api/v1/security/csrf').flush({token:'synthetic-csrf'});await new Promise(resolve=>setTimeout(resolve,0));
    const request=http.expectOne('/api/v1/bookings');expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('synthetic-csrf');const key=request.request.headers.get('Idempotency-Key');expect(key).toBeTruthy();request.error(new ProgressEvent('error'));await rejected;
    const retry=api.command('/bookings',{serviceId:'synthetic'});const replay=http.expectOne('/api/v1/bookings');expect(replay.request.headers.get('Idempotency-Key')).toBe(key);replay.flush({code:'OK',bookingId:'one'});expect((await retry).bookingId).toBe('one');
  });
  it('uses a new key after a definitive conflict and sends expected versions',async()=>{
    const csrf=api.refreshCsrf();http.expectOne('/api/v1/security/csrf').flush({token:'synthetic'});await csrf;
    const result=api.command('/bookings/one/reschedules',{startInstant:'synthetic'},3);const rejected=expect(result).rejects.toMatchObject({code:'SLOT_UNAVAILABLE'});const req=http.expectOne('/api/v1/bookings/one/reschedules');const key=req.request.headers.get('Idempotency-Key');expect(req.request.headers.get('If-Match')).toBe('"3"');req.flush({code:'SLOT_UNAVAILABLE'},{status:409,statusText:'Conflict'});await rejected;
    const retry=api.command('/bookings/one/reschedules',{startInstant:'synthetic'},3);const req2=http.expectOne('/api/v1/bookings/one/reschedules');expect(req2.request.headers.get('Idempotency-Key')).not.toBe(key);req2.flush({code:'OK'});await retry;
  });
  it('keeps guest scope separate and clears identity on logout',async()=>{
    const me=api.me();http.expectOne('/api/v1/me').flush({authenticated:true,kind:'MANAGEMENT_GRANT',bookingId:'one'});await me;expect(api.identity().kind).toBe('MANAGEMENT_GRANT');
    const out=api.logout();http.expectOne('/api/v1/security/csrf').flush({token:'synthetic'});await new Promise(resolve=>setTimeout(resolve,0));http.expectOne('/api/v1/auth/logout').flush(null);await out;expect(api.identity().authenticated).toBe(false);
  });
  it('shows business errors without exposing raw backend values',()=>{expect(errorText(new ApiError('SLOT_UNAVAILABLE',409))).toContain('choose another');expect(errorText({secret:'must-not-render'})).not.toContain('must-not-render');expect(money(1500)).toContain('15.00');});
});
