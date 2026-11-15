import { HttpClient, HttpResponse } from '@angular/common/http';
import { Observable, switchMap } from 'rxjs';

export function postWithCsrf<T>(http: HttpClient, url: string, body: object | null): Observable<T>;
export function postWithCsrf<T>(http: HttpClient, url: string, body: object | null, observe: 'response'): Observable<HttpResponse<T>>;
export function postWithCsrf<T>(http: HttpClient, url: string, body: object | null, observe?: 'response') {
  return observe === 'response'
    ? withCsrf(http, headers => http.post<T>(url, body, { headers, observe }))
    : writeWithCsrf<T>(http, 'POST', url, body);
}

export function putWithCsrf<T>(http: HttpClient, url: string, body: object) {
  return writeWithCsrf<T>(http, 'PUT', url, body);
}

export function deleteWithCsrf<T>(http: HttpClient, url: string) {
  return writeWithCsrf<T>(http, 'DELETE', url, null);
}

function writeWithCsrf<T>(http: HttpClient, method: 'POST' | 'PUT' | 'DELETE', url: string, body: object | null) {
  return withCsrf(http, headers => http.request<T>(method, url, { body, headers }));
}

function withCsrf<T>(http: HttpClient, request: (headers: Record<string, string>) => Observable<T>) {
  return http.get<{ headerName: string; token: string }>('/api/auth/csrf').pipe(
    switchMap(({ headerName, token }) => request({ [headerName]: token })),
  );
}
