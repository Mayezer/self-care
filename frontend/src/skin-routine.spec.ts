import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { expect, it } from 'vitest';
import { SkinRoutine } from './skin-routine';
import { suggestSkinRoutine } from './skin-routine-model';

it('offers the reviewed introductory sequence for skipped answers and avoids unreviewed templates', () => {
  const morning = { id: 'morning', version: 1, reviewedOn: '2026-11-13', steps: ['Modelo de teste'] };
  const night = { ...morning, id: 'night', reviewedOn: null };
  expect(suggestSkinRoutine('', '', '')?.periods.map(period => period.period)).toEqual(['MORNING']);
  expect(suggestSkinRoutine('NIGHT', 'SHORT', 'MAKEUP')?.periods[0].steps).toEqual(['Limpeza suave', 'Hidratação']);
  expect(suggestSkinRoutine('NIGHT', 'FLEXIBLE', 'MAKEUP')?.periods[0].steps[0]).toBe('Remover a maquiagem');
  expect(suggestSkinRoutine('BOTH', '', '', { MORNING: morning, NIGHT: night })).toBeNull();
});

it('saves only the edited period, keeps the preview on failure and reuses the confirmation on retry', () => {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
  });
  const fixture = TestBed.createComponent(SkinRoutine);
  fixture.componentRef.setInput('today', '2026-11-13');
  fixture.detectChanges();
  const root = fixture.nativeElement as HTMLElement;
  const http = TestBed.inject(HttpTestingController);
  const press = (name: string) => {
    [...root.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent.trim() === name)!.click();
    fixture.detectChanges();
  };
  const choose = (selector: string, value: string, event: string) => {
    const input = root.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event(event));
    fixture.detectChanges();
  };
  const submit = () => {
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
  };

  press('Receber sugestão');
  choose('#skin-timing', 'BOTH', 'change');
  choose('#skin-perception', 'OILINESS', 'change');
  press('Ver sugestão');
  expect(root.querySelector('h2')?.textContent).toBe('Prévia da rotina de pele');
  const [morning] = [...root.querySelectorAll<HTMLInputElement>('input[type=checkbox]')];
  morning.click();
  fixture.detectChanges();
  choose('#skin-NIGHT-title', 'Minha rotina noturna', 'input');
  choose('input[id^=skin-NIGHT-step-]', 'Limpeza facial', 'input');
  root.querySelector<HTMLButtonElement>('[data-day=FRIDAY]')!.click();
  fixture.detectChanges();
  submit();
  http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test' });
  const request = http.expectOne('/api/skin-routines');
  expect(request.request.body).toEqual({
    requestId: expect.any(String), startDate: '2026-11-13', weeks: 1,
    periods: [{
      period: 'NIGHT', title: 'Minha rotina noturna', steps: ['Limpeza facial', 'Hidratação'],
      weekdays: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'SATURDAY', 'SUNDAY'],
    }],
  });
  request.flush(null, { status: 503, statusText: 'Unavailable' });
  fixture.detectChanges();
  expect(root.querySelector('[role=alert]')?.textContent).toContain('Tente salvar a mesma rotina');
  submit();
  http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test' });
  const retry = http.expectOne('/api/skin-routines');
  expect(retry.request.body).toEqual(request.request.body);
  retry.flush(null, { status: 503, statusText: 'Unavailable' });
  fixture.detectChanges();

  press('Descartar prévia');
  press('Montar manualmente');
  submit();
  expect(root.querySelector('[role=alert]')?.textContent).toContain('selecione pelo menos um dia');
  expect(root.querySelector<HTMLInputElement>('input[id^=skin-MORNING-step-]')?.value).toBe('');
  http.verify();
});
