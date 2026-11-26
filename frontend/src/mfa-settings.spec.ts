import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { expect, it, vi } from 'vitest';
import { MfaSettings } from './mfa-settings';

function setup(enabled = false, required = false) {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  const fixture = TestBed.createComponent(MfaSettings);
  const http = TestBed.inject(HttpTestingController);
  http.expectOne('/api/auth/mfa').flush({ enabled, required, available: true });
  fixture.detectChanges();
  const page: HTMLElement = fixture.nativeElement;
  const button = (text: string) => [...page.querySelectorAll('button')].find(item => item.textContent?.includes(text))!;
  const post = (path: string) => {
    http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test-only' });
    const request = http.expectOne('/api/auth/mfa/' + path);
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('test-only');
    return request;
  };
  const edit = () => { button(enabled ? 'Desativar' : 'Ativar').click(); fixture.detectChanges(); };
  const submit = () => page.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
  return { fixture, http, page, button, post, edit, submit };
}

it('offers optional enrollment only after a password and email confirmation, with an associated recovery checkbox', () => {
  const { fixture, http, page, post, edit, submit } = setup();
  expect(page.textContent).toContain('Desativada');
  expect(page.querySelector('input')).toBeNull();
  edit();
  page.querySelector<HTMLInputElement>('[name="currentPassword"]')!.value = 'Synthetic-password-123!';
  submit();
  const start = post('setup');
  expect(start.request.body).toEqual({ currentPassword: 'Synthetic-password-123!' });
  start.flush(null, { status: 202, statusText: 'Accepted' });
  fixture.detectChanges();
  expect(page.textContent).toContain('Confirme o e-mail');
  page.querySelector<HTMLInputElement>('#identity-email-code')!.value = 'synthetic-email-code';
  submit();
  post('email/confirm').flush({ secret: 'SYNTHETICSETUPKEY', recovery: 'synthetic-recovery-key' });
  fixture.detectChanges();
  const checkbox = page.querySelector<HTMLInputElement>('input[type="checkbox"]')!;
  expect(checkbox.required).toBe(true);
  expect(checkbox.labels?.[0]?.textContent).toContain('Guardei a chave de recuperação');
  const actions = page.querySelector('.identity-actions')!;
  expect(actions.querySelector('.primary')?.textContent).toContain('Confirmar configuração');
  expect(actions.querySelector('.secondary')?.textContent).toContain('Cancelar configuração');
  http.verify();
});

it('cancels a pending profile enrollment on the server while preserving the profile', () => {
  const { fixture, http, page, button, post, edit, submit } = setup();
  edit();
  page.querySelector<HTMLInputElement>('[name="currentPassword"]')!.value = 'Synthetic-password-123!';
  submit(); post('setup').flush(null, { status: 202, statusText: 'Accepted' });
  fixture.detectChanges();
  button('Cancelar configuração').click();
  post('cancel').flush(null);
  fixture.detectChanges();
  expect(page.querySelector('app-identity-flow')).toBeNull();
  expect(page.textContent).toContain('Desativada');
  http.verify();
});

it('disables MFA only with the password and current factor and reports the ended sessions', () => {
  const { fixture, http, page, post, edit, submit } = setup(true);
  const changed = vi.fn(); fixture.componentInstance.changed.subscribe(changed);
  edit();
  page.querySelector<HTMLInputElement>('[name="currentPassword"]')!.value = 'Synthetic-password-123!';
  page.querySelector<HTMLInputElement>('[name="code"]')!.value = '123456';
  submit();
  const request = post('disable');
  expect(request.request.body).toEqual({ currentPassword: 'Synthetic-password-123!', code: '123456' });
  expect(page.querySelector<HTMLInputElement>('[name="currentPassword"]')!.value).toBe('');
  request.flush(null);
  expect(changed).toHaveBeenCalledWith('disabled');
  http.verify();
});

it('keeps the owner protection visible and does not offer disabling it', () => {
  const { http, page } = setup(true, true);
  expect(page.textContent).toContain('conta administrativa exige');
  expect(page.querySelector('button')).toBeNull();
  http.verify();
});

it('keeps the form usable on failure and reports expired sessions', () => {
  const { fixture, http, page, post, edit, submit } = setup();
  const expired = vi.fn(); fixture.componentInstance.sessionExpired.subscribe(expired);
  edit(); submit(); post('setup').flush({}, { status: 403, statusText: 'Forbidden' });
  fixture.detectChanges();
  expect(page.querySelector('[role="alert"]')?.textContent).toContain('Confira a senha atual');
  expect(page.querySelector<HTMLButtonElement>('[type="submit"]')!.disabled).toBe(false);
  submit(); post('setup').flush({}, { status: 401, statusText: 'Unauthorized' });
  expect(expired).toHaveBeenCalledOnce();
  http.verify();
});
