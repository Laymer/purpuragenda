import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
export { CatalogService as GeneratedCatalogClient } from './generated/api/catalog.service';

export interface Service { id:string;version:number;name:string;durationMinutes:number;priceMinor:number;currency:string;active:boolean; }
export interface Slot { startInstant:string;sessionEndInstant:string;reservedEndInstant:string;businessDate:string;displayLocalTime:string;offset:string; }
export interface Availability {serverNow:string;businessZone:string;displayZone:string;horizonInstant:string;policyVersion:number;slots:Slot[];restrictions:string[];}
export interface Identity {authenticated:boolean;kind?:string;bookingId?:string;absoluteExpiry?:string;}
export interface Receipt {code:string;bookingId:string;version:number;warning?:string;}
export interface Booking {id:string;reference:string;status:string;version:number;service:Service;intake:{firstName:string;lastName:string;telephone:string;birthDate:string;birthTime:string;birthplace:string;message:string};deliveryEmail:string;displayZone:string;startInstant:string;sessionEndInstant:string;reservedEndInstant:string;expiresAt:string|null;cancellationDeadline:string;participantUrl:string|null;permittedActions:string[];payment:{status:string;receivedMinor:number;refundedMinor:number;remainingMinor:number;refundDueByCurrency:Record<string,number>};}
export const money=(minor:number,currency='EUR')=>new Intl.NumberFormat('en',{style:'currency',currency}).format(minor/100);
export const dateTime=(instant:string,zone:string)=>new Intl.DateTimeFormat('en-GB',{dateStyle:'full',timeStyle:'short',timeZone:zone}).format(new Date(instant));
export function errorText(error:unknown):string {const code=error instanceof ApiError?error.code:'';return ({SLOT_UNAVAILABLE:'That time was just taken. Please choose another available time.',DAILY_CAPACITY_REACHED:'That day is fully booked. Please choose another day.',ACTIVE_LIMIT_REACHED:'You already have three active appointments.',REQUEST_LIMIT_REACHED:'You have reached today’s three-request limit. Please try again tomorrow (Brussels time).',SERVICE_CHANGED:'The service has changed. Please refresh and review the new details.',POLICY_CHANGED:'The booking policy has changed. Please refresh and review the available times.',CSRF_REQUIRED:'Your session needs refreshing. Reload this page and try again.',UNAUTHENTICATED:'Your session has expired. Please verify your email or sign in again.',AUTHENTICATION_FAILED:'Those sign-in details could not be verified. Please try again.',INVALID_TOKEN:'This link has expired, was already used, or belongs to a different browser. Request a new link.',RATE_LIMITED:'Please wait before trying again. Email requests have a 60-second cooldown.',CUTOFF_PASSED:'The 24-hour change deadline has passed. Please contact Medina.',HOLD_EXPIRED:'This pending request has expired. Please choose a new appointment.',MEETING_REQUIRED:'Add a valid unique Zoom participant link to confirm this appointment.',DUPLICATE_REFERENCE:'That payment reference or Zoom meeting is already in use.',STALE_VERSION:'This record changed. Refresh it before trying again.',REAUTHENTICATION_REQUIRED:'Please sign in again before this sensitive operation.',VALIDATION_FAILED:'Please check the required fields and try again.',NETWORK:'The response could not be confirmed. Your details are still here; retry to safely check the same request.'} as Record<string,string>)[code]||'The request could not be completed. Please review your details and try again.';}
export class ApiError extends Error {constructor(public code:string,public status:number){super(code);}}
@Injectable({providedIn:'root'})
export class Api {
  private http=inject(HttpClient);private csrf='';private uncertain=new Map<string,string>();
  identity=signal<Identity>({authenticated:false});
  async refreshCsrf(){const value=await this.get<{token:string}>('/security/csrf');this.csrf=value.token;}
  async me(){const value=await this.get<Identity>('/me');this.identity.set(value);return value;}
  async get<T>(path:string){try{return await firstValueFrom(this.http.get<T>('/api/v1'+path));}catch(e){throw this.failure(e);}}
  async post<T>(path:string,body:unknown){return this.send<T>('POST',path,body);}
  async command<T=Receipt>(path:string,body:unknown,version?:number,method='POST'){
    const signature=method+path+JSON.stringify(body)+(version??'');const key=this.uncertain.get(signature)??crypto.randomUUID();this.uncertain.set(signature,key);
    try{const result=await this.send<T>(method,path,body,version,key);this.uncertain.delete(signature);return result;}catch(e){if(e instanceof ApiError&&e.status!==0&&e.status<500)this.uncertain.delete(signature);throw e;}
  }
  private async send<T>(method:string,path:string,body:unknown,version?:number,key?:string){
    if(!this.csrf)await this.refreshCsrf();let headers=new HttpHeaders({'X-CSRF-TOKEN':this.csrf});if(key)headers=headers.set('Idempotency-Key',key);if(version!==undefined)headers=headers.set('If-Match','"'+version+'"');
    try{return await firstValueFrom(this.http.request<T>(method,'/api/v1'+path,{body,headers}));}catch(e){throw this.failure(e);}
  }
  async logout(){await this.post('/auth/logout',{});this.csrf='';this.identity.set({authenticated:false});}
  private failure(e:unknown){return e instanceof HttpErrorResponse?new ApiError(e.error?.code??(e.status===0?'NETWORK':'REQUEST_FAILED'),e.status):e;}
}
