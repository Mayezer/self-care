import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, expect, it } from 'vitest';
import { AgendaWeek } from './agenda-week';
import { History } from './history';

const today = '2026-11-09';
const categories = ['HAIR', 'SKIN', 'MAKEUP', 'NAILS'];
const cares = [
  { id: 1, category: 'HAIR', title: 'Lavagem' },
  { id: 2, category: 'SKIN', title: 'Rotina da manhã', period: 'MORNING' },
  { id: 3, category: 'MAKEUP', title: 'Maquiagem leve' },
  { id: 4, category: 'NAILS', title: 'Trocar esmalte' },
];

let http: HttpTestingController;

beforeEach(() => {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
  });
  http = TestBed.inject(HttpTestingController);
});

afterEach(() => http.verify());

function selectCategory<T>(fixture: ComponentFixture<T>, value: string): void {
  const page: HTMLElement = fixture.nativeElement;
  const radio = page.querySelector<HTMLInputElement>(`input[type="radio"][value="${value}"]`)!;
  radio.click();
  fixture.detectChanges();
  expect(radio.checked).toBe(true);
}

it('filters the four categories on the same day while preserving the weekly summary and next care', () => {
  const fixture = TestBed.createComponent(AgendaWeek);
  const page: HTMLElement = fixture.nativeElement;
  const items = cares.map(care => ({ ...care, date: today, status: 'PLANNED', canReschedule: true }));
  http.expectOne('/api/agenda/week').flush({
    today,
    days: ['09', '10', '11', '12', '13', '14', '15'].map(day => `2026-11-${day}`),
    items,
    overdue: [{ ...items[0], id: 5, date: '2026-11-08', title: 'Lavagem em atraso' }],
    nextCare: items[1],
    completedCount: 2,
    careStreakDays: 3,
  });
  http.expectOne('/api/agenda/daily-message').flush(null, { status: 204, statusText: 'No Content' });
  fixture.detectChanges();

  const visibleCategories = () => Array.from(page.querySelectorAll('.agenda-day .care-category-badge'),
    badge => badge.getAttribute('data-category'));
  const summary = page.querySelector('.week-summary')?.textContent;
  expect(summary).toContain('2 cuidados concluídos');
  expect(summary).toContain('4 cuidados planejados');
  expect(summary).toContain('3 dias seguidos');
  expect(visibleCategories()).toHaveLength(4);
  expect(visibleCategories()).toEqual(expect.arrayContaining(categories));

  for (const category of categories) {
    selectCategory(fixture, category);
    expect(visibleCategories()).toEqual([category]);
    expect(Boolean(page.querySelector('.overdue-panel'))).toBe(category === 'HAIR');
    expect(page.querySelector('.next-care .featured-title')?.textContent).toBe('Rotina da manhã');
    expect(page.querySelector('.week-summary')?.textContent).toBe(summary);
  }
  selectCategory(fixture, '');
  expect(visibleCategories()).toHaveLength(4);
});

it('shows a welcoming empty streak and updates it after loading another week', () => {
  const fixture = TestBed.createComponent(AgendaWeek);
  const page: HTMLElement = fixture.nativeElement;
  const week = {
    today,
    days: [today],
    previousWeek: '2026-11-02',
    nextWeek: null,
    items: [],
    overdue: [],
    nextCare: null,
    completedCount: 0,
  };
  http.expectOne('/api/agenda/week').flush({ ...week, careStreakDays: 0 });
  http.expectOne('/api/agenda/daily-message').flush(null, { status: 204, statusText: 'No Content' });
  fixture.detectChanges();

  const streak = () => page.querySelector('.care-streak');
  expect(streak()?.querySelector('[role="status"]')?.textContent).toContain('0 dias seguidos');
  expect(streak()?.textContent).toContain('Comece no seu ritmo.');
  expect(streak()?.textContent).toContain('Qualquer categoria conta.');

  page.querySelector<HTMLButtonElement>('[aria-label="Semana anterior"]')!.click();
  http.expectOne('/api/agenda/week?date=2026-11-02').flush({ ...week, days: ['2026-11-02'], careStreakDays: 1 });
  fixture.detectChanges();
  expect(streak()?.querySelector('[role="status"]')?.textContent).toContain('1 dia seguido');

  page.querySelector<HTMLButtonElement>('.week-card .text-button')!.click();
  http.expectOne('/api/agenda/week').flush({ ...week, careStreakDays: null });
  fixture.detectChanges();
  expect(streak()).toBeNull();
  expect(page.querySelector('.week-summary')?.textContent).toContain('0 cuidados concluídos');
});

