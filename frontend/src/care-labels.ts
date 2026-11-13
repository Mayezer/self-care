import { STAGES } from './hair-preview-draft';
import type { Stage } from './hair-preview-draft';

export const CATEGORY_LABELS = { HAIR: 'Cabelo', SKIN: 'Pele', MAKEUP: 'Maquiagem', NAILS: 'Unhas e esmalte' };
export const CATEGORY_ICONS = { HAIR: 'spa', SKIN: 'water_drop', MAKEUP: 'auto_awesome', NAILS: 'local_florist' };
export const STATUS_LABELS = { PLANNED: 'Planejada', COMPLETED: 'Concluída', SKIPPED: 'Pulada' };
export const STATUS_ICONS = { PLANNED: 'schedule', COMPLETED: 'check_circle', SKIPPED: 'redo' };

export interface CareDetails {
  category: keyof typeof CATEGORY_LABELS;
  stage: Stage | null;
  period: 'MORNING' | 'NIGHT' | null;
  note: string | null;
  polishColor: string | null;
  polishProduct: string | null;
}

export function careDetails(item: CareDetails, includeCategory = true): string {
  const stage = STAGES.find(stage => stage.value === item.stage)?.label;
  const period = item.period === 'MORNING' ? 'Manhã' : item.period === 'NIGHT' ? 'Noite' : null;
  return [includeCategory ? CATEGORY_LABELS[item.category] : null, stage, period,
    item.note && 'Notas: ' + item.note, item.polishColor && 'Cor: ' + item.polishColor,
    item.polishProduct && 'Produto: ' + item.polishProduct].filter(Boolean).join(' · ');
}
