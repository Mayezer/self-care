import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { expect, it } from 'vitest';
import { HairDiary } from './hair-diary';

it('moves focus to the chronological gallery after a photo is saved', async () => {
  const url = '/api/hair-diary/photos';
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
  });
  const http = TestBed.inject(HttpTestingController);
  const fixture = TestBed.createComponent(HairDiary);
  fixture.componentRef.setInput('today', '2026-11-11');
  http.expectOne(url).flush([]);
  fixture.detectChanges();

  const page: HTMLElement = fixture.nativeElement;
  const file = page.querySelector<HTMLInputElement>('#diary-photo')!;
  Object.defineProperty(file, 'files', { value: [new File(['fixture'], 'foto.png', { type: 'image/png' })] });
  page.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  http.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test-token' });
  const upload = http.expectOne(request => request.method === 'PUT' && request.url.startsWith(url + '/'));
  expect(upload.request.headers.get('X-CSRF-TOKEN')).toBe('test-token');
  expect((upload.request.body as FormData).get('date')).toBe('2026-11-11');
  upload.flush(null, { status: 204, statusText: 'No Content' });
  http.expectOne(url).flush([{ id: upload.request.url.split('/').at(-1), date: '2026-11-11' }]);
  fixture.detectChanges();
  await fixture.whenStable();

  expect(page.querySelector('ol li')).not.toBeNull();
  expect(document.activeElement).toBe(page.querySelector('#diary-photos-title'));
  http.verify();
});
