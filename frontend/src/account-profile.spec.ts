import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { expect, it } from 'vitest';
import { AccountProfile } from './account-profile';
import { AccountProfileState } from './account-profile-state';

function setup() {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  const state = TestBed.inject(AccountProfileState);
  state.current.set({ displayName: 'Nome anterior', hasPhoto: true, photoVersion: 3 });
  const fixture = TestBed.createComponent(AccountProfile);
  fixture.componentRef.setInput('identifier', 'pessoa@example.test');
  fixture.detectChanges();
  const page: HTMLElement = fixture.nativeElement;
  return { fixture, state, http: TestBed.inject(HttpTestingController), page };
}

it('saves the name with CSRF and preserves the profile photo', () => {
  const { fixture, state, http, page } = setup();
  const name = page.querySelector<HTMLInputElement>('#profile-display-name')!;
  name.value = ' Nome atualizado ';
  name.dispatchEvent(new Event('input'));
  page.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
  http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test-only' });
  const write = http.expectOne('/api/account/profile');
  expect(write.request.method).toBe('PUT');
  expect(write.request.body).toEqual({ displayName: 'Nome atualizado' });
  expect(write.request.headers.get('X-CSRF-TOKEN')).toBe('test-only');
  write.flush({ displayName: 'Nome atualizado', hasPhoto: true, photoVersion: 3 });
  fixture.detectChanges();
  expect(state.current()?.displayName).toBe('Nome atualizado');
  expect(state.photoUrl()).toBe('/api/account/profile/photo?v=3');
  expect(page.textContent).toContain('Nome salvo.');
  http.verify();
});

it('keeps the current picture on upload failure and updates both views only after success', () => {
  const { fixture, state, http, page } = setup();
  const file = page.querySelector<HTMLInputElement>('input[type="file"]')!;
  const choose = () => {
    Object.defineProperty(file, 'files', { configurable: true, value: [new File(['fixture'], 'test.png', { type: 'image/png' })] });
    file.dispatchEvent(new Event('change'));
    http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test-only' });
    return http.expectOne('/api/account/profile/photo');
  };
  const first = choose();
  expect(first.request.body instanceof FormData).toBe(true);
  first.flush({ errors: { photo: 'Não foi possível salvar.' } }, { status: 503, statusText: 'Unavailable' });
  fixture.detectChanges();
  expect(state.photoUrl()).toBe('/api/account/profile/photo?v=3');
  expect(state.current()?.displayName).toBe('Nome anterior');
  expect(page.querySelector('[role="alert"]')?.textContent).toContain('Não foi possível salvar.');
  choose().flush({ displayName: 'Nome anterior', hasPhoto: true, photoVersion: 4 });
  fixture.detectChanges();
  expect(state.photoUrl()).toBe('/api/account/profile/photo?v=4');
  expect(page.textContent).toContain('Foto de perfil salva.');
  http.verify();
});

it('removes a photo without clearing the name', () => {
  const { fixture, state, http, page } = setup();
  const remove = [...page.querySelectorAll('button')].find(button => button.textContent?.includes('Remover foto'))!;
  remove.click();
  http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test-only' });
  const write = http.expectOne('/api/account/profile/photo');
  expect(write.request.method).toBe('DELETE');
  write.flush({ displayName: 'Nome anterior', hasPhoto: false, photoVersion: 4 });
  fixture.detectChanges();
  expect(state.photoUrl()).toBeNull();
  expect(state.current()?.displayName).toBe('Nome anterior');
  expect(page.querySelector('img')).toBeNull();
  http.verify();
});
