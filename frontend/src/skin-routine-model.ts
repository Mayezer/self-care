export const SKIN_PERIODS = [
  { value: 'MORNING', label: 'Manhã', title: 'Rotina de pele da manhã' },
  { value: 'NIGHT', label: 'Noite', title: 'Rotina de pele da noite' },
] as const;

export type SkinPeriod = typeof SKIN_PERIODS[number]['value'];
export type SkinTiming = '' | SkinPeriod | 'BOTH';
export type SkinTime = '' | 'SHORT' | 'FLEXIBLE';
export type SkinHabit = '' | 'MAKEUP' | 'NO_MAKEUP';

interface RoutineTemplate {
  id: string;
  version: number;
  reviewedOn: string | null;
  steps: readonly string[];
}

// Revisão editorial da E3-06: cuidados básicos, sem inferir tipo de pele ou tratamentos.
const INTRODUCTORY: Record<SkinPeriod, RoutineTemplate> = {
  MORNING: {
    id: 'skin-basics-morning', version: 1, reviewedOn: '2026-11-13',
    steps: ['Limpeza suave', 'Hidratação', 'Proteção solar'],
  },
  NIGHT: {
    id: 'skin-basics-night', version: 1, reviewedOn: '2026-11-13',
    steps: ['Limpeza suave', 'Hidratação'],
  },
};

const MAKEUP_REMOVAL: RoutineTemplate = {
  id: 'skin-makeup-removal', version: 1, reviewedOn: '2026-11-13',
  steps: ['Remover a maquiagem'],
};

export function suggestSkinRoutine(timing: SkinTiming, time: SkinTime, habit: SkinHabit,
                                   templates = INTRODUCTORY) {
  const periods = timing === 'BOTH' ? SKIN_PERIODS : SKIN_PERIODS.filter(
    period => period.value === (timing || 'MORNING'));
  if (periods.some(period => !templates[period.value]?.reviewedOn)) return null;
  const addsRemoval = habit === 'MAKEUP' && time === 'FLEXIBLE' && !!MAKEUP_REMOVAL.reviewedOn
    && periods.some(period => period.value === 'NIGHT');

  return {
    reason: (timing ? 'A proposta usa uma sequência básica para os períodos escolhidos.'
      : 'Como você pulou o período, a proposta começa pela manhã.')
      + (addsRemoval ? ' À noite, incluímos remover a maquiagem porque você marcou esse hábito.' : ''),
    periods: periods.map(period => ({
      period: period.value,
      title: period.title,
      steps: [
        ...(addsRemoval && period.value === 'NIGHT' ? MAKEUP_REMOVAL.steps : []),
        ...templates[period.value].steps,
      ],
    })),
  };
}
