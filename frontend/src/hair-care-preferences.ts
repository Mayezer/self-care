import { Component, input, output } from '@angular/core';

export type ExtraHairStage = 'HYDRATION' | 'NUTRITION' | 'RECONSTRUCTION';
type TreatmentStatus = 'UNKNOWN' | 'KNOWN' | 'FIRST_USE' | 'AVOID';
type IntervalUnit = 'DAYS' | 'WEEKS' | 'MONTHS';
export interface HairTreatmentPreference {
  status: TreatmentStatus;
  lastUse?: string;
  every?: number;
  unit?: IntervalUnit;
}
export interface HairCarePreferencesData {
  washesPerWeek?: number;
  scalpSymptoms?: 'YES' | 'NO' | 'UNKNOWN';
  treatments?: Partial<Record<ExtraHairStage, HairTreatmentPreference>>;
}

@Component({
  selector: 'app-hair-care-preferences',
  styles: `
    :host { display: block; min-width: 0; }
    fieldset { display: grid; gap: var(--space-md); }
    details { border-top: 1px solid var(--line); padding-block: var(--space-sm); }
    summary { min-height: var(--touch); padding-block: 0.75rem; cursor: pointer; font-weight: 600; }
    .care-fields { display: grid; gap: var(--space-md); padding-top: var(--space-sm); }
    .care-interval { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: var(--space-sm); }
  `,
  template: `
    <fieldset [disabled]="disabled()" data-profile-field="careSchedule" tabindex="-1"
      [attr.aria-invalid]="error() ? 'true' : null"
      [attr.aria-describedby]="error() ? 'care-schedule-hint care-schedule-error' : 'care-schedule-hint'">
      <legend>Rotina de lavagem e cuidados extras</legend>
      <p class="hint" id="care-schedule-hint">Estas respostas ajudam a sugerir dias e respeitar os intervalos dos seus cuidados.
        Não é preciso cadastrar marcas ou produtos. Sem resposta continua sendo desconhecido.</p>
      <div class="field">
        <label for="care-washes">Quantas lavagens por semana deixam seu couro cabeludo confortável?</label>
        <select #washes id="care-washes" (change)="frequency(washes.value)">
          <option value="" [selected]="value()?.washesPerWeek === undefined">Sem resposta ou outra frequência</option>
          @for (count of counts; track count) { <option [value]="count" [selected]="value()?.washesPerWeek === count">{{ count }} {{ count === 1 ? 'lavagem' : 'lavagens' }} por semana</option> }
        </select>
        <p class="hint">Considere a frequência que já funciona para você. Oleosidade, suor e resíduos podem mudar sua necessidade de limpeza.</p>
      </div>
      <div class="field">
        <label for="care-symptoms">Há irritação, feridas ou queda súbita ou em falhas?</label>
        <select #symptoms id="care-symptoms" [value]="value()?.scalpSymptoms ?? 'UNKNOWN'" (change)="scalp(symptoms.value)">
          <option value="UNKNOWN">Não sei ou não informado</option>
          <option value="NO">Não</option><option value="YES">Sim</option>
        </select>
        <p class="hint">Se sim, procure avaliação dermatológica. A sugestão mantém a rotina básica e não programa tratamentos extras.</p>
      </div>
      @for (care of treatments; track care.stage) {
        @let item = value()?.treatments?.[care.stage];
        <details>
          <summary>{{ care.label }}</summary>
          <div class="care-fields">
            <div class="field">
              <label [for]="'care-status-' + care.stage">Como é seu uso de {{ care.label.toLowerCase() }}?</label>
              <select #status [id]="'care-status-' + care.stage" [value]="item?.status ?? 'UNKNOWN'" (change)="state(care.stage, status.value)">
                <option value="UNKNOWN">Não sei ou não informado</option>
                <option value="KNOWN">Já uso e tolero bem</option>
                <option value="FIRST_USE">Primeiro uso, com orientação de uso conhecida</option>
                <option value="AVOID">Já houve reação ou piora — não sugerir</option>
              </select>
            </div>
            @if (item?.status === 'AVOID') {
              <p class="hint">Este cuidado ficará fora da sugestão. Não tente compensar uma reação aumentando os tratamentos.</p>
            }
            @if (item?.status === 'KNOWN' || item?.status === 'FIRST_USE') {
              @if (item?.status === 'KNOWN') {
                <div class="field">
                  <label [for]="'care-last-' + care.stage">Última aplicação de {{ care.label.toLowerCase() }}</label>
                  <input #last [id]="'care-last-' + care.stage" type="date" [max]="today()" required data-care-input
                    [value]="item?.lastUse ?? ''" (input)="treatment(care.stage, { lastUse: last.value || undefined })">
                </div>
              }
              <div class="care-interval">
                <div class="field">
                  <label [for]="'care-every-' + care.stage">Intervalo {{ care.stage === 'HYDRATION' ? '(se já orientado)' : 'de uso conhecido' }}</label>
                  <input #every [id]="'care-every-' + care.stage" type="number" min="1" [max]="maximum(item?.unit)" data-care-input
                    [required]="care.stage !== 'HYDRATION'" [value]="item?.every ?? ''"
                    (input)="interval(care.stage, every.value)">
                </div>
                <div class="field">
                  <label [for]="'care-unit-' + care.stage">Unidade do intervalo</label>
                  <select #unit [id]="'care-unit-' + care.stage" [value]="item?.unit ?? 'DAYS'" (change)="unitOf(care.stage, unit.value)">
                    <option value="DAYS">Dias</option><option value="WEEKS">Semanas</option><option value="MONTHS">Meses</option>
                  </select>
                </div>
              </div>
              <p class="hint">{{ care.stage === 'HYDRATION'
                ? 'Sem outro intervalo informado, a sugestão semanal só será oferecida para ressecamento relatado. O histórico continua sendo considerado.'
                : 'Informe o intervalo que você já tolera ou recebeu de um profissional. O app organiza as datas; não deduz esse intervalo pela curvatura ou pelo frizz.' }}</p>
              <p class="hint">Se a orientação for por número de lavagens, não converta para dias. Deixe como não informado e organize esse cuidado manualmente.</p>
            }
          </div>
        </details>
      }
      @if (error(); as message) { <p id="care-schedule-error" class="field-error">{{ message }}</p> }
    </fieldset>
  `,
})
export class HairCarePreferences {
  readonly value = input<HairCarePreferencesData>();
  readonly today = input.required<string>();
  readonly disabled = input(false);
  readonly error = input<string>();
  readonly changed = output<HairCarePreferencesData>();
  protected readonly counts = [1, 2, 3, 4, 5, 6, 7];
  protected readonly treatments: { stage: ExtraHairStage; label: string }[] = [
    { stage: 'HYDRATION', label: 'Hidratação' }, { stage: 'NUTRITION', label: 'Nutrição' },
    { stage: 'RECONSTRUCTION', label: 'Reconstrução' },
  ];

