import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { expect, it, vi } from 'vitest';
import { Agenda } from './agenda';
import { App } from './app';
import { SavedHairPlan } from './saved-hair-plan';

it('resumes the draft, corrects the date and safely retries saving the chosen stages', async () => {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([
        { path: '', component: App },
        { path: 'agenda', component: Agenda },
        { path: 'cronogramas/:planId', component: SavedHairPlan },
      ]),
    ],
  });
  const http = TestBed.inject(HttpTestingController);
  const harness = await RouterTestingHarness.create('/');
  const previewUrl = '/api/hair-plans/preview';
  const getPage = () => harness.routeNativeElement!;
  const input = (id: string) => getPage().querySelector<HTMLInputElement>(id)!;
  const setInput = (id: string, value: string) => {
    input(id).value = value;
    input(id).dispatchEvent(new Event('input'));
  };
  const monday = () => getPage().querySelector<HTMLButtonElement>('[aria-label="Seg, Segunda-feira"]')!;
  const thursday = () => getPage().querySelector<HTMLButtonElement>('[aria-label="Qui, Quinta-feira"]')!;
  const goal = () => getPage().querySelector<HTMLSelectElement>('#hair-goal')!;
  const thursdayStage = () => getPage().querySelector<HTMLSelectElement>('#stage-2026-11-12')!;
  const submit = () => getPage().querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
  const response = (...dates: string[]) => ({
    occurrences: dates.map(date => ({ date, stage: 'WASH_ONLY' })),
  });

  http.expectOne('/api/health').flush({ status: 'UP' });
  setInput('#start-date', '2026-11-09');
  setInput('#weeks', '1');
  monday().click();
  thursday().click();
  getPage().querySelectorAll<HTMLInputElement>('[name="planner-mode"]')[1].click();
  harness.detectChanges();
  goal().value = 'HYDRATION';
  goal().dispatchEvent(new Event('change'));
  submit();
  http.expectOne(previewUrl).flush({
    occurrences: [{ date: '2026-11-09', stage: 'HYDRATION' }, { date: '2026-11-12', stage: 'WASH_ONLY' }],
    suggestion: { reason: 'Você escolheu incluir uma sessão de hidratação.' },
  });
  harness.detectChanges();
  thursdayStage().value = 'HYDRATION';
  thursdayStage().dispatchEvent(new Event('change'));
  getPage().querySelector<HTMLButtonElement>('.preview-action')!.click();
  http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'visitor' });
  const beforeLogin = http.expectOne('/api/hair-plans');
  expect(beforeLogin.request.body.suggestion).toBeUndefined();
  beforeLogin.flush(null, { status: 401, statusText: 'Unauthorized' });
  await harness.fixture.whenStable();
  http.expectOne('/api/agenda').flush(null, { status: 401, statusText: 'Unauthorized' });
  harness.detectChanges();
  expect(getPage().querySelector('h1')?.textContent).toBe('Entre para retomar a prévia');

  setInput('#account-email', 'teste@example.test');
  setInput('#account-password', 'Senha de teste 123!');
  submit();
  http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test-token' });
  http.expectOne('/api/auth/login').flush({ mfa: 'verify', needsEmail: false }, { status: 202, statusText: 'Accepted' });
  http.expectNone('/api/agenda');
  harness.detectChanges();
  expect(getPage().textContent).toContain('Verificação em duas etapas');
  setInput('#identity-verify-code', '123456');
  submit();
  http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'mfa-test-token' });
  http.expectOne('/api/auth/mfa/verify').flush(null, { status: 204, statusText: 'No Content' });
  http.expectOne('/api/agenda').flush({ email: 'teste@example.test', username: null, timeZone: 'UTC' });
  await harness.fixture.whenStable();
  http.expectOne('/api/health').flush({ status: 'UP' });
  const resumed = http.expectOne(previewUrl);
  expect(resumed.request.body).toMatchObject({
    startDate: '2026-11-09', weeks: 1, weekdays: ['MONDAY', 'THURSDAY'],
  });
  expect(resumed.request.body.suggestion).toBeUndefined();
  resumed.flush(
    { errors: { startDate: 'Escolha a data de hoje ou uma data futura.' } },
    { status: 400, statusText: 'Bad Request' },
  );
  harness.detectChanges();

  expect(input('#start-date').value).toBe('2026-11-09');
  expect(input('#start-date').getAttribute('aria-invalid')).toBe('true');
  expect(input('#weeks').value).toBe('1');
  expect(monday().getAttribute('aria-pressed')).toBe('true');
  expect(thursday().getAttribute('aria-pressed')).toBe('true');
  expect(thursdayStage().value).toBe('HYDRATION');
  expect(goal().value).toBe('HYDRATION');
  expect(getPage().querySelector('[role="alert"]')?.textContent).toContain('Suas escolhas foram mantidas.');

  setInput('#start-date', '2026-11-10');
  submit();
  const corrected = http.expectOne(previewUrl);
  expect(corrected.request.body.startDate).toBe('2026-11-10');
  expect(corrected.request.body.suggestion).toEqual({ useProfile: true, goal: 'HYDRATION' });
  corrected.flush(response('2026-11-12', '2026-11-16'));
  harness.detectChanges();
  expect(getPage().querySelector('#stage-2026-11-09')).toBeNull();
  expect(thursdayStage().value).toBe('HYDRATION');
  const saveButton = () => getPage().querySelector<HTMLButtonElement>('.preview-action')!;
  expect(saveButton().textContent).toContain('Salvar cronograma');

  saveButton().click();
  saveButton().click();
  http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'first' });
  const first = http.expectOne('/api/hair-plans');
  expect(first.request.headers.get('X-CSRF-TOKEN')).toBe('first');
  expect(first.request.body.requestId).not.toBe(beforeLogin.request.body.requestId);
  expect(first.request.body).toMatchObject({
    startDate: '2026-11-10',
    weeks: 1,
    weekdays: ['MONDAY', 'THURSDAY'],
    occurrences: [
      { date: '2026-11-12', stage: 'HYDRATION' },
      { date: '2026-11-16', stage: 'WASH_ONLY' },
    ],
  });
  first.error(new ProgressEvent('error'));
  harness.detectChanges();
  expect(getPage().querySelector('[role="alert"]')?.textContent).toContain('Tente novamente.');
  expect(thursdayStage().value).toBe('HYDRATION');

  saveButton().click();
  http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'second' });
  const retry = http.expectOne('/api/hair-plans');
  expect(retry.request.body).toEqual(first.request.body);
  retry.flush({ planId: 8 }, { status: 201, statusText: 'Created' });
  await harness.fixture.whenStable();
  http.expectOne('/api/hair-plans/8').flush({ occurrences: first.request.body.occurrences });
  harness.detectChanges();
  expect(getPage().querySelector('h1')?.textContent).toBe('Cronograma salvo');
  expect(getPage().textContent).toContain('Hidratação');
  http.verify();
});

