import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { WebPushSettings } from './web-push-settings';

const url = '/api/agenda/web-push';
const endpoint = 'https://fcm.googleapis.com/fcm/send/fictional';
const publicKey = 'fixture-public-key';
const unsubscribe = vi.fn().mockResolvedValue(true);
const subscribe = vi.fn().mockResolvedValue({ endpoint, unsubscribe });
const getSubscription = vi.fn();
const worker = { active: {}, pushManager: { getSubscription, subscribe }, getNotifications: async () => [] };
const register = vi.fn().mockResolvedValue(worker);
const requestPermission = vi.fn();
let http: HttpTestingController;

beforeEach(() => {
  vi.clearAllMocks();
  getSubscription.mockResolvedValue(null);
  vi.stubGlobal('isSecureContext', true);
  vi.stubGlobal('PushManager', class {});
  vi.stubGlobal('Notification', { permission: 'default', requestPermission });
  vi.stubGlobal('navigator', { serviceWorker: { getRegistration: async () => worker, register } });
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  http = TestBed.inject(HttpTestingController);
});

afterEach(() => {
  http.verify();
  vi.unstubAllGlobals();
});

async function render() {
  const fixture = TestBed.createComponent(WebPushSettings);
  await vi.waitFor(() => http.expectOne(url).flush({ active: false, publicKey }));
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture;
}

function button(page: HTMLElement, name: string): HTMLButtonElement | undefined {
  return Array.from(page.querySelectorAll('button')).find(candidate => candidate.textContent?.trim() === name);
}

it('requests permission only after activation and removes the browser subscription after server deactivation', async () => {
  requestPermission.mockResolvedValue('granted');
  const fixture = await render();
  const page: HTMLElement = fixture.nativeElement;
  expect(requestPermission).not.toHaveBeenCalled();
  button(page, 'Ativar notificações')!.click();
  expect(requestPermission).toHaveBeenCalledOnce();
  await vi.waitFor(() => http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'fixture' }));
  const activation = http.expectOne({ method: 'PUT', url });
  expect(activation.request.body).toEqual({ endpoint, enabled: true });
  expect(activation.request.headers.get('X-CSRF-TOKEN')).toBe('fixture');
  expect(subscribe).toHaveBeenCalledWith({ userVisibleOnly: true, applicationServerKey: publicKey });
  activation.flush(null, { status: 204, statusText: 'No Content' });
  await fixture.whenStable();
  fixture.detectChanges();
  expect(page.textContent).toContain('Notificações ativas neste navegador.');

  getSubscription.mockResolvedValue({ endpoint, unsubscribe });
  button(page, 'Desativar notificações')!.click();
  await vi.waitFor(() => http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'fixture' }));
  const deactivation = http.expectOne({ method: 'PUT', url });
  expect(deactivation.request.body).toEqual({ endpoint, enabled: false });
  expect(unsubscribe).not.toHaveBeenCalled();
  deactivation.flush(null, { status: 204, statusText: 'No Content' });
  await vi.waitFor(() => expect(unsubscribe).toHaveBeenCalledOnce());
  fixture.detectChanges();
  expect(page.textContent).toContain('Notificações desativadas neste navegador.');
});

it('announces denied permission without registering a service worker', async () => {
  requestPermission.mockResolvedValue('denied');
  const fixture = await render();
  const page: HTMLElement = fixture.nativeElement;
  button(page, 'Ativar notificações')!.click();
  await fixture.whenStable();
  fixture.detectChanges();
  expect(page.textContent).toContain('Permissão recusada.');
  expect(button(page, 'Ativar notificações')).toBeUndefined();
  expect(register).not.toHaveBeenCalled();
});

it('announces unavailable support without querying subscriptions or prompting', () => {
  vi.stubGlobal('isSecureContext', false);
  const fixture = TestBed.createComponent(WebPushSettings);
  fixture.detectChanges();
  expect(fixture.nativeElement.textContent).toContain('As notificações estão indisponíveis neste navegador.');
  expect(getSubscription).not.toHaveBeenCalled();
  expect(requestPermission).not.toHaveBeenCalled();
});
