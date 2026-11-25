import { expect, it } from 'vitest';
import { PASSWORD_PATTERN } from './password-rules';

it('validates the required composition and Unicode length using the browser input pattern', () => {
  const field = document.createElement('input');
  field.type = 'password';
  field.required = true;
  field.pattern = PASSWORD_PATTERN;
  for (const value of ['Aa1!bcde', 'Água7€xy', 'Aa1🙂bcde', 'Aa1!' + '🙂'.repeat(124)]) {
    field.value = value;
    expect(field.checkValidity(), value).toBe(true);
  }
  for (const value of ['', 'Ab1!abc', 'abcdef1!', 'ABCDEFG1!', 'Abcdefg!', 'Abcdefg1', 'Abcd123 ',
    'Abcdefg1\u0301', 'Aa1!' + '🙂'.repeat(125)]) {
    field.value = value;
    expect(field.checkValidity(), value).toBe(false);
  }
});
