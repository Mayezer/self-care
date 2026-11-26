import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { expect, it, vi } from 'vitest';
import { Agenda } from './agenda';
import { App } from './app';
import { SavedHairPlan } from './saved-hair-plan';
import { HairRoutine, type HairRoutineData } from './hair-routine';

const care: HairRoutineData = {
  modelId: 'daily-hair-care', version: 2, reviewedOn: '2026-11-23', personalized: false,
  summary: 'Rotina geral em cada lavagem.', notes: [], steps: [
    { id: 'cleanse', title: 'Lavar com suavidade', instruction: 'Massageie o couro cabeludo.' },
    { id: 'condition', title: 'Condicionar os fios', instruction: 'Comece pelas pontas.' },
    { id: 'dry', title: 'Retirar o excesso de água', instruction: 'Não esfregue os fios.' },
    { id: 'detangle', title: 'Desembaraçar sem tração', instruction: 'Comece pelas pontas.' },
  ],
};

it('keeps the original explanation attached to a moved occurrence and distinguishes a manually changed stage', () => {
  const fixture = TestBed.createComponent(HairRoutine);
  fixture.componentRef.setInput('routine', { ...care, version: 3, schedule: { sourceRevision: 'a'.repeat(64), notes: [],
    sessions: [{ occurrenceId: 42, date: '2026-11-26', stage: 'HYDRATION', reason: 'Motivo da hidratação original.' }],
  } });
  fixture.componentRef.setInput('occurrenceId', 42);
  fixture.componentRef.setInput('date', '2026-12-01');
  fixture.componentRef.setInput('stage', 'HYDRATION');
  fixture.detectChanges();
  const page: HTMLElement = fixture.nativeElement;
  expect(page.textContent).toContain('Motivo da hidratação original');
  expect(page.textContent).toContain('Esta sessão foi reagendada');
  fixture.componentRef.setInput('stage', 'RECONSTRUCTION');
  fixture.detectChanges();
  expect(page.textContent).toContain('Etapa alterada por você');
  expect(page.textContent).not.toContain('Motivo da hidratação original');
});

it('adopts authenticated suggestions after returning to an unedited draft and invalidates a stale source before saving', async () => {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
    provideRouter([{ path: '', component: App }, { path: 'retomar', component: App }])],
  });
  const harness = await RouterTestingHarness.create('/');
  const http = TestBed.inject(HttpTestingController);
  http.expectOne('/api/health').flush({ status: 'UP' });
  const date = '2026-11-26';
  await TestBed.inject(Router).navigateByUrl('/retomar', { state: { hairPreviewDraft: {
    startDate: date, weeks: 1, weekdays: ['THURSDAY'], occurrences: [{ date, stage: 'WASH_ONLY' }],
    suggestion: { useProfile: true, autoWeekdays: false }, editedDates: [],
  } } });
  http.expectOne('/api/health').flush({ status: 'UP' });
  const revision = 'a'.repeat(64);
  const routine: HairRoutineData = { ...care, version: 3, schedule: { sourceRevision: revision, notes: [],
    sessions: [{ date, stage: 'HYDRATION', reason: 'Ressecamento e intervalo atendidos.' }],
  } };
  http.expectOne('/api/hair-plans/preview').flush({ occurrences: [{ date, stage: 'HYDRATION' }], suggestion: routine, weekdays: ['THURSDAY'] });
  harness.detectChanges();
  const page = harness.routeNativeElement!;
  expect(page.querySelector<HTMLSelectElement>('#stage-' + date)!.value).toBe('HYDRATION');
  expect(page.textContent).toContain('Ressecamento e intervalo atendidos.');
  page.querySelector<HTMLButtonElement>('.preview-action')!.click();
  http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test-only' });
  const save = http.expectOne('/api/hair-plans');
  expect(save.request.body.suggestion.sourceRevision).toBe(revision);
  save.flush({ errors: { suggestion: 'Seu perfil ou sua agenda mudou. Gere a prévia novamente.' } }, { status: 409, statusText: 'Conflict' });
  harness.detectChanges();
  expect(page.querySelector('.preview-action')).toBeNull();
  expect(page.querySelector('[role="alert"]')?.textContent).toContain('Gere a prévia novamente');
  http.verify();
});

