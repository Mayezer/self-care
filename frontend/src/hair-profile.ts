import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import {
  afterNextRender, Component, computed, DestroyRef, ElementRef, inject, Injector, input, output, signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { putWithCsrf } from './csrf';
import { CURL_GROUPS, CurlGroup, CurlSubtype } from './curl-patterns';
import { Guide, GUIDES } from './help-guides';

const PROFILE_URL = '/api/hair-profile';
type PatternChoice = CurlGroup | 'MULTIPLE' | 'UNKNOWN' | '';
interface CurlSelection { group: CurlGroup; subtype?: CurlSubtype }
type CurlPattern = { kind: 'SINGLE' | 'MULTIPLE' | 'UNKNOWN'; groups?: CurlSelection[] };

const UNKNOWN = { value: 'UNKNOWN', label: 'Não sei' };
const TEXTURE_FIELDS = [
  {
    field: 'visualLength', label: 'Comprimento visual', hint: 'Pense no cabelo seco, no caimento habitual.',
    options: [
      { value: 'ABOVE_EAR', label: 'Acima da orelha' },
      { value: 'BETWEEN_EAR_AND_SHOULDERS', label: 'Entre a orelha e os ombros' },
      { value: 'AT_SHOULDERS', label: 'Na altura dos ombros' },
      { value: 'BELOW_SHOULDERS', label: 'Abaixo dos ombros' }, UNKNOWN,
    ],
  },
  {
    field: 'strandThickness', label: 'Espessura de um fio', hint: '', options: [
      { value: 'FINE', label: 'Fino' }, { value: 'MEDIUM', label: 'Médio' }, { value: 'THICK', label: 'Grosso' }, UNKNOWN,
    ],
  },
  {
    field: 'rootOiliness', label: 'Com que frequência a raiz fica oleosa?', hint: '', options: [
      { value: 'RARELY', label: 'Raramente' }, { value: 'SOMETIMES', label: 'Às vezes' },
      { value: 'OFTEN', label: 'Frequentemente' }, UNKNOWN,
    ],
  },
] as const;
const HEAT_FREQUENCIES = [
  { value: 'NEVER', label: 'Nunca' }, { value: 'LESS_THAN_WEEKLY', label: 'Menos de uma vez por semana' },
  { value: 'ONE_TO_THREE_PER_WEEK', label: 'Uma a três vezes por semana' },
  { value: 'FOUR_OR_MORE_PER_WEEK', label: 'Quatro ou mais vezes por semana' }, UNKNOWN,
];
const MULTI_FIELDS = [
  {
    field: 'chemicalTreatments', title: 'Química', label: 'Procedimentos que você fez', options: [
      { value: 'DYE', label: 'Tintura' }, { value: 'BLEACH', label: 'Descoloração' },
      { value: 'HIGHLIGHTS', label: 'Luzes ou mechas' }, { value: 'STRAIGHTENING_OR_RELAXING', label: 'Alisamento ou relaxamento' },
      { value: 'OTHER', label: 'Outro' }, { value: 'NONE', label: 'Nenhum' }, UNKNOWN,
    ],
  },
  {
    field: 'heatTools', title: 'Calor', label: 'Ferramentas que você usa', options: [
      { value: 'DRYER', label: 'Secador' }, { value: 'FLAT_IRON', label: 'Chapinha' }, { value: 'CURLING_IRON', label: 'Modelador' },
    ],
  },
  {
    field: 'observations', title: 'O que você percebeu', label: 'Observações', options: [
      { value: 'DRYNESS', label: 'Ressecamento' }, { value: 'BREAKAGE', label: 'Quebra' },
      { value: 'SPLIT_ENDS', label: 'Pontas duplas' }, { value: 'FRIZZ', label: 'Frizz' },
      { value: 'NONE', label: 'Nenhum desses' }, UNKNOWN,
    ],
  },
] as const;

type TextureField = typeof TEXTURE_FIELDS[number]['field'];
type MultiField = typeof MULTI_FIELDS[number]['field'];
type SingleField = TextureField | 'heatFrequency' | 'lastChemicalTreatmentMonth';
type Answers = Partial<Record<SingleField, string> & Record<MultiField, string[]> & { curlPattern: CurlPattern }>;
type FieldErrors = Partial<Record<keyof Answers | 'body', string>>;

function isExclusive(value: string): boolean {
  return value === 'NONE' || value === 'UNKNOWN';
}

@Component({
  selector: 'app-hair-profile',
  imports: [RouterLink],
  templateUrl: './hair-profile.html',
  styleUrl: './hair-profile.css',
})
export class HairProfile {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly today = input.required<string>();
  readonly guidesAvailable = input.required<boolean>();
  readonly productsAvailable = input(false);
  readonly sessionExpired = output<void>();
  protected readonly curlGroups = CURL_GROUPS;
  protected readonly guideTopics = GUIDES;
  protected readonly textureFields = TEXTURE_FIELDS;
  protected readonly heatFrequencies = HEAT_FREQUENCIES;
  protected readonly multiFields = MULTI_FIELDS;
  protected readonly answers = signal<Answers>({});
  private readonly savedAnswers = signal<Answers>({});
  protected readonly hasSavedAnswers = computed(() => Object.keys(this.savedAnswers()).length > 0);
  protected readonly showProducts = computed(() => this.productsAvailable() && this.hasSavedAnswers());
  protected readonly suggestedGuides = computed(() => {
    const answers = this.savedAnswers();
    const guides: Guide[] = [];
    if (answers.curlPattern?.groups?.length) guides.push(GUIDES.curl);
    if (answers.chemicalTreatments?.some(value => !isExclusive(value))) guides.push(GUIDES.chemistry);
    if (answers.heatTools?.length || answers.heatFrequency && answers.heatFrequency !== 'NEVER' && answers.heatFrequency !== 'UNKNOWN') {
      guides.push(GUIDES.heat);
    }
    return guides;
  });
  protected readonly state = signal<'loading' | 'ready' | 'unavailable'>('loading');
  protected readonly saving = signal(false);
  protected readonly confirmingClear = signal(false);
  protected readonly errors = signal<FieldErrors>({});
  protected readonly feedback = signal('');
  protected readonly saveResult = signal<'saved' | 'cleared' | null>(null);
  protected readonly hasErrors = computed(() => Object.keys(this.errors()).length > 0);
  protected readonly groups = computed(() => this.answers().curlPattern?.groups ?? []);
  protected readonly patternChoice = computed<PatternChoice>(() => {
    const pattern = this.answers().curlPattern;
    return pattern?.kind === 'SINGLE' ? pattern.groups?.[0]?.group ?? '' : pattern?.kind ?? '';
  });
  protected readonly selectedGroups = computed(() =>
    CURL_GROUPS.filter(option => this.groups().some(selection => selection.group === option.value)));
  protected readonly hasChemicalTreatment = computed(() =>
    this.answers().chemicalTreatments?.some(value => !isExclusive(value)) ?? false);

  constructor() {
    this.load();
  }

  protected load(retrying = false): void {
    this.state.set('loading');
    if (retrying) this.focus('[data-load-status]');
    this.http.get<Answers>(PROFILE_URL).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: answers => {
        this.receive(answers);
        this.state.set('ready');
        if (retrying) this.focus('[data-profile-field="curlPattern"]');
      },
      error: (error: HttpErrorResponse) => {
        if (error.status === 401) {
          this.sessionExpired.emit();
        } else {
          this.state.set('unavailable');
          if (retrying) this.focus('[data-load-retry]');
        }
      },
    });
  }

  protected choosePattern(value: PatternChoice): void {
    if (value === 'MULTIPLE' || value === 'UNKNOWN') {
      this.setAnswer('curlPattern', { kind: value });
    } else if (value) {
      this.setAnswer('curlPattern', { kind: 'SINGLE', groups: [{ group: value }] });
    } else {
      this.setAnswer('curlPattern', undefined);
    }
  }

  protected toggleGroup(group: CurlGroup, checked: boolean): void {
    const groups = this.groups();
    if (checked && (groups.length >= 2 || groups.some(selection => selection.group === group))) return;
    this.setAnswer('curlPattern', {
      kind: 'MULTIPLE',
      groups: checked ? [...groups, { group }] : groups.filter(selection => selection.group !== group),
    });
  }

  protected subtypeOf(group: CurlGroup): CurlSubtype | '' {
    return this.groups().find(selection => selection.group === group)?.subtype ?? '';
  }

  protected chooseSubtype(group: CurlGroup, value: string): void {
    const pattern = this.answers().curlPattern;
    if (!pattern) return;
    this.setAnswer('curlPattern', {
      kind: pattern.kind,
      groups: this.groups().map(selection => selection.group === group
        ? { group, subtype: value ? value as CurlSubtype : undefined } : selection),
    });
  }

  protected chooseSingle(field: SingleField, value: string): void {
    this.setAnswer(field, value || undefined);
    if (field === 'heatFrequency' && value === 'NEVER') this.setAnswer('heatTools', undefined);
  }

  protected toggleOption(field: MultiField, value: string, checked: boolean): void {
    const current = this.answers()[field] ?? [];
    const values = !checked ? current.filter(option => option !== value) : isExclusive(value)
      ? [value] : [...current.filter(option => !isExclusive(option)), value];
    this.setAnswer(field, values.length ? values : undefined);
    if (field === 'chemicalTreatments' && !this.hasChemicalTreatment()) {
      this.setAnswer('lastChemicalTreatmentMonth', undefined);
    }
  }

  protected requestClear(): void {
    this.confirmingClear.set(true);
    this.focus('[data-cancel-clear]');
  }

  protected cancelClear(): void {
    this.confirmingClear.set(false);
    this.focus('[data-clear-profile]');
  }

  protected save(clear = false): void {
    if (this.saving() || this.state() !== 'ready' || (!clear && this.confirmingClear())) return;
    if (!clear && this.host.nativeElement.querySelector<HTMLInputElement>('#lastChemicalTreatmentMonth')?.validity.badInput) {
      this.errors.set({ lastChemicalTreatmentMonth: 'Preencha o mês e o ano ou deixe o campo em branco.' });
      this.focus('#lastChemicalTreatmentMonth');
      return;
    }
    this.confirmingClear.set(false);
    this.saving.set(true);
    this.errors.set({});
    this.feedback.set('');
    this.saveResult.set(null);
    putWithCsrf<Answers>(this.http, PROFILE_URL, clear ? {} : this.answers()).pipe(
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: answers => {
        this.receive(answers);
        this.saving.set(false);
        this.saveResult.set(clear ? 'cleared' : 'saved');
        this.focus('[data-save-status]');
      },
      error: (error: HttpErrorResponse) => {
        this.saving.set(false);
        if (error.status === 401) {
          this.sessionExpired.emit();
          return;
        }
        const errors: FieldErrors = error.status === 400 ? error.error?.errors ?? {} : {};
        this.errors.set(errors);
        if (this.hasErrors() && !errors.body) {
          this.focus('[data-profile-field][aria-invalid="true"]', '[data-save-feedback]');
        } else {
          this.feedback.set(errors.body ?? 'Não foi possível salvar as respostas. Tente novamente.');
          this.focus('[data-save-feedback]');
        }
      },
    });
  }

  private setAnswer<K extends keyof Answers>(field: K, value: Answers[K]): void {
    this.answers.update(answers => ({ ...answers, [field]: value }));
    this.errors.update(({ [field]: _error, ...remaining }) => remaining);
    this.feedback.set('');
    this.saveResult.set(null);
  }

  private receive(answers: Answers): void {
    this.answers.set(answers);
    this.savedAnswers.set(answers);
  }

  private focus(selector: string, fallback?: string): void {
    afterNextRender(() => {
      const host = this.host.nativeElement;
      (host.querySelector<HTMLElement>(selector) ?? (fallback ? host.querySelector<HTMLElement>(fallback) : null))?.focus();
    }, { injector: this.injector });
  }
}
