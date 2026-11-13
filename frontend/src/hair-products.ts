import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';

interface ProductVariant {
  brand: string;
  name: string;
  presentation: string;
  hairCondition: string;
  rinse: 'RINSE_OFF' | 'LEAVE_IN';
  warnings: string[];
  manufacturerUrl: string;
}

interface ProductCard {
  category: string;
  title: string;
  reason: string;
  area: string;
  instructions: string;
  optional: boolean;
  variant: ProductVariant | null;
}

interface Suggestions {
  hasProfile: boolean;
  cards: ProductCard[];
}

type ProductState =
  | { kind: 'loading' | 'no-profile' | 'unavailable' | 'error' }
  | { kind: 'ready'; cards: ProductCard[] };

@Component({
  selector: 'app-hair-products',
  imports: [RouterLink],
  templateUrl: './hair-products.html',
  styleUrl: './hair-products.css',
})
export class HairProducts {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);

  readonly sessionExpired = output<void>();
  protected readonly state = signal<ProductState>({ kind: 'loading' });

  constructor() {
    this.load();
  }

  protected load(): void {
    this.state.set({ kind: 'loading' });
    this.http.get<Suggestions>('/api/hair-profile/products').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ({ hasProfile, cards }) => this.state.set(hasProfile ? { kind: 'ready', cards } : { kind: 'no-profile' }),
      error: (error: HttpErrorResponse) => {
        if (error.status === 401) {
          this.sessionExpired.emit();
        } else {
          this.state.set({ kind: error.status === 404 ? 'unavailable' : 'error' });
        }
      },
    });
  }
}
