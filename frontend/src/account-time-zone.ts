export const ACCOUNT_TIME_ZONE = 'America/Sao_Paulo';

export function accountToday(now = new Date()): string {
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: ACCOUNT_TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(now);
}
