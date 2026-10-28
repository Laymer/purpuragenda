import { FormControl } from '@angular/forms';
import { passwordPolicy } from './password-policy';

describe('Account password policy matches server code points', () => {
  it.each([
    { label: 'minimum ASCII', value: 'a'.repeat(15), valid: true },
    { label: 'maximum ASCII', value: 'a'.repeat(128), valid: true },
    { label: 'minimum supplementary points', value: '😀'.repeat(15), valid: true },
    { label: 'maximum supplementary points', value: '😀'.repeat(128), valid: true },
    { label: 'exact spaces preserved', value: ' '.repeat(15), valid: true },
    { label: 'below minimum points despite enough UTF16 units', value: '😀'.repeat(8), valid: false },
    { label: 'above point maximum', value: 'a'.repeat(129), valid: false },
    { label: 'above storage unit maximum', value: '😀'.repeat(129), valid: false },
    { label: 'control zero', value: 'a'.repeat(15) + '\0', valid: false },
    { label: 'control delete', value: 'a'.repeat(15) + '\x7f', valid: false },
    { label: 'control C1', value: 'a'.repeat(15) + '\x85', valid: false },
    { label: 'unpaired high surrogate', value: 'a'.repeat(15) + '\ud800', valid: false },
    { label: 'unpaired low surrogate', value: 'a'.repeat(15) + '\udc00', valid: false },
    { label: 'empty', value: '', valid: false },
    { label: 'wrong type', value: 17, valid: false },
  ])('$label', ({ value, valid }) => { expect(passwordPolicy(new FormControl(value)) === null).toBe(valid); });
});
