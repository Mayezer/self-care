import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AccountProfileState } from './account-profile-state';

@Component({
  selector: 'app-account-profile',
  templateUrl: './account-profile.html',
  styleUrl: './account-profile.css',
})
export class AccountProfile {
  protected readonly state = inject(AccountProfileState);
  private readonly destroyRef = inject(DestroyRef);
  readonly identifier = input.required<string>();
  readonly sessionExpired = output<void>();
  protected readonly name = signal(this.state.current()?.displayName ?? '');
  protected readonly saving = signal(false);
  protected readonly uploading = signal(false);
  protected readonly error = signal('');
  protected readonly nameError = signal('');
  protected readonly notice = signal('');

  protected saveName(): void {
    if (this.saving()) return;
    const name = this.name().trim();
    if ([...name].length > 80) {
      this.nameError.set('Use até 80 caracteres.');
      return;
    }
    this.nameError.set('');
    this.error.set('');
    this.notice.set('');
    this.saving.set(true);
    this.state.rename(name).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: profile => {
        this.name.set(profile.displayName ?? '');
        this.saving.set(false);
        this.notice.set('Nome salvo.');
      },
      error: (error: HttpErrorResponse) => {
        this.saving.set(false);
        if (error.status === 401) this.sessionExpired.emit();
        else this.nameError.set(error.error?.errors?.displayName ?? 'Não foi possível salvar seu nome. Tente novamente.');
      },
    });
  }

  protected upload(input: HTMLInputElement): void {
    const photo = input.files?.[0];
    input.value = '';
    if (!photo || this.uploading()) return;
    this.error.set('');
    this.notice.set('');
    if (!['image/jpeg', 'image/png'].includes(photo.type) || photo.size > 5 * 1024 * 1024) {
      this.error.set('Escolha uma foto JPEG ou PNG de até 5 MiB.');
      return;
    }
    this.uploading.set(true);
    this.state.upload(photo).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.uploading.set(false); this.notice.set('Foto de perfil salva.'); },
      error: (error: HttpErrorResponse) => this.photoFailed(error),
    });
  }

  protected removePhoto(): void {
    if (this.uploading()) return;
    this.error.set('');
    this.notice.set('');
    this.uploading.set(true);
    this.state.removePhoto().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.uploading.set(false); this.notice.set('Foto de perfil removida.'); },
      error: (error: HttpErrorResponse) => this.photoFailed(error),
    });
  }

  private photoFailed(error: HttpErrorResponse): void {
    this.uploading.set(false);
    if (error.status === 401) this.sessionExpired.emit();
    else this.error.set(error.error?.errors?.photo ?? (error.status === 413
      ? 'Escolha uma foto de até 5 MiB.' : 'Não foi possível atualizar sua foto. Tente novamente.'));
  }
}