it('keeps future care planned and makes completion available after rescheduling to the account day', () => {
  const fixture = TestBed.createComponent(AgendaWeek);
  const page: HTMLElement = fixture.nativeElement;
  const friday = '2026-11-13';
  const sunday = '2026-11-15';
  const first = { ...cares[0], date: sunday, status: 'PLANNED', canReschedule: true };
  const second = { ...cares[1], date: sunday, status: 'PLANNED', canReschedule: true };
  const overdue = { ...cares[2], date: '2026-11-12', status: 'PLANNED', canReschedule: true };
  const week = {
    today: friday,
    days: ['09', '10', '11', '12', '13', '14', '15'].map(day => `2026-11-${day}`),
    items: [first, second],
    overdue: [overdue],
    nextCare: first,
    completedCount: 0,
  };
  http.expectOne('/api/agenda/week').flush(week);
  http.expectOne('/api/agenda/daily-message').flush(null, { status: 204, statusText: 'No Content' });
  fixture.detectChanges();
  page.querySelector<HTMLButtonElement>(`[aria-describedby="calendar-date-${sunday}"]`)!.click();
  fixture.detectChanges();

  expect(page.querySelector('.agenda-day [data-status="PLANNED"]')?.textContent).toContain('Planejada');
  for (const area of ['.next-care', '.agenda-day']) {
    const complete = page.querySelector<HTMLButtonElement>(`${area} .complete-action`)!;
    expect(complete.disabled).toBe(true);
    expect(page.querySelector(`#${complete.getAttribute('aria-describedby')}`)?.textContent)
      .toContain('a partir da data planejada');
    complete.click();
  }
  fixture.detectChanges();
  expect(page.querySelector('input[name="completedOn"]')).toBeNull();
  http.expectNone('/api/auth/csrf');
  expect(page.querySelector<HTMLButtonElement>('.overdue-panel .complete-action')?.disabled).toBe(false);

  page.querySelector<HTMLButtonElement>('.next-care .reschedule-action')!.click();
  fixture.detectChanges();
  const newDate = page.querySelector<HTMLInputElement>('input[name="plannedDate"]')!;
  expect(newDate.min).toBe(friday);
  newDate.value = friday;
  newDate.form!.dispatchEvent(new Event('submit', { cancelable: true }));
  http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test-token' });
  const move = http.expectOne(`/api/occurrences/${first.id}/reschedule`);
  expect(move.request.body).toEqual({ plannedDate: friday });
  move.flush(null, { status: 204, statusText: 'No Content' });
  const moved = { ...first, date: friday };
  http.expectOne(`/api/agenda/week?date=${friday}`).flush({ ...week, items: [moved, second], nextCare: moved });
  fixture.detectChanges();

  const complete = page.querySelector<HTMLButtonElement>('.next-care .complete-action')!;
  expect(complete.disabled).toBe(false);
  complete.click();
  fixture.detectChanges();
  const completedOn = page.querySelector<HTMLInputElement>('input[name="completedOn"]')!;
  expect(completedOn.value).toBe(friday);
  expect(completedOn.max).toBe(friday);
});

it('shows the daily message independently of week navigation and category filters', () => {
  const fixture = TestBed.createComponent(AgendaWeek);
  const page: HTMLElement = fixture.nativeElement;
  const week = {
    today,
    days: [today],
    previousWeek: '2026-11-02',
    items: [],
    overdue: [],
    completedCount: 0,
  };
  http.expectOne('/api/agenda/week').flush(week);
  http.expectOne('/api/agenda/daily-message').flush({
    text: 'Lembre-se do protetor solar na rotina do dia.',
    category: 'SKIN',
  });
  fixture.detectChanges();

  const card = () => page.querySelector('.daily-message');
  expect(card()?.querySelector('h2')?.textContent).toBe('Mensagem do dia');
  expect(card()?.querySelector('[data-category="SKIN"]')?.textContent).toBe('Pele');
  expect(card()?.querySelector('p')?.textContent).toBe('Lembre-se do protetor solar na rotina do dia.');

  selectCategory(fixture, 'HAIR');
  page.querySelector<HTMLButtonElement>('[aria-label="Semana anterior"]')!.click();
  http.expectOne('/api/agenda/week?date=2026-11-02').flush({ ...week, days: ['2026-11-02'] });
  fixture.detectChanges();
  http.expectNone('/api/agenda/daily-message');
  expect(card()?.querySelector('[data-category="SKIN"]')?.textContent).toBe('Pele');

  page.querySelector<HTMLButtonElement>('.week-card .text-button')!.click();
  http.expectOne('/api/agenda/week').flush({ ...week, today: '2026-11-10', days: [today, '2026-11-10'] });
  http.expectOne('/api/agenda/daily-message').flush({ text: 'Vá no seu ritmo.', category: null });
  fixture.detectChanges();
  expect(card()?.querySelector('p')?.textContent).toBe('Vá no seu ritmo.');
  expect(card()?.querySelector('.care-category-badge')).toBeNull();
});

it('keeps the category filter selected after undoing a care record from history', () => {
  const fixture = TestBed.createComponent(History);
  fixture.componentRef.setInput('today', today);
  const page: HTMLElement = fixture.nativeElement;
  const records = cares.map(care => ({ ...care, actionDate: today, status: 'COMPLETED', canUndo: true }));
  http.expectOne('/api/history').flush(records);
  fixture.detectChanges();

  const visibleCategories = () => Array.from(page.querySelectorAll('.history-entry'),
    entry => entry.getAttribute('data-category'));
  expect(visibleCategories()).toEqual(categories);
  for (const category of categories) {
    selectCategory(fixture, category);
    expect(visibleCategories()).toEqual([category]);
  }

  page.querySelector<HTMLButtonElement>('.history-entry .undo-action')!.click();
  http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test-token' });
  const undo = http.expectOne('/api/occurrences/4/undo');
  expect(undo.request.method).toBe('POST');
  undo.flush(null, { status: 204, statusText: 'No Content' });
  http.expectOne('/api/history').flush(records.slice(0, -1));
  fixture.detectChanges();

  expect(page.querySelector<HTMLInputElement>('input[value="NAILS"]')?.checked).toBe(true);
  expect(visibleCategories()).toEqual([]);
  expect(page.querySelector('.empty-state')?.textContent).toContain('nesta categoria');
  selectCategory(fixture, '');
  expect(visibleCategories()).toEqual(['HAIR', 'SKIN', 'MAKEUP']);
});
