import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, expect, it } from 'vitest';
import { HairProfile } from './hair-profile';

const url = '/api/hair-profile';
let http: HttpTestingController;

beforeEach(() => {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
  });
  http = TestBed.inject(HttpTestingController);
});

afterEach(() => http.verify());

function render(answers: object = {}) {
  const fixture = TestBed.createComponent(HairProfile);
  fixture.componentRef.setInput('today', '2026-11-09');
  fixture.componentRef.setInput('guidesAvailable', true);
  http.expectOne(url).flush(answers);
  fixture.detectChanges();
  const page: HTMLElement = fixture.nativeElement;
  return {
    page,
    choose(name: string) {
      const label = Array.from(page.querySelectorAll('label')).find(label => label.textContent?.trim() === name)!;
      label.querySelector('input')!.click();
      fixture.detectChanges();
    },
    set(selector: string, value: string) {
      const field = page.querySelector<HTMLInputElement | HTMLSelectElement>(selector)!;
      field.value = value;
      field.dispatchEvent(new Event(field instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
      fixture.detectChanges();
    },
    click(name: string) {
      Array.from(page.querySelectorAll('button')).find(button => button.textContent?.trim() === name)!.click();
      fixture.detectChanges();
    },
    update() {
      fixture.detectChanges();
    },
  };
}

function request() {
  http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test-token' });
  const request = http.expectOne({ method: 'PUT', url });
  expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('test-token');
  return request;
}

it('displays the saved comfortable frequency on the first render', () => {
  const form = render({ careSchedule: { washesPerWeek: 3, scalpSymptoms: 'NO' } });
  expect(form.page.querySelector<HTMLSelectElement>('#care-washes')!.value).toBe('3');
});

it('saves comfortable frequency and explicit care history without treating unknown or a reaction as permission', () => {
  const form = render();
  expect(form.page.querySelector<HTMLSelectElement>('#care-symptoms')!.value).toBe('UNKNOWN');
  form.set('#care-washes', '3');
  form.set('#care-symptoms', 'NO');
  form.set('#care-status-HYDRATION', 'KNOWN');
  form.set('#care-last-HYDRATION', '2026-11-05');
  form.set('#care-status-RECONSTRUCTION', 'FIRST_USE');
  form.set('#care-unit-RECONSTRUCTION', 'MONTHS');
  form.set('#care-every-RECONSTRUCTION', '2');
  form.set('#care-status-NUTRITION', 'AVOID');
  form.click('Salvar respostas');
  const write = request();
  expect(JSON.parse(write.request.serializeBody() as string).careSchedule).toEqual({
    washesPerWeek: 3, scalpSymptoms: 'NO', treatments: {
      HYDRATION: { status: 'KNOWN', lastUse: '2026-11-05' },
      RECONSTRUCTION: { status: 'FIRST_USE', unit: 'MONTHS', every: 2 }, NUTRITION: { status: 'AVOID' },
    },
  });
  write.flush(write.request.body);
  form.update();
  expect(form.page.querySelector<HTMLSelectElement>('#care-unit-RECONSTRUCTION')!.value).toBe('MONTHS');
});

it('does not silently replace an invalid numeric interval with the general hydration default', () => {
  const form = render({ careSchedule: { scalpSymptoms: 'NO', treatments: { HYDRATION: { status: 'FIRST_USE' } } } });
  const every = form.page.querySelector<HTMLInputElement>('#care-every-HYDRATION')!;
  Object.defineProperty(every, 'validity', { value: { badInput: true } });
  form.click('Salvar respostas');
  http.expectNone('/api/auth/csrf');
  expect(form.page.textContent).toContain('Confira a data ou o intervalo digitado');
});

it('saves up to two distinct patterns with subtypes scoped to each group', () => {
  const form = render();
  expect(form.page.querySelector('.profile-state a[href="/agenda"]')?.textContent).toBe('Pular e ir à agenda');
  form.choose('Mais de um padrão');
  form.choose('Ondulado');
  form.choose('Cacheado');
  const other = Array.from(form.page.querySelectorAll('label')).find(label => label.textContent?.trim() === 'Crespo');
  expect(other?.querySelector('input')?.disabled).toBe(true);
  const options = form.page.querySelector<HTMLSelectElement>('#subtype-WAVY')!.options;
  expect(Array.from(options, option => option.value)).toEqual(['', '2A', '2B', '2C']);
  form.set('#subtype-WAVY', '2B');
  form.set('#subtype-CURLY', '3C');
  form.click('Salvar respostas');
  const put = request();
  const answers = { curlPattern: { kind: 'MULTIPLE', groups: [
    { group: 'WAVY', subtype: '2B' }, { group: 'CURLY', subtype: '3C' },
  ] } };
  expect(JSON.parse(put.request.serializeBody() as string)).toEqual(answers);
  put.flush(answers);
  form.update();
  expect(form.page.querySelector('[role="status"]')?.textContent).toContain('Respostas salvas.');
});

it('keeps independent observations and clears dependent answers when an exclusive option is selected', () => {
  const form = render({
    chemicalTreatments: ['DYE'], lastChemicalTreatmentMonth: '2025-07', heatTools: ['DRYER'],
    heatFrequency: 'LESS_THAN_WEEKLY', observations: ['DRYNESS'],
  });
  expect(form.page.querySelector<HTMLSelectElement>('#heatFrequency')?.value).toBe('LESS_THAN_WEEKLY');
  form.choose('Nenhum');
  expect(form.page.querySelector('#lastChemicalTreatmentMonth')).toBeNull();
  form.choose('Descoloração');
  form.choose('Nenhum desses');
  form.choose('Frizz');
  form.set('#heatFrequency', 'NEVER');
  form.set('#heatFrequency', 'LESS_THAN_WEEKLY');
  form.click('Salvar respostas');
  const put = request();
  const answers = { chemicalTreatments: ['BLEACH'], heatFrequency: 'LESS_THAN_WEEKLY', observations: ['FRIZZ'] };
  expect(JSON.parse(put.request.serializeBody() as string)).toEqual(answers);
  put.flush(answers);
});

it('retains a partial draft on field error and clears stored answers only after confirmation', () => {
  const form = render({ rootOiliness: 'SOMETIMES' });
  expect(form.page.querySelector<HTMLSelectElement>('#rootOiliness')?.value).toBe('SOMETIMES');
  form.choose('Tintura');
  form.set('#lastChemicalTreatmentMonth', '2027-01');
  form.click('Salvar respostas');
  request().flush({ errors: { lastChemicalTreatmentMonth: 'Informe um mês atual ou anterior.' } },
    { status: 400, statusText: 'Bad Request' });
  form.update();
  const month = form.page.querySelector<HTMLInputElement>('#lastChemicalTreatmentMonth')!;
  expect(month.value).toBe('2027-01');
  expect(month.getAttribute('aria-invalid')).toBe('true');
  expect(form.page.querySelector('#chemical-month-error')?.textContent).toContain('mês atual ou anterior');

  form.click('Limpar respostas');
  form.click('Cancelar');
  expect(month.value).toBe('2027-01');
  http.expectNone('/api/auth/csrf');
  form.click('Limpar respostas');
  form.click('Sim, limpar respostas');
  const put = request();
  expect(put.request.body).toEqual({});
  put.flush({});
  form.update();
  expect(form.page.querySelector<HTMLSelectElement>('#rootOiliness')?.value).toBe('');
  expect(form.page.querySelector('#lastChemicalTreatmentMonth')).toBeNull();
});

it('highlights guides only for applicable saved answers and removes them when the profile is cleared', () => {
  const form = render({ curlPattern: { kind: 'UNKNOWN' }, chemicalTreatments: ['NONE'], heatFrequency: 'NEVER' });
  const highlightedLinks = () => Array.from(form.page.querySelectorAll<HTMLAnchorElement>('.profile-guide-links a'),
    link => link.getAttribute('href'));

  expect(highlightedLinks()).toEqual([]);
  expect(form.page.querySelector('a[href="/ajuda/guias/curvatura"]')).not.toBeNull();
  expect(form.page.querySelector('a[href="/ajuda/guias/elasticidade"]')).not.toBeNull();
  expect(form.page.querySelector('a[href="/ajuda/guias/porosidade"]')).not.toBeNull();

  form.choose('Mais de um padrão');
  form.choose('Ondulado');
  form.choose('Tintura');
  form.set('#heatFrequency', 'LESS_THAN_WEEKLY');
  expect(highlightedLinks()).toEqual([]);
  form.click('Salvar respostas');
  request().flush({
    curlPattern: { kind: 'MULTIPLE', groups: [{ group: 'WAVY' }] },
    chemicalTreatments: ['DYE'], heatFrequency: 'LESS_THAN_WEEKLY',
  });
  form.update();
  expect(highlightedLinks()).toEqual(['/ajuda/guias/curvatura', '/ajuda/guias/quimica', '/ajuda/guias/calor']);

  form.click('Limpar respostas');
  form.click('Sim, limpar respostas');
  request().flush({});
  form.update();
  expect(highlightedLinks()).toEqual([]);
});