it('uses the server suggested weekdays and lets a day edit return to manual dates', async () => {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{ path: '', component: App }])] });
  const harness = await RouterTestingHarness.create('/');
  const http = TestBed.inject(HttpTestingController);
  http.expectOne('/api/health').flush({ status: 'UP' });
  const page = harness.routeNativeElement!;
  const start = page.querySelector<HTMLInputElement>('#start-date')!;
  start.value = '2026-11-26'; start.dispatchEvent(new Event('input'));
  page.querySelectorAll<HTMLInputElement>('[name="planner-mode"]')[1].click();
  harness.detectChanges();
  page.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
  const request = http.expectOne('/api/hair-plans/preview');
  expect(request.request.body.suggestion.autoWeekdays).toBe(true);
  expect(request.request.body.weekdays).toEqual([]);
  request.flush({ weekdays: ['MONDAY', 'THURSDAY', 'SATURDAY'], suggestion: { ...care, version: 3, schedule: {
    sourceRevision: 'a'.repeat(64), notes: [], sessions: [],
  } }, occurrences: [{ date: '2026-11-26', stage: 'HYDRATION' },
    { date: '2026-11-28', stage: 'WASH_ONLY' }, { date: '2026-11-30', stage: 'WASH_ONLY' }] });
  harness.detectChanges();
  const edited = page.querySelector<HTMLSelectElement>('#stage-2026-11-28')!;
  edited.value = 'NUTRITION'; edited.dispatchEvent(new Event('change'));
  const thursday = page.querySelector<HTMLButtonElement>('[aria-label="Qui, Quinta-feira"]')!;
  expect(thursday.getAttribute('aria-pressed')).toBe('true');
  thursday.click(); harness.detectChanges();
  page.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
  const manual = http.expectOne('/api/hair-plans/preview');
  expect(manual.request.body.suggestion.autoWeekdays).toBe(false);
  expect(manual.request.body.weekdays).toEqual(['MONDAY', 'SATURDAY']);
  manual.flush({ occurrences: [{ date: '2026-11-28', stage: 'WASH_ONLY' }], suggestion: care });
  harness.detectChanges();
  expect(page.querySelector<HTMLSelectElement>('#stage-2026-11-28')!.value).toBe('NUTRITION');
  http.verify();
});

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
  const thursdayStage = () => getPage().querySelector<HTMLSelectElement>('#stage-2026-11-12')!;
  const submit = () => getPage().querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
  const response = (...dates: string[]) => ({
    occurrences: dates.map(date => ({ date, stage: 'WASH_ONLY' })), suggestion: care,
  });

  http.expectOne('/api/health').flush({ status: 'UP' });
  setInput('#start-date', '2026-11-09');
  setInput('#weeks', '1');
  monday().click();
  thursday().click();
  getPage().querySelectorAll<HTMLInputElement>('[name="planner-mode"]')[1].click();
  harness.detectChanges();
  submit();
  http.expectOne(previewUrl).flush({
    occurrences: [{ date: '2026-11-09', stage: 'WASH_ONLY' }, { date: '2026-11-12', stage: 'WASH_ONLY' }],
    suggestion: care,
  });
  harness.detectChanges();
  thursdayStage().value = 'HYDRATION';
  thursdayStage().dispatchEvent(new Event('change'));
  getPage().querySelector<HTMLButtonElement>('.preview-action')!.click();
  http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'visitor' });
  const beforeLogin = http.expectOne('/api/hair-plans');
  expect(beforeLogin.request.body.suggestion).toEqual({ useProfile: true, autoWeekdays: false });
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
  http.expectOne('/api/agenda').flush({ email: 'teste@example.test', username: null, timeZone: 'America/Sao_Paulo' });
  await harness.fixture.whenStable();
  http.expectOne('/api/health').flush({ status: 'UP' });
  const resumed = http.expectOne(previewUrl);
  expect(resumed.request.body).toMatchObject({
    startDate: '2026-11-09', weeks: 1, weekdays: ['MONDAY', 'THURSDAY'],
  });
  expect(resumed.request.body.suggestion).toEqual({ useProfile: true, autoWeekdays: false });
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
  expect(getPage().querySelector<HTMLInputElement>('input[aria-describedby="planner-profile-hint"]')!.checked).toBe(true);
  expect(getPage().querySelector('[role="alert"]')?.textContent).toContain('Suas escolhas foram mantidas.');

  setInput('#start-date', '2026-11-10');
  submit();
  const corrected = http.expectOne(previewUrl);
  expect(corrected.request.body.startDate).toBe('2026-11-10');
  expect(corrected.request.body.suggestion).toEqual({ useProfile: true, autoWeekdays: false });
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
  http.expectOne('/api/hair-plans/8').flush({ occurrences: first.request.body.occurrences, routine: care });
  harness.detectChanges();
  expect(getPage().querySelector('h1')?.textContent).toBe('Cronograma salvo');
  expect(getPage().textContent).toContain('Hidratação');
  expect(getPage().textContent).toContain(care.summary);
  expect(getPage().textContent).toContain('Condicionar os fios');
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
  const profile = page.querySelector<HTMLInputElement>('input[aria-describedby="planner-profile-hint"]')!;
  submit();
  respond('WASH_ONLY', [date]);
  harness.detectChanges();
  choose(stage(), 'HYDRATION');
  weekdays.slice(1).forEach(day => day.click());
  submit();
  respond('WASH_ONLY', week);
  harness.detectChanges();
  expect(dialog.showModal).not.toHaveBeenCalled();
  expect(page.querySelectorAll('.hair-session')).toHaveLength(7);
  expect(stage().value).toBe('HYDRATION');

  profile.click();
  harness.detectChanges();
  submit();
  expect(dialog.showModal).toHaveBeenCalledOnce();
  http.expectNone(preview);
  dialog.querySelector<HTMLButtonElement>('.secondary')!.click();
  harness.detectChanges();
  expect(stage().value).toBe('HYDRATION');
  expect(profile.checked).toBe(true);
  expect(page.querySelector('.preview-action')?.textContent).toContain('Salvar cronograma');

  profile.click();
  submit();
  const replace = () => dialog.querySelector<HTMLButtonElement>('.primary')!.click();
  replace();
  http.expectOne(preview).flush(null, { status: 503, statusText: 'Service Unavailable' });
  harness.detectChanges();
  expect(stage().value).toBe('HYDRATION');
  submit();
  expect(dialog.showModal).toHaveBeenCalledTimes(3);
  replace();
  respond('WASH_ONLY', week);
  harness.detectChanges();
  expect(stage().value).toBe('WASH_ONLY');
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
  submit();
  const suggested = http.expectOne(preview);
  expect(suggested.request.body).toEqual({ ...manual.request.body, suggestion: { useProfile: false, autoWeekdays: false } });
  suggested.flush({
    occurrences: [{ date, stage: 'WASH_ONLY' }],
    suggestion: care,
  });
  harness.detectChanges();
  expect(stage().value).toBe('WASH_ONLY');
  expect(page.querySelector('.planner-suggestion-status')?.textContent).toContain(care.summary);

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
