import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { expect, it, vi } from 'vitest';
import { PasswordChange } from './password-change';

for (const enabled of [false, true]) {
  it(`changes a password with MFA ${enabled ? 'enabled' : 'disabled'} without inventing a required factor`, () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    const fixture = TestBed.createComponent(PasswordChange);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    const page: HTMLElement = fixture.nativeElement;
    expect(page.querySelector<HTMLButtonElement>('button')!.disabled).toBe(true);
    http.expectOne('/api/auth/mfa').flush({ enabled, required: false, available: true });
    fixture.detectChanges();
    const changed = vi.fn(); fixture.componentInstance.changed.subscribe(changed);
    page.querySelector<HTMLInputElement>('#change-current-password')!.value = 'Synthetic-old-123!';
    page.querySelector<HTMLInputElement>('#change-new-password')!.value = 'Synthetic-new-123!';
    const factor = page.querySelector<HTMLInputElement>('#change-factor');
    expect(!!factor).toBe(enabled);
    if (factor) { expect(factor.required).toBe(true); factor.value = '123456'; }
    page.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test-only' });
    const request = http.expectOne('/api/auth/password/change');
    expect(request.request.body).toEqual({ currentPassword: 'Synthetic-old-123!', password: 'Synthetic-new-123!', factor: enabled ? '123456' : '' });
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('test-only');
    request.flush(null);
    expect(changed).toHaveBeenCalledOnce();
    http.verify();
  });
}
