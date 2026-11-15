import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { expect, it } from 'vitest';
import { Vanity } from './vanity';

it('keeps the draft on a failed save and updates the shopping list after a purchase', () => {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  const fixture = TestBed.createComponent(Vanity);
  const root = fixture.nativeElement as HTMLElement;
  const http = TestBed.inject(HttpTestingController);
  const url = '/api/makeup-items';
  const owned = { id: 'a', name: 'Batom', category: null, status: 'OWNED' };
  const low = { id: 'b', name: 'Blush', category: 'Rosto', status: 'RUNNING_LOW' };
  const press = (selector: string) => {
    root.querySelector<HTMLElement>(selector)!.click();
    fixture.detectChanges();
  };
  const enter = (selector: string, value: string) => {
    const input = root.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };
  const send = (target: string) => {
    http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test' });
    return http.expectOne(target);
  };

  http.expectOne(url).flush([owned, low]);
  fixture.detectChanges();
  press('#vanity-missing');
  expect(root.querySelector('ul')?.textContent).toContain('Está acabando');
  expect(root.querySelector('ul')?.textContent).not.toContain('Batom');
  press('#vanity-add');
  press('button[type=submit]');
  expect(root.querySelector('[role=alert]')?.textContent).toContain('Informe o nome');
  http.expectNone('/api/auth/csrf');
  enter('#vanity-name', 'Máscara');
  enter('#vanity-category', 'Olhos');
  press('button[type=submit]');
  const first = send(url);
  expect(first.request.body).toEqual({ id: expect.any(String), name: 'Máscara', category: 'Olhos', status: 'TO_BUY' });
  first.flush(null, { status: 503, statusText: 'Unavailable' });
  fixture.detectChanges();
  expect(root.querySelector<HTMLInputElement>('#vanity-name')?.value).toBe('Máscara');
  expect(root.querySelector<HTMLInputElement>('#vanity-category')?.value).toBe('Olhos');
  press('button[type=submit]');
  const retry = send(url);
  expect(retry.request.body).toEqual(first.request.body);
  retry.flush(first.request.body);
  fixture.detectChanges();
  expect(root.querySelector('ul')?.textContent).toContain('Comprar');
  press('[aria-label="Marcar como comprado: Máscara"]');
  send(url + '/' + first.request.body.id + '/purchased').flush({ ...first.request.body, status: 'OWNED' });
  fixture.detectChanges();
  expect(root.querySelector('ul')?.textContent).not.toContain('Máscara');
  expect(root.querySelector('ul')?.textContent).toContain('Blush');
  press('input[name=vanity-view]');
  expect(root.querySelector('ul')?.textContent).toContain('Máscara');
  http.verify();
});