it('keeps overlapping edits after a date change and replaces them only after a confirmed preview succeeds', async () => {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{ path: '', component: App }])],
  });
  const harness = await RouterTestingHarness.create('/');
  const http = TestBed.inject(HttpTestingController);
  const page = harness.routeNativeElement!;
  const preview = '/api/hair-plans/preview';
  const submit = () => page.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
  const dialog = page.querySelector('dialog')!;
  // jsdom does not implement the native modal methods.
  dialog.showModal = vi.fn();
  dialog.close = vi.fn();
  const date = '2026-11-16';
  const week = [date, '2026-11-17', '2026-11-18', '2026-11-19', '2026-11-20', '2026-11-21', '2026-11-22'];
  const stage = () => page.querySelector<HTMLSelectElement>(`#stage-${date}`)!;
  const choose = (field: HTMLSelectElement, value: string) => {
    field.value = value;
    field.dispatchEvent(new Event('change'));
  };
  const respond = (first: string, dates: string[]) => http.expectOne(preview).flush({
    occurrences: dates.map((date, index) => ({ date, stage: index === 0 ? first : 'WASH_ONLY' })),
  });

  http.expectOne('/api/health').flush({ status: 'UP' });
  const start = page.querySelector<HTMLInputElement>('#start-date')!;
  start.value = date;
  start.dispatchEvent(new Event('input'));
  const weeks = page.querySelector<HTMLInputElement>('#weeks')!;
  weeks.value = '1';
  weeks.dispatchEvent(new Event('input'));
  const weekdays = [...page.querySelectorAll<HTMLButtonElement>('.weekdays button')];
  weekdays[0].click();
  page.querySelectorAll<HTMLInputElement>('[name="planner-mode"]')[1].click();
  harness.detectChanges();
  const goal = page.querySelector<HTMLSelectElement>('#hair-goal')!;
  choose(goal, 'HYDRATION');
  submit();
  respond('HYDRATION', [date]);
  harness.detectChanges();
  choose(stage(), 'WASH_ONLY');
  weekdays.slice(1).forEach(day => day.click());
  submit();
  respond('HYDRATION', week);
  harness.detectChanges();
  expect(dialog.showModal).not.toHaveBeenCalled();
  expect(page.querySelectorAll('.hair-session')).toHaveLength(7);
  expect(stage().value).toBe('WASH_ONLY');

  choose(goal, 'NUTRITION');
  harness.detectChanges();
  submit();
  expect(dialog.showModal).toHaveBeenCalledOnce();
  http.expectNone(preview);
  dialog.querySelector<HTMLButtonElement>('.secondary')!.click();
  harness.detectChanges();
  expect(stage().value).toBe('WASH_ONLY');
  expect(goal.value).toBe('HYDRATION');
  expect(page.querySelector('.preview-action')?.textContent).toContain('Salvar cronograma');

  choose(goal, 'NUTRITION');
  submit();
  const replace = () => dialog.querySelector<HTMLButtonElement>('.primary')!.click();
  replace();
  http.expectOne(preview).flush(null, { status: 503, statusText: 'Service Unavailable' });
  harness.detectChanges();
  expect(stage().value).toBe('WASH_ONLY');
  submit();
  expect(dialog.showModal).toHaveBeenCalledTimes(3);
  replace();
  respond('NUTRITION', week);
  harness.detectChanges();
  expect(stage().value).toBe('NUTRITION');
  http.verify();
});

