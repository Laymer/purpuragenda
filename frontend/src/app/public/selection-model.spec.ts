import { addDays, canonicalZone, civilDate, retryDelay, slotDuration, slotLabel, timeLabel, dateLabel, offsetLabel, validRange } from './selection-model';
import { Slot } from '../shared/generated';

describe('Civil display dates and advisory selection', () => {
  it('uses the chosen civil zone across year and DST boundaries', () => {
    const instant = new Date('2026-01-01T00:30:00Z');
    expect(civilDate(instant, 'Europe/Brussels')).toBe('2026-01-01');
    expect(civilDate(instant, 'America/New_York')).toBe('2025-12-31');
    expect(addDays('2026-03-28', 2)).toBe('2026-03-30');
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29');
    expect(canonicalZone(' europe/brussels ')).toBe('Europe/Brussels');
    expect(canonicalZone('not-a-zone')).toBeNull();
  });

  it('validates actual calendar dates and an inclusive maximum of31 dates', () => {
    expect(validRange('2026-01-01', '2026-01-31')).toBe(true);
    expect(validRange('2026-01-01', '2026-02-01')).toBe(false);
    expect(validRange('2028-02-29', '2028-02-29')).toBe(true);
    for (const [from, to] of [['', '2026-01-01'], ['2026-02-29', '2026-03-01'], ['2026-04-31', '2026-05-01'], ['2026-01-02', '2026-01-01'], ['2026-01-01', 'bad']])
      expect(validRange(from, to)).toBe(false);
  });

  it('honors Retry-After seconds or HTTP dates and uses a bounded safe fallback', () => {
    const now = Date.parse('2026-11-01T12:00:00Z');
    expect(retryDelay('30', now)).toBe(30); expect(retryDelay('0', now)).toBe(1);
    expect(retryDelay('Sun, 01 Nov 2026 12:01:00 GMT', now)).toBe(60);
    expect(retryDelay('Sun, 01 Nov 2026 11:00:00 GMT', now)).toBe(1);
    expect(retryDelay('9'.repeat(400), now)).toBe(60);
    expect(retryDelay('1' + '0'.repeat(100), now)).toBe(60);
    expect(retryDelay('1000000000000000', now)).toBe(60);
    expect(retryDelay('8639999999000', now)).toBe(60); expect(retryDelay(null, now)).toBe(60); expect(retryDelay('invalid', now)).toBe(60);
  });

  it('renders elapsed consultation duration rather than the reservation length', () => {
    const slot: Slot = { startInstant: '2026-10-25T00:30:00Z', sessionEndInstant: '2026-10-25T01:00:00Z', reservedEndInstant: '2026-10-25T02:30:00Z', businessDate: '2026-10-25', displayLocalTime: '2026-10-25T02:30', offset: '+02:00' };
    expect(slotDuration(slot)).toBe(30); expect(slotLabel(slot, 'Europe/Brussels')).toContain('02:30');
    expect(slotLabel(slot, 'America/New_York')).toContain('20:30');
    expect(timeLabel(slot, 'Europe/Brussels')).toBe('02:30');
    expect(dateLabel('2026-10-25')).toBe('Sunday 25 October');
    expect(offsetLabel('Z')).toBe('UTC+00:00'); expect(offsetLabel('+02:00')).toBe('UTC+02:00');
  });
});
