import { Availability, Service, Slot } from '../shared/generated';

export interface ConsultationSelection {
  service: Service;
  policyVersion: number;
  slot: Slot;
  displayZone: string;
}

export function canonicalZone(zone: string): string | null {
  try { return new Intl.DateTimeFormat('en', { timeZone: zone.trim() }).resolvedOptions().timeZone; }
  catch { return null; }
}

export function civilDate(now: Date, zone: string): string {
  const parts = new Intl.DateTimeFormat('en', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now);
  const value = (type: string) => parts.find(part => part.type === type)!.value;
  return `${value('year')}-${value('month')}-${value('day')}`;
}

export function addDays(date: string, days: number): string {
  return new Date(Date.parse(date + 'T00:00:00Z') + days * 86400000).toISOString().slice(0, 10);
}

export function validRange(from: string, to: string): boolean {
  const valid = (value: string) => /^\d{4}-\d{2}-\d{2}$/.test(value)
    && Number.isFinite(Date.parse(value + 'T00:00:00Z'))
    && new Date(value + 'T00:00:00Z').toISOString().slice(0, 10) === value;
  return valid(from) && valid(to) && to >= from
    && (Date.parse(to + 'T00:00:00Z') - Date.parse(from + 'T00:00:00Z')) / 86400000 < 31;
}

export function retryDelay(header: string | null, now: number): number {
  if (header !== null && /^\d+$/.test(header) && Number.isFinite(Number(header))) return Math.max(1, Number(header));
  const deadline = header === null ? NaN : Date.parse(header);
  return Number.isFinite(deadline) ? Math.max(1, Math.ceil((deadline - now) / 1000)) : 60;
}

const labelFormats = new Map<string, Intl.DateTimeFormat>();

export function slotLabel(slot: Slot, zone: string): string {
  let formatter = labelFormats.get(zone);
  if (formatter === undefined) {
    formatter = new Intl.DateTimeFormat('en', { timeZone: zone, weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
    labelFormats.set(zone, formatter);
  }
  return formatter.format(new Date(slot.startInstant));
}

export function slotDuration(slot: Slot): number {
  return (Date.parse(slot.sessionEndInstant) - Date.parse(slot.startInstant)) / 60000;
}

export function selected(service: Service, availability: Availability, slot: Slot): ConsultationSelection {
  return { service, policyVersion: availability.policyVersion, slot, displayZone: availability.displayZone };
}

export function offsetLabel(offset: string): string { return offset === 'Z' ? 'UTC+00:00' : 'UTC' + offset; }
