import { expect, it } from 'vitest';
import { accountToday } from './account-time-zone';

it('changes the civil date only at midnight in Sao Paulo regardless of the browser zone', () => {
  expect(accountToday(new Date('2026-11-24T02:59:59Z'))).toBe('2026-11-23');
  expect(accountToday(new Date('2026-11-24T03:00:00Z'))).toBe('2026-11-24');
});
