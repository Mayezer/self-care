export const CURL_GROUPS = [
  {
    value: 'STRAIGHT', label: 'Liso', path: 'M3 14h30',
    subtypes: [
      { code: '1A', caption: 'Fio liso e fino.', path: 'M3 13h42', width: 1 },
      { code: '1B', caption: 'Fio liso de espessura média.', path: 'M3 13h42', width: 2 },
      { code: '1C', caption: 'Fio liso e grosso.', path: 'M3 13h42', width: 3 },
    ],
  },
  {
    value: 'WAVY', label: 'Ondulado', path: 'M3 14q4-10 8 0t8 0t8 0q3 6 6 0',
    subtypes: [
      { code: '2A', caption: 'Ondas suaves e abertas em S.', path: 'M3 13q5-5 10 0t10 0t10 0t12 0', width: 1.6 },
      { code: '2B', caption: 'Ondas em S mais marcadas.', path: 'M3 13q5-10 10 0t10 0t10 0t12 0', width: 1.6 },
      { code: '2C', caption: 'Ondas profundas que podem formar voltas nas pontas.', path: 'M3 13q5-17 10 0t10 0t10 0c5-14 15-8 12 1', width: 1.6 },
    ],
  },
  {
    value: 'CURLY', label: 'Cacheado', path: 'M3 16c6 8 13-8 7-9s-1 14 6 9 8-12 4-10-2 16 5 12 11-14 6-12-2 13 2 12',
    subtypes: [
      { code: '3A', caption: 'Cachos largos e abertos.', path: 'M3 19c6 8 19-13 12-15s-3 21 9 15 16-15 10-15-4 22 11 15', width: 1.6 },
      { code: '3B', caption: 'Cachos em espirais menores e mais fechadas.', path: 'M3 18c6 6 13-12 8-12s-3 19 5 12 12-12 8-12-3 19 5 12 13-12 9-12-2 19 7 12', width: 1.6 },
      { code: '3C', caption: 'Espirais estreitas, próximas umas das outras.', path: 'M3 17q8-14 5-10t3 10q8-14 5-10t3 10q8-14 5-10t3 10q8-14 5-10t3 10q8-14 5-10t3 10', width: 1.6 },
    ],
  },
  {
    value: 'COILY', label: 'Crespo', path: 'M3 16l3-7 3 10 3-10 3 10 3-10 3 10 3-10 3 10 3-10 3 7',
    subtypes: [
      { code: '4A', caption: 'Voltas pequenas, bem fechadas e visíveis.', path: 'M3 17c6-4 5-14 2-9s2 16 7 9 5-14 2-9 2 16 7 9 5-14 2-9 2 16 7 9 5-14 2-9 2 16 7 9 5-14 2-9 1 12 4 9', width: 1.6 },
      { code: '4B', caption: 'Dobras apertadas com ângulos em zigue-zague.', path: 'M3 17l4-10 2 13 5-12 2 12 5-12 2 12 5-12 2 12 5-12 2 12 5-12 3 9', width: 1.6 },
      { code: '4C', caption: 'Voltas ou dobras muito fechadas, difíceis de distinguir a olho nu.', path: 'M3 17l3-9 1 10 4-12 1 13 4-11 1 10 4-12 1 13 4-11 1 10 4-12 1 13 4-11 1 10 4-12 1 13 3-7', width: 1.6 },
    ],
  },
] as const;

type CurlGroupEntry = typeof CURL_GROUPS[number];
export type CurlGroup = CurlGroupEntry['value'];
export type CurlSubtype = CurlGroupEntry['subtypes'][number]['code'];