it('uses the same dates for either mode and keeps a stage explicitly edited by the user', async () => {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{ path: '', component: App }])],
  });
  const harness = await RouterTestingHarness.create('/');
  const http = TestBed.inject(HttpTestingController);
  const page = harness.routeNativeElement!;
  const preview = '/api/hair-plans/preview';
  const submit = () => page.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
  const chooseMode = (index: number) => {
    page.querySelectorAll<HTMLInputElement>('[name="planner-mode"]')[index].click();
    harness.detectChanges();
  };
  const date = '2026-11-16';
  const stage = () => page.querySelector<HTMLSelectElement>(`#stage-${date}`)!;

  http.expectOne('/api/health').flush({ status: 'UP' });
  page.querySelector<HTMLButtonElement>('[aria-label="Seg, Segunda-feira"]')!.click();
  submit();
  const manual = http.expectOne(preview);
  expect(manual.request.body.suggestion).toBeUndefined();
  manual.flush({ occurrences: [{ date, stage: 'WASH_ONLY' }] });
  harness.detectChanges();
  expect(stage().value).toBe('WASH_ONLY');

  chooseMode(1);
  page.querySelector<HTMLInputElement>('input[aria-describedby="planner-profile-hint"]')!.click();
  const goal = page.querySelector<HTMLSelectElement>('#hair-goal')!;
  goal.value = 'HYDRATION';
  goal.dispatchEvent(new Event('change'));
  submit();
  const suggested = http.expectOne(preview);
  expect(suggested.request.body).toEqual({ ...manual.request.body, suggestion: { useProfile: false, goal: 'HYDRATION' } });
  suggested.flush({
    occurrences: [{ date, stage: 'HYDRATION' }],
    suggestion: { reason: 'Você escolheu incluir uma sessão de hidratação.' },
  });
  harness.detectChanges();
  expect(stage().value).toBe('HYDRATION');
  expect(page.querySelector('.planner-suggestion-status')?.textContent).toContain('sessão de hidratação');

  stage().value = 'NUTRITION';
  stage().dispatchEvent(new Event('change'));
  chooseMode(0);
  submit();
  const manualAgain = http.expectOne(preview);
  expect(manualAgain.request.body).toEqual(manual.request.body);
  manualAgain.flush({ occurrences: [{ date, stage: 'WASH_ONLY' }] });
  harness.detectChanges();
  expect(stage().value).toBe('NUTRITION');
  expect(page.querySelector('.planner-suggestion-status')?.textContent?.trim()).toBe('');
  http.verify();
});
