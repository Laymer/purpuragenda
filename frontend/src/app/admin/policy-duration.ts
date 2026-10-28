/** Keep readable input compatible with the HTTP boundary, including normalized durations returned by the server. */
export function policyDuration(input: string): string | null {
  const match = /^(?=\d)(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/.exec(input.trim().toLowerCase().replaceAll(' ', ''));
  if (match === null) return null;
  let remaining = BigInt(match[1] ?? '0') * 3600n + BigInt(match[2] ?? '0') * 60n + BigInt(match[3] ?? '0');
  if (remaining > 9999n * 3600n + 999999n * 60n + 99999999n) return null;
  const hours = remaining / 3600n > 9999n ? 9999n : remaining / 3600n; remaining -= hours * 3600n;
  const minutes = remaining / 60n > 999999n ? 999999n : remaining / 60n; remaining -= minutes * 60n;
  return 'PT' + (hours ? hours + 'H' : '') + (minutes ? minutes + 'M' : '') + (remaining ? remaining + 'S' : hours || minutes ? '' : '0S');
}
