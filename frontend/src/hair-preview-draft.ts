import { Router } from '@angular/router';

export const WEEKDAYS = [
  { value: 'MONDAY', label: 'Segunda-feira', short: 'Seg' },
  { value: 'TUESDAY', label: 'Terça-feira', short: 'Ter' },
  { value: 'WEDNESDAY', label: 'Quarta-feira', short: 'Qua' },
  { value: 'THURSDAY', label: 'Quinta-feira', short: 'Qui' },
  { value: 'FRIDAY', label: 'Sexta-feira', short: 'Sex' },
  { value: 'SATURDAY', label: 'Sábado', short: 'Sáb' },
  { value: 'SUNDAY', label: 'Domingo', short: 'Dom' },
] as const;

export const STAGES = [
  { value: 'WASH_ONLY', label: 'Somente lavagem' },
  { value: 'HYDRATION', label: 'Hidratação' },
  { value: 'NUTRITION', label: 'Nutrição' },
  { value: 'RECONSTRUCTION', label: 'Reconstrução' },
] as const;

export type Weekday = (typeof WEEKDAYS)[number]['value'];
export type Stage = (typeof STAGES)[number]['value'];
export type HairGoal = 'ORGANIZE' | Exclude<Stage, 'WASH_ONLY'>;

export interface HairSuggestionOptions {
  goal?: HairGoal;
  useProfile: boolean;
}

export interface PreviewOccurrence {
  date: string;
  stage: Stage;
}

export interface HairPreviewDraft {
  startDate: string;
  weeks: number;
  weekdays: readonly Weekday[];
  occurrences: readonly PreviewOccurrence[];
}

export interface HairPlanRequest extends HairPreviewDraft {
  requestId: string;
}

export interface HairPreviewState extends HairPreviewDraft {
  requestId?: string;
  suggestion?: HairSuggestionOptions;
  suggestionReason?: string;
  editedDates?: readonly string[];
}

const dateLabel = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'full', timeZone: 'UTC' });

export function formatHairDate(date: string): string {
  return dateLabel.format(new Date(`${date}T00:00:00Z`));
}

export function readHairPreviewDraft(router: Router): HairPreviewState | undefined {
  return router.currentNavigation()?.extras.state?.['hairPreviewDraft'];
}
