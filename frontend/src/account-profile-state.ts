import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { tap } from 'rxjs';
import { deleteWithCsrf, putWithCsrf } from './csrf';

export interface AccountProfileData {
  displayName: string | null;
  hasPhoto: boolean;
  photoVersion: number;
}

@Injectable({ providedIn: 'root' })
export class AccountProfileState {
  private readonly http = inject(HttpClient);
  readonly current = signal<AccountProfileData | null>(null);
  readonly photoUrl = computed(() => this.current()?.hasPhoto
    ? '/api/account/profile/photo?v=' + this.current()!.photoVersion : null);

  rename(displayName: string) {
    return putWithCsrf<AccountProfileData>(this.http, '/api/account/profile', { displayName })
      .pipe(tap(profile => this.current.set(profile)));
  }

  upload(photo: File) {
    const form = new FormData();
    form.append('photo', photo);
    return putWithCsrf<AccountProfileData>(this.http, '/api/account/profile/photo', form)
      .pipe(tap(profile => this.current.set(profile)));
  }

  removePhoto() {
    return deleteWithCsrf<AccountProfileData>(this.http, '/api/account/profile/photo')
      .pipe(tap(profile => this.current.set(profile)));
  }
}
