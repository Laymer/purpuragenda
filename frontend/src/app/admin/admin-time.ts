const ZONE = 'Europe/Brussels';
const DAY = /^(\d{4})-(\d{2})-(\d{2})$/;
const parts = new Intl.DateTimeFormat('en-CA', { timeZone: ZONE, year: 'numeric', month: '2-digit', day: '2-digit' });
const clock = new Intl.DateTimeFormat('en-GB', { timeZone: ZONE, weekday: 'short', day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZoneName: 'shortOffset' });

export const MAX_RANGE_DATES = 31;

/** Europe/Brussels civil date (YYYY-MM-DD) of an instant. */
export function brusselsDate(instant: Date): string { return parts.format(instant); }

export function parseDay(value: string): number | null {
  const match = DAY.exec(value); if (match === null) return null;
  const time = Date.UTC(+match[1], +match[2] - 1, +match[3]);
  return new Date(time).toISOString().slice(0, 10) === value ? time : null;
}

export function addDays(value: string, days: number): string {
  const time = parseDay(value); if (time === null) throw new RangeError('Invalid date');
  return new Date(time + days * 86400000).toISOString().slice(0, 10);
}

/** Monday-to-Sunday week containing the civil date. */
export function weekOf(value: string): { from: string; through: string } {
  const time = parseDay(value); if (time === null) throw new RangeError('Invalid date');
  const from = addDays(value, -((new Date(time).getUTCDay() + 6) % 7));
  return { from, through: addDays(from, 6) };
}

/** Null when valid, otherwise a user-facing hint; the server remains authoritative. */
export function rangeProblem(from: string, through: string): string | null {
  const start = parseDay(from), end = parseDay(through);
  if (start === null || end === null) return 'Choose valid start and end dates.';
  if (end < start) return 'The end date cannot be before the start date.';
  if ((end - start) / 86400000 + 1 > MAX_RANGE_DATES) return 'Choose at most 31 dates.';
  return null;
}

/** Brussels wall time with an explicit UTC offset, unambiguous at DST overlaps. */
export function brusselsDisplay(instant: string): string {
  const time = Date.parse(instant); if (!Number.isFinite(time)) return 'Unknown time';
  return clock.format(new Date(time)).replace('GMT', 'UTC').replace(',', '');
}

/** Hint only. The server validates the meeting URL authoritatively. */
export function zoomHint(value: string): string | null {
  let url: URL;
  try { url = new URL(value.trim()); } catch { return 'Enter the full https Zoom meeting link.'; }
  const host = url.hostname.toLowerCase();
  if (url.protocol !== 'https:' || url.username !== '' || url.password !== '' || (host !== 'zoom.us' && !host.endsWith('.zoom.us'))) return 'Use an https link on zoom.us.';
  if (!/^\/j\/\d{9,12}\/?$/.test(url.pathname)) return 'Use a meeting link like https://zoom.us/j/123456789.';
  return null;
}
