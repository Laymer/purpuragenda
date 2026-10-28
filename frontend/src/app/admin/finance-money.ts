/** Decimal entry is converted with integer arithmetic; never round actual-money facts. */
export function currencyDigits(currency: string): number | null {
  if (!/^[A-Z]{3}$/.test(currency)) return null;
  return new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits ?? 2;
}
export function minorAmount(value: string, currency: string): number | null {
  const digits = currencyDigits(currency), match = /^(0|[1-9]\d*)(?:\.(\d+))?$/.exec(value.trim());
  if (digits === null || match === null || (match[2]?.length ?? 0) > digits) return null;
  const amount = BigInt(match[1]) * 10n ** BigInt(digits) + BigInt((match[2] ?? '').padEnd(digits, '0') || '0');
  return amount > 0n && amount <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(amount) : null;
}
export function displayMoney(minor: number, currency: string): string {
  const digits = currencyDigits(currency);
  if (digits === null || !Number.isSafeInteger(minor)) return 'Amount unavailable';
  const amount = BigInt(Math.abs(minor)), scale = 10n ** BigInt(digits);
  const whole = new Intl.NumberFormat('en').format(amount / scale);
  return currency + ' ' + (minor < 0 ? '-' : '') + whole + (digits ? '.' + (amount % scale).toString().padStart(digits, '0') : '');
}
