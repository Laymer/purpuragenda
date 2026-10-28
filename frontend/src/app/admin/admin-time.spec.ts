import { brusselsInstants, addDays, brusselsDate, brusselsDisplay, parseDay, rangeProblem, weekOf, zoomHint } from './admin-time';

describe('Administrator Brussels civil-date helpers', () => {
  it('derives the Brussels civil date across the midnight and DST boundaries', () => {
    expect(brusselsDate(new Date('2026-10-25T22:30:00Z'))).toBe('2026-10-25');
    expect(brusselsDate(new Date('2026-10-25T23:30:00Z'))).toBe('2026-10-26');
    expect(brusselsDate(new Date('2026-03-28T23:30:00Z'))).toBe('2026-03-29');
  });
  it('validates calendar dates, month ends and leap days', () => {
    expect(parseDay('2026-02-29')).toBeNull(); expect(parseDay('2028-02-29')).not.toBeNull(); expect(parseDay('2026-1-1')).toBeNull();
    expect(addDays('2026-01-31', 1)).toBe('2026-02-01'); expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
    expect(() => addDays('bad', 1)).toThrow(RangeError); expect(() => weekOf('bad')).toThrow(RangeError);
  });
  it('returns the Monday-to-Sunday week for every weekday', () => {
    expect(weekOf('2026-10-26')).toEqual({ from: '2026-10-26', through: '2026-11-01' });
    expect(weekOf('2026-11-01')).toEqual({ from: '2026-10-26', through: '2026-11-01' });
    expect(weekOf('2026-10-29')).toEqual({ from: '2026-10-26', through: '2026-11-01' });
  });
  it('accepts at most thirty-one inclusive dates and explains each failure', () => {
    expect(rangeProblem('2026-10-01', '2026-10-31')).toBeNull(); expect(rangeProblem('2026-10-01', '2026-11-01')).toContain('31');
    expect(rangeProblem('2026-10-02', '2026-10-01')).toContain('before'); expect(rangeProblem('', '2026-10-01')).toContain('valid');
  });
  it('shows the offset so a repeated autumn hour is unambiguous', () => {
    const first = brusselsDisplay('2026-10-25T00:30:00Z'), second = brusselsDisplay('2026-10-25T01:30:00Z');
    expect(first).toContain('02:30'); expect(second).toContain('02:30'); expect(first).toContain('UTC+2'); expect(second).toContain('UTC+1');
    expect(brusselsDisplay('not a time')).toBe('Unknown time');
    expect(first).not.toContain(','); expect(second).not.toContain(',');
  });
  it('labels the spring-forward gap correctly and keeps month-end ranges at thirty-one dates', () => {
    expect(brusselsDisplay('2026-03-29T00:30:00Z')).toContain('01:30'); expect(brusselsDisplay('2026-03-29T00:30:00Z')).toContain('UTC+1');
    expect(brusselsDisplay('2026-03-29T01:00:00Z')).toContain('03:00'); expect(brusselsDisplay('2026-03-29T01:00:00Z')).toContain('UTC+2');
    expect(rangeProblem('2026-03-01', '2026-03-31')).toBeNull(); expect(rangeProblem('2028-02-01', '2028-02-29')).toBeNull();
  });
  it('treats the Zoom link as a hint with strict host and path rules', () => {
    expect(zoomHint('https://zoom.us/j/123456789')).toBeNull(); expect(zoomHint(' https://us02web.zoom.us/j/12345678901/ ')).toBeNull();
    for (const bad of ['', 'zoom.us/j/123456789', 'http://zoom.us/j/123456789', 'https://zoom.us.evil.test/j/123456789', 'https://evilzoom.us/j/123456789', 'https://u:p@zoom.us/j/123456789'])
      expect(zoomHint(bad)).not.toBeNull();
    expect(zoomHint('https://zoom.us/my/room')).toContain('meeting link'); expect(zoomHint('https://zoom.us/j/12')).not.toBeNull();
  });
});

describe('Manual Brussels start selection', () => {
  it('rejects invalid dates, times and spring gaps without normalization', () => {
    for (const [day, time] of [['2027-02-29', '10:00'], ['2027-03-28', '02:30'], ['', '10:00'], ['2027-01-01', '24:00'], ['2027-01-01', '10:60']]) expect(brusselsInstants(day, time)).toEqual([]);
  });
  it('offers both autumn occurrences, in instant order, and ordinary winter/summer times', () => {
    expect(brusselsInstants('2027-10-31', '02:30')).toEqual(['2027-10-31T00:30:00.000Z', '2027-10-31T01:30:00.000Z']);
    expect(brusselsInstants('2027-03-28', '03:00')).toEqual(['2027-03-28T01:00:00.000Z']);
    expect(brusselsInstants('2028-02-29', '00:00')).toEqual(['2028-02-28T23:00:00.000Z']);
  });
});
