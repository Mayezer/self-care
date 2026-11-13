import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { expect, it } from 'vitest';
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
  submit();
  http.expectOne(previewUrl).flush(response('2026-11-09', '2026-11-12'));
  harness.detectChanges();
  thursdayStage().value = 'HYDRATION';
  thursdayStage().dispatchEvent(new Event('change'));
  getPage().querySelector<HTMLButtonElement>('.preview-action')!.click();
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
  expect(getPage().querySelector('[role="alert"]')?.textContent).toContain('Suas escolhas foram mantidas.');

  setInput('#start-date', '2026-11-10');
  submit();
  const corrected = http.expectOne(previewUrl);
  expect(corrected.request.body.startDate).toBe('2026-11-10');
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
