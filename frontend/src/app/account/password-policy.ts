import { ValidatorFn } from '@angular/forms';

/** Match the server's15–128 Unicode code points; preserve exact input without trimming or normalization. */
export const passwordPolicy: ValidatorFn = control => {
  const value: unknown = control.value;
  if (typeof value !== 'string' || value.length > 256) return { passwordPolicy: true };
  const characters = Array.from(value);
  if (characters.length < 15 || characters.length > 128) return { passwordPolicy: true };
  for (const character of characters) {
    const point = character.codePointAt(0)!;
    if (point <= 0x1f || (point >= 0x7f && point <= 0x9f) || (point >= 0xd800 && point <= 0xdfff)) return { passwordPolicy: true };
  }
  return null;
};
