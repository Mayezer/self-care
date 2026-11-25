import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { App } from './app';

describe('App', () => {
  let http: HttpTestingController;
  const previewUrl = '/api/hair-plans/preview';

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reports the available API', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const page: HTMLElement = fixture.nativeElement;

    expect(page.querySelector('h1')?.textContent).toBe('Cronograma capilar');
    expect(page.querySelector('.connection')?.textContent).toBe('Verificando a conexão…');

    const request = http.expectOne('/api/health');
    expect(request.request.method).toBe('GET');
    request.flush({ status: 'UP' });
    fixture.detectChanges();

    expect(page.querySelector('.connection')?.textContent).toBe('Conexão com a API confirmada.');
  });

  it('reports an unavailable API', () => {
    const fixture = TestBed.createComponent(App);
    http.expectOne('/api/health').flush(null, { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();
    const page: HTMLElement = fixture.nativeElement;

    expect(page.querySelector('.connection')?.textContent).toBe('API indisponível.');
  });

  function openPlanner(): { fixture: ComponentFixture<App>; page: HTMLElement } {
    const fixture = TestBed.createComponent(App);
    http.expectOne('/api/health').flush({ status: 'UP' });
    fixture.detectChanges();
    return { fixture, page: fixture.nativeElement };
  }

  function setInput(input: HTMLInputElement, value: string): void {
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  function submit(page: HTMLElement): void {
    page.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
  }

  it('uses the API dates and keeps the chosen stage for a date in both previews', () => {
    const { fixture, page } = openPlanner();
    setInput(page.querySelector<HTMLInputElement>('#start-date')!, '2026-11-11');
    const monday = page.querySelector<HTMLButtonElement>('button[aria-label="Seg, Segunda-feira"]')!;
    const thursday = page.querySelector<HTMLButtonElement>('button[aria-label="Qui, Quinta-feira"]')!;
    monday.click();
    thursday.click();
    submit(page);

    const request = http.expectOne(previewUrl);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      startDate: '2026-11-11',
      weeks: 2,
      weekdays: ['MONDAY', 'THURSDAY'],
      timeZone: 'America/Sao_Paulo',
    });
    request.flush({
      occurrences: ['2026-11-12', '2026-11-16', '2026-11-19', '2026-11-23']
        .map(date => ({ date, stage: 'WASH_ONLY' })),
    });
    fixture.detectChanges();

    const thursdayStage = page.querySelector<HTMLSelectElement>('#stage-2026-11-12')!;
    expect(thursdayStage.value).toBe('WASH_ONLY');
    thursdayStage.value = 'HYDRATION';
    thursdayStage.dispatchEvent(new Event('change'));

    monday.click();
    page.querySelector<HTMLButtonElement>('button[aria-label="Sáb, Sábado"]')!.click();
    fixture.detectChanges();
    expect(monday.getAttribute('aria-pressed')).toBe('false');
    expect(page.querySelector('.preview-status')?.textContent).toContain('Os parâmetros mudaram.');
    submit(page);
    const updated = http.expectOne(previewUrl);
    expect(updated.request.body.weekdays).toEqual(['THURSDAY', 'SATURDAY']);
    updated.flush({
      occurrences: ['2026-11-12', '2026-11-14', '2026-11-19', '2026-11-21']
        .map(date => ({ date, stage: 'WASH_ONLY' })),
    });
    fixture.detectChanges();

    expect(Array.from(page.querySelectorAll('time'), item => item.dateTime))
      .toEqual(['2026-11-12', '2026-11-14', '2026-11-19', '2026-11-21']);
    expect(thursdayStage.value).toBe('HYDRATION');
    expect(page.querySelector<HTMLSelectElement>('#stage-2026-11-14')?.value).toBe('WASH_ONLY');
  });

  it('shows a field error while preserving the submitted values for correction', () => {
    const { fixture, page } = openPlanner();
    const weeks = page.querySelector<HTMLInputElement>('#weeks')!;
    const monday = page.querySelector<HTMLButtonElement>('button[aria-label="Seg, Segunda-feira"]')!;
    monday.click();
    setInput(weeks, '13');
    submit(page);
    http.expectOne(previewUrl).flush(
      { errors: { weeks: 'Escolha uma duração de 1 a 12 semanas.' } },
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();

    expect(weeks.value).toBe('13');
    expect(weeks.getAttribute('aria-invalid')).toBe('true');
    expect(weeks.getAttribute('aria-describedby')).toContain('weeks-error');
    expect(page.querySelector('#weeks-error')?.textContent).toContain('de 1 a 12 semanas');
    expect(monday.getAttribute('aria-pressed')).toBe('true');

    setInput(weeks, '2');
    fixture.detectChanges();
    expect(weeks.hasAttribute('aria-invalid')).toBe(false);
    expect(page.querySelector('#weeks-error')).toBeNull();
  });

  it('cancels an in-flight preview when the parameters change and accepts a new submission', () => {
    const { fixture, page } = openPlanner();
    submit(page);
    const stale = http.expectOne(previewUrl);
    fixture.detectChanges();
    expect(page.querySelector('.preview-status')?.textContent).toContain('Consultando as datas');

    page.querySelector<HTMLButtonElement>('button[aria-label="Seg, Segunda-feira"]')!.click();
    expect(stale.cancelled).toBe(true);

    submit(page);
    http.expectOne(previewUrl).flush(null, { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Tente novamente.');
  });
});
