import { HttpClient } from '@angular/common/http';
import { switchMap } from 'rxjs';

export function postWithCsrf<T>(http: HttpClient, url: string, body: object | null) {
  return writeWithCsrf<T>(http, 'POST', url, body);
}

export function putWithCsrf<T>(http: HttpClient, url: string, body: object) {
  return writeWithCsrf<T>(http, 'PUT', url, body);
}

export function deleteWithCsrf<T>(http: HttpClient, url: string) {
  return writeWithCsrf<T>(http, 'DELETE', url, null);
}

function writeWithCsrf<T>(http: HttpClient, method: 'POST' | 'PUT' | 'DELETE', url: string, body: object | null) {
  return http.get<{ headerName: string; token: string }>('/api/auth/csrf').pipe(
    switchMap(({ headerName, token }) => http.request<T>(method, url, { body, headers: { [headerName]: token } })),
  );
}