  protected frequency(value: string): void { this.changed.emit({ ...this.value(), washesPerWeek: value ? Number(value) : undefined }); }
  protected scalp(value: string): void { this.changed.emit({ ...this.value(), scalpSymptoms: value as HairCarePreferencesData['scalpSymptoms'] }); }
  protected state(stage: ExtraHairStage, status: string): void {
    const previous = this.value()?.treatments?.[stage];
    this.treatment(stage, status === 'KNOWN' || status === 'FIRST_USE'
      ? { ...previous, status, lastUse: status === 'FIRST_USE' ? undefined : previous?.lastUse }
      : { status: status as TreatmentStatus }, true);
  }
  protected treatment(stage: ExtraHairStage, patch: Partial<HairTreatmentPreference>, replace = false): void {
    const item = { ...(replace ? {} : this.value()?.treatments?.[stage]), ...patch } as HairTreatmentPreference;
    this.changed.emit({ ...this.value(), treatments: { ...this.value()?.treatments, [stage]: item } });
  }
  protected interval(stage: ExtraHairStage, text: string): void {
    this.treatment(stage, { every: text ? Number(text) : undefined,
      unit: text ? this.value()?.treatments?.[stage]?.unit ?? 'DAYS' : undefined });
  }
  protected unitOf(stage: ExtraHairStage, unit: string): void {
    this.treatment(stage, { unit: unit as IntervalUnit });
  }
  protected maximum(unit?: IntervalUnit): number { return unit === 'MONTHS' ? 12 : unit === 'WEEKS' ? 52 : 365; }
}
