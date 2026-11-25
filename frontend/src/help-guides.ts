interface Source {
  title: string;
  url: string;
}

interface Tutorial {
  title: string;
  objective: string;
  video: Source;
}

export interface Guide {
  id: string;
  title: string;
  summary: string;
  paragraphs?: readonly string[];
  tutorials?: readonly Tutorial[];
  stepsTitle?: string;
  steps?: readonly string[];
  sources: readonly Source[];
}

const cosmeticsLabel = {
  title: 'Anvisa · Rotulagem de cosméticos',
  url: 'https://www.gov.br/anvisa/pt-br/setorregulado/regularizacao/cosmeticos/rotulagem',
};

export const GUIDES = {
  curl: {
    id: 'curvatura',
    title: 'Curvatura do cabelo',
    summary: 'Compare 12 exemplos ilustrados e leia a legenda de cada subtipo.',
    paragraphs: [
      'Observe o cabelo seco no caimento habitual. O desenho pode variar entre partes da cabeça ou depois de uma transformação. Em Meu cabelo, você pode escolher até dois grupos em Mais de um padrão, marcar Não sei ou deixar em branco.',
      'As ilustrações são esquemas de um fio. A espessura e a oleosidade da raiz têm campos próprios no perfil. Use a curvatura como descrição visual.',
    ],
    sources: [{
      title: 'Callender e colaboradores · Revisão de classificações de curvatura',
      url: 'https://onlinelibrary.wiley.com/doi/10.1111/jocd.70828',
    }, {
      title: 'Unilever · Guia de representação de curvaturas',
      url: 'https://www.unilever.com/files/92ui5egz/production/f61d45ed20483597f9ce477e242ba768760074ac.pdf',
    }, {
      title: 'Van den Berg e colaboradores · Medição da curvatura e da fibra',
      url: 'https://pubmed.ncbi.nlm.nih.gov/39564786/',
    }],
  },
  heat: {
    id: 'calor',
    title: 'Calor e proteção térmica',
    summary: 'Cuidados ao usar secador, chapinha ou modelador.',
    paragraphs: [
      'Calor excessivo pode danificar o fio. Se usar uma ferramenta térmica, reduza o tempo de contato e use a menor temperatura que atenda à finalidade. Use chapinha no cabelo seco e siga o manual do aparelho.',
      'Se usar um protetor térmico, consulte o rótulo para saber quanto aplicar e se o produto deve ir no cabelo seco ou úmido. Mesmo com proteção, vale reduzir a exposição ao calor.',
    ],
    sources: [{
      title: 'Academia Americana de Dermatologia · Hábitos que podem danificar o cabelo',
      url: 'https://www.aad.org/public/diseases/hair-loss/insider/stop-damage',
    }, {
      title: 'Academia Americana de Dermatologia · Como modelar o cabelo com menos dano',
      url: 'https://www.aad.org/public/diseases/hair-loss/hair-care/styling/',
    }, cosmeticsLabel],
  },
  chemistry: {
    id: 'quimica',
    title: 'Química no cabelo',
    summary: 'O que conferir antes de uma coloração, descoloração ou alisamento.',
    paragraphs: [
      'Guarde o nome do procedimento e, se souber, o mês em que o fez. Se for fazer outro, informe o que já aplicou e confirme a compatibilidade com um profissional. Para alisantes, consulte a regularização do produto na Anvisa.',
      'Confira as advertências e siga o modo de uso do rótulo. Ressecamento, quebra, pontas duplas e frizz são observações que você pode registrar separadamente em Meu cabelo.',
    ],
    sources: [{
      title: 'Anvisa · Produtos alisantes e ondulantes',
      url: 'https://www.gov.br/anvisa/pt-br/comunicacao/campanhas/estetica/produtos-alisantes-e-ondulantes-para-cabelo',
    }, cosmeticsLabel],
  },
  elasticity: {
    id: 'elasticidade',
    title: 'O que é elasticidade do fio?',
    summary: 'Entenda a diferença entre observar um fio e medir sua elasticidade.',
    paragraphs: [
      'Elasticidade é a capacidade do fio de se alongar sob força e voltar em direção à forma inicial quando a força cessa. O comportamento muda conforme o fio está seco ou molhado.',
      'Puxar um fio com os dedos dá uma impressão de como ele reage naquela situação. Um ensaio de tração controla condições como umidade, comprimento da amostra e velocidade do alongamento e mede a força aplicada.',
      'Uma observação caseira é insuficiente para classificar o cabelo ou indicar uma etapa de cuidado. O perfil guarda apenas as observações que você escolhe informar.',
    ],
    sources: [{
      title: 'Wortmann e colaboradores · Ensaios de tração em fios secos e molhados',
      url: 'https://onlinelibrary.wiley.com/doi/10.1111/ics.12796',
    }, {
      title: 'Sociedade Japonesa de Engenheiros Mecânicos · Condições de ensaio em um único fio',
      url: 'https://www.jstage.jst.go.jp/article/transjsme/86/890/86_20-00043/_article/-char/en',
    }],
  },
  porosity: {
    id: 'porosidade',
    title: 'O que o teste do copo tenta mostrar?',
    summary: 'Entenda porosidade e por que boiar ou afundar diz pouco sobre o fio.',
    paragraphs: [
      'Porosidade descreve os espaços acessíveis dentro da fibra. Ela ajuda a entender como substâncias podem entrar no fio. Em laboratório, é possível medir o volume e o tamanho dos poros com técnicas controladas.',
      'O teste do copo tenta inferir a porosidade observando se um fio boia ou afunda. A tensão superficial da água e resíduos de produtos no fio também podem mudar esse comportamento. O resultado do copo é insuficiente para classificar o cabelo ou escolher um tratamento.',
    ],
    sources: [{
      title: 'Journal of Cosmetic Science · Medição da porosidade do fio',
      url: 'https://pubmed.ncbi.nlm.nih.gov/18818850/',
    }, {
      title: 'Universidade de Manchester · Caracterização do fio e molhabilidade',
      url: 'https://research.manchester.ac.uk/en/studentTheses/characterisation-of-human-hair-and-the-effects-of-chemical-treatm/',
    }],
  },
  stages: {
    id: 'etapas-do-cronograma',
    title: 'Etapas do cronograma',
    summary: 'Veja como escolher os dias e o que significam as etapas opcionais.',
    paragraphs: [
      'Na prévia, escolha de 1 a 7 dias diferentes da semana. Na montagem manual, cada sessão começa em Lavagem e condicionamento. Em Receber sugestão, os cuidados de cada lavagem são adaptados às respostas salvas em Meu cabelo. Confira a rotina em cada data e ajuste os cuidados extras como preferir.',
      'Hidratação, nutrição e reconstrução são opções para organizar cuidados escolhidos por você. O perfil sozinho não define intervalos seguros para esses tratamentos. A sugestão do app adapta o condicionamento, o manuseio e as orientações de cuidado, sem escolher produtos.',
      'Antes de usar um produto, confira a região de aplicação, o tempo de pausa e a orientação sobre enxágue no rótulo. Escolha os dias e as etapas que cabem na sua rotina.',
    ],
    sources: [
      cosmeticsLabel,
      { title: 'Anvisa · Consultar a regularização de um cosmético', url: 'https://www.gov.br/anvisa/pt-br/comunicacao/campanhas/estetica/consulta-de-cosmeticos' },
      { title: 'Academia Americana de Dermatologia · Cuidados com o cabelo', url: 'https://www.aad.org/public/everyday-care/hair-scalp-care/hair/healthy-hair-tips' },
      { title: 'Academia Americana de Dermatologia · Cuidados com cachos e crespos', url: 'https://www.aad.org/public/everyday-care/hair-scalp-care/hair/curly-hair-care' },
    ],
  },
  skin: {
    id: 'pele',
    title: 'Planejar um cuidado de pele',
    summary: 'Monte um cuidado avulso ou recorrente na agenda.',
    steps: [
      'Uma rotina simples pode incluir limpeza suave, hidratação e proteção solar. Confira o modo de uso de cada produto. Para protetor solar, leia também as instruções de reaplicação.',
      'Na agenda, abra Adicionar cuidado e escolha Pele. Use o modo avulso para um dia ou a recorrência para repetir em dias escolhidos.',
      'Depois de fazer o cuidado, registre a data em Concluir.',
    ],
    sources: [{
      title: 'Academia Americana de Dermatologia · Rotina simples de pele',
      url: 'https://www.aad.org/public/everyday-care/skin-care-basics/care/skin-care-budget',
    }, {
      title: 'Anvisa · Manual de protetor solar',
      url: 'https://www.gov.br/anvisa/pt-br/centraisdeconteudo/publicacoes/cosmeticos/manuais-e-guias/manual-de-protetor-solar.pdf',
    }, cosmeticsLabel],
  },
  makeup: {
    id: 'maquiagem',
    title: 'Maquiagem',
    summary: 'Aprenda técnicas para iniciantes e veja como registrar a maquiagem na agenda.',
    tutorials: [{
      title: 'Prepare a pele e aplique a base',
      objective: 'Pratique espalhar uma camada fina de base e acrescentar um pouco mais onde quiser maior cobertura.',
      video: {
        title: 'Preparação de pele para maquiagem · Alice Salazar no YouTube',
        url: 'https://www.youtube.com/watch?v=oKPSXBqD5ZY',
      },
    }, {
      title: 'Esfume a sombra',
      objective: 'Comece com uma cor na pálpebra e suavize a borda com um pincel limpo. Use um produto indicado para a área dos olhos.',
      video: {
        title: 'Aprenda a esfumar · Camila Nunes no YouTube',
        url: 'https://www.youtube.com/watch?v=X0s4adK3nNc',
      },
    }, {
      title: 'Finalize com blush',
      objective: 'Aplique um pouco de blush cremoso nas maçãs do rosto e espalhe com batidinhas até chegar à intensidade que você quer.',
      video: {
        title: 'Como aplicar blush cremoso · Débora Bitencourt no canal Lilian Pacce no YouTube',
        url: 'https://www.youtube.com/watch?v=IpoOZ6jerds',
      },
    }],
    stepsTitle: 'Planejar ou registrar maquiagem',
    steps: [
      'Lave as mãos antes de aplicar e use apenas produtos indicados para a área dos olhos. Reserve a maquiagem e os aplicadores para uso pessoal.',
      'Para limpar pincéis reutilizáveis, lave as cerdas com água morna e xampu suave sem mergulhar o cabo. Enxágue e deixe os pincéis secarem deitados antes de guardar.',
      'Na agenda, selecione Adicionar cuidado e depois Maquiagem. Escolha uma data para planejar ou Já realizado para salvar no histórico.',
    ],
    sources: [{
      title: 'FDA · Uso seguro de maquiagem para os olhos',
      url: 'https://www.fda.gov/cosmetics/resources-consumers-cosmetics/cosmetics-safety-qa-eye-cosmetic-safety',
    }, {
      title: 'Academia Americana de Dermatologia · Como limpar pincéis de maquiagem',
      url: 'https://www.aad.org/public/everyday-care/skin-care-secrets/routine/clean-your-makeup-brushes',
    }],
  },
  nails: {
    id: 'unhas-e-esmalte',
    title: 'Planejar ou registrar unhas e esmalte',
    summary: 'Encontre as opções de agendamento e de registro direto.',
    steps: [
      'Use instrumentos limpos e leia as instruções e advertências do esmalte ou removedor. Alguns produtos pedem ventilação ou distância de fontes de calor.',
      'Na agenda, abra Adicionar cuidado e escolha Unhas e esmalte. Informe a data futura ou de hoje para agendar, ou escolha Já realizado e informe a data do cuidado.',
    ],
    sources: [{
      title: 'Academia Americana de Dermatologia · Cuidados em manicure e pedicure',
      url: 'https://www.aad.org/public/everyday-care/nail-care-secrets/basics/pedicures/manicure-pedicure-safety',
    }, {
      title: 'FDA · Uso seguro de produtos para unhas',
      url: 'https://www.fda.gov/cosmetics/cosmetic-products/nail-care-products',
    }],
  },
} as const satisfies Record<string, Guide>;

export const HELP_GUIDES = Object.values(GUIDES);
