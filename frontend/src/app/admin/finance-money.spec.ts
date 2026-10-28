import { currencyDigits, displayMoney, minorAmount } from './finance-money';

describe('Exact external-money input', () => {
  it('converts decimals exactly for zero, two and three fraction currencies', () => {
    expect(minorAmount(' 0.29 ', 'EUR')).toBe(29); expect(minorAmount('12', 'JPY')).toBe(12); expect(minorAmount('1.234', 'KWD')).toBe(1234); expect(minorAmount('1.2', 'EUR')).toBe(120);
    expect(currencyDigits('EUR')).toBe(2); expect(currencyDigits('JPY')).toBe(0);
  });
  it('rejects rounding, negative/zero amounts, grouping, exponent and unsafe integers', () => {
    for (const value of ['', '0', '-1', '01', '1,000', '1e2', '1.001', '90071992547409.92']) expect(minorAmount(value, 'EUR')).toBeNull();
    expect(minorAmount('1.1', 'JPY')).toBeNull(); expect(minorAmount('1', 'bad')).toBeNull();
    expect(minorAmount('90071992547409.91', 'EUR')).toBe(Number.MAX_SAFE_INTEGER);
  });
  it('labels original currencies and refuses unsafe values', () => {
    expect(displayMoney(Number.MAX_SAFE_INTEGER, 'EUR')).toBe('EUR 90,071,992,547,409.91'); expect(displayMoney(Number.MAX_SAFE_INTEGER, 'KWD')).toBe('KWD 9,007,199,254,740.991'); expect(displayMoney(-29, 'EUR')).toBe('EUR -0.29');
    expect(displayMoney(29, 'EUR')).toContain('0.29'); expect(displayMoney(12, 'JPY')).toContain('12'); expect(displayMoney(1234, 'KWD')).toContain('1.234');
    expect(displayMoney(1, 'bad')).toBe('Amount unavailable'); expect(displayMoney(Number.MAX_SAFE_INTEGER + 1, 'EUR')).toBe('Amount unavailable');
  });
});
