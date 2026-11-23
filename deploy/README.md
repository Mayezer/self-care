# Publicação da Agenda de autocuidado

Esta pasta versiona fontes de publicação, testes e exemplos sem credenciais, com uma lista explícita no `.gitignore`. O caminho Compose/SSH e o candidato Cloudflare Containers têm procedimentos separados. Guarde a configuração efetiva, os certificados, as chaves e o estado no armazenamento protegido do ambiente.

## Preparar o candidato Cloudflare Containers

O candidato preserva Spring Boot, JDBC, Flyway e Nginx. Um Worker encaminha o domínio canônico para uma única instância identificada de Durable Object, que controla o contêiner. Java escuta somente em loopback; o Nginx exige uma credencial de origem independente da chave de MFA. O Worker substitui cabeçalhos de encaminhamento e o proxy aplica as cotas pelo IP recebido da borda. [Arquitetura de Containers](https://developers.cloudflare.com/containers/concepts/architecture/)

`wrangler.example.json` é um exemplo de homologação: não possui rotas públicas, desliga `workers.dev` e previews e usa tags locais de build para o ensaio. O workflow **Publicação**, descrito abaixo, continua sendo Compose/SSH; ele não publica na Cloudflare. O dry-run valida o bundle e o modelo do Wrangler, sem comprovar ativação do contêiner, TLS, SMTP ou rollback no provedor.

O runtime usa um perfil explícito de 1 vCPU, 1024 MiB de memória e 2000 MB de disco, correspondente ao orçamento do ensaio Java. O prazo de inatividade é de 35 minutos, superior aos 30 minutos de inatividade da sessão, e é restaurado quando o Durable Object reinicia. A parada envia SIGTERM e espera até 30 segundos antes de forçar `destroy()`. Revalidar latência de partida, tarefas agendadas, sessões e custos no candidato antes de abrir o acesso. [Recursos e perfis](https://developers.cloudflare.com/containers/platform/limits/) · [Ciclo de vida nativo](https://developers.cloudflare.com/containers/api/durable-object-container/)

O supervisor encaminha stdout/stderr por um FIFO privado para permitir a reabertura do log pelo Nginx no runtime nativo, sem acumular logs em arquivo. A saída segue para o coletor da plataforma. Falha de Java, Nginx ou do encaminhamento encerra a instância inteira; SIGTERM aguarda o encerramento dos processos.

Na política nativa `durable_object`, o `user` de `exec()` não cria uma fronteira de permissões Linux: a plataforma documenta capacidades equivalentes às de root dentro da VM. O UID 10001 do Docker local não comprova essa separação na Cloudflare. O Worker da aplicação não expõe execução remota de comandos; as credenciais de migração permanecem fora do runtime. Inclua essa característica da plataforma na análise de segurança do ambiente.

1. Use Workers Paid e um projeto Neon dedicado ao ambiente. Conserve o papel de migração separado de `autocuidado_runtime`; o runtime não pode criar schema, alterar Flyway, truncar tabelas ou provisionar o proprietário. Use conexão direta para migrações e para este runtime JDBC; a aplicação mantém seu próprio pool Hikari limitado a cinco conexões. O controlador recusa o hostname `-pooler`: no ensaio concorrente desta aplicação, o caminho transacional herdou isolamento de sessão e retornou erros de serialização; a conexão direta preservou os níveis de isolamento e eliminou essas falhas. Verifique certificado e hostname com `sslmode=verify-full` e `sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory`. Não reutilize credenciais administrativas na aplicação. [Limites do pool transacional Neon](https://neon.com/docs/connect/connection-pooling)
2. Construa API e interface a partir da mesma revisão aprovada pelo CI, com a origem HTTPS final no build Angular. Em uma cópia protegida do exemplo, substitua `WEB_IMAGE` e `API_IMAGE` por referências imutáveis, disponíveis ao build. `Dockerfile.cloudflare` combina esses artefatos, executa como UID 10001 e usa `start-cloudflare.sh` para encerrar a instância quando Java ou Nginx falhar. A imagem combinada também precisa passar na triagem de vulnerabilidades. Tags locais do ensaio não são uma entrega aprovada de produção.
3. Aplique as migrações pela imagem da API em um job separado, antes da ativação. Use `--migrate`, o papel `autocuidado_migration`, conexão direta e variáveis fornecidas pelo armazenamento protegido. O runtime Cloudflare mantém Flyway desligado. Preserve as imagens aprovadas e teste sua compatibilidade com o schema atualizado; rollback de imagem não reverte migrações.
4. Configure `PUBLIC_SITE_ORIGIN` e o remetente verificado `AUTOCUIDADO_SMTP_FROM`. Carregue via `wrangler secret put` os nomes `AUTOCUIDADO_DB_RUNTIME_URL`, `AUTOCUIDADO_DB_RUNTIME_PASSWORD`, `AUTOCUIDADO_SMTP_PASSWORD`, `AUTOCUIDADO_MFA_KEY` e `AUTOCUIDADO_EDGE_TOKEN`, usando o arquivo protegido de configuração com `--config`. A URL JDBC não inclui usuário nem senha. As duas chaves são valores distintos de 32 bytes em hexadecimal. Conserve a chave MFA em armazenamento recuperável, separado do backup do banco. Não inclua valores em argumentos de shell, código ou logs.
5. Antes de anexar uma rota pública, mantenha a homologação sob controle de acesso e valide a identidade, entrega SMTP, IP efetivo, proteção da origem, HTTPS, CSP, cookies, cotas, alarmes, recuperação do banco e SEO. O cadastro começa fechado e os módulos opcionais desligados. Retenção, RPO/RTO e política de rede do banco continuam sendo decisões do candidato; TLS com endpoint público, sozinho, não comprova rede privada.

Com o Node indicado em `.nvmrc`, execute na raiz:

```sh
node deploy/test_cloudflare.mjs
npx --yes wrangler@4.165.0 deploy --config deploy/wrangler.example.json --dry-run
```

A suíte Node cobre cabeçalhos forjados, host, HTTPS, configuração protegida, privilégio do runtime, partida concorrente, cinco sondas antes da promoção e recuperação de falhas. O controlador guarda somente imagens e a impressão SHA-256 da configuração, sem persistir credenciais no estado do Durable Object. Uma atualização reprovada restaura a imagem anterior apenas se a configuração protegida for a mesma. Falha no primeiro deploy ou após troca de credenciais mantém a aplicação parada; uma candidata reprovada não é reiniciada a cada request. Retestar essas decisões no provedor antes de aprovar a publicação.

## Testar as decisões da publicação

Na raiz do repositório, execute com Python 3.10+ em Linux:

```sh
python3 -B -m unittest discover -s deploy -p 'test_*.py' -v
```

O CI executa as suítes Python e Node. A suíte Python usa somente a biblioteca padrão e cobre deploy Compose, rollback manual, atualização reprovada, primeiro deploy reprovado e migração reprovada, além da falha de inicialização e da recusa de imagens inválidas ou rollback sem revisão anterior. Docker e respostas HTTPS são simulados; os arquivos de estado são reais, isolados e removidos ao final. A suíte verifica que o estado só avança após as cinco sondas e que rollback não migra. O ensaio no host candidato continua necessário para validar imagens, banco e HTTPS reais.

## Preparar o host e o GitHub

O workflow manual **Publicação** usa um host Linux x86-64 com Docker Compose, Python 3.10+, `curl` e acesso SSH. Prepare os ambientes `homologacao` e `producao` no GitHub com hosts, bancos, remetentes e segredos próprios. Mantenha a homologação atrás de controle de acesso e associe a proteção da `main` e a aprovação da produção à operação do projeto.

1. Instale `release.py`, `compose.yaml` e `nginx.publicacao-tls.conf` em `/opt/autocuidado/deploy`. Mantenha os arquivos protegidos e dê à operação permissão de gravar no diretório de estado. Trate a conta com acesso ao Docker como privilegiada. Use uma chave SSH dedicada por ambiente e sem encaminhamento. Verifique a chave pública do host por um canal independente antes de cadastrá-la no GitHub.
2. A partir dos exemplos desta pasta, configure `/etc/autocuidado/release.json` e `/etc/autocuidado/publicacao.env` no host. Restrinja o arquivo de ambiente à operação. Configure no GitHub as variáveis `DEPLOY_SSH_HOST` e `DEPLOY_SSH_USER` e os segredos `DEPLOY_SSH_KEY` e `DEPLOY_SSH_KNOWN_HOSTS` por ambiente. Autorize o host a baixar as imagens do GHCR, com acesso apenas de leitura para pacotes privados. [GitHub Container registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
3. Prepare um banco dedicado em rede privada e as credenciais distintas `autocuidado_migration` e `autocuidado_runtime`. Aplique os privilégios de `backend/db-roles.sql` com a operação do provedor. Use o nome do host coberto pelo certificado e a CA do provedor. Guarde a chave de MFA em armazenamento de segredos recuperável, separado dos backups do PostgreSQL.
4. Configure as rotas DNS, o certificado da origem, a proteção de acesso direto na Cloudflare e a confiança no IP encaminhado pela borda. As respostas privadas e o HTML com nonce precisam manter `no-store`. No primeiro ensaio, faça a sonda pelo endereço do host configurado em `smoke_address` e valide o certificado para o nome em `smoke_origin`. Use `smoke_ca_file` quando a origem estiver assinada por CA própria. Defina alertas na borda e restrinja o acesso à origem.

## Implantar e reverter

1. Na `main`, execute **Publicação → deploy** em `homologacao` e informe a origem HTTPS canônica. Marque a publicação editorial dos guias após a revisão desse conteúdo. O workflow exige o CI da revisão, constrói as imagens, publica no GHCR e entrega seus digests ao host. O host aplica Flyway pelo papel de migração e ativa as imagens com o papel de execução. [Metadados do Buildx](https://docs.docker.com/reference/cli/docker/buildx/build/#metadata-file)
2. Confirme a disponibilidade e os fluxos por HTTPS. O script verifica a home, a prontidão do banco, a recusa da Agenda anônima, a prévia e a flag de leitura pública do fórum. Use contas fictícias com MFA para verificar também Agenda, Histórico, perfil e lembretes. O Compose começa com cadastro público fechado e com os módulos opcionais desligados. Feche os aceites do candidato antes de aplicar o mesmo fluxo em `producao`.
3. Use **Publicação → rollback** para voltar ao par anterior aprovado. Se uma atualização falhar nas sondas, o host tenta restaurar automaticamente as imagens antes aprovadas e registra falha para a operação. O estado só avança depois do smoke. No primeiro deploy reprovado, os serviços da aplicação são parados. Em Linux, o host também pode executar `python3 /opt/autocuidado/deploy/release.py smoke` pela configuração local.

O rollback troca as imagens e preserva o banco e o histórico do Flyway. Teste a compatibilidade das duas imagens com o esquema atualizado e com MFA antes de promover uma migração. Se a reversão de imagem falhar, mantenha a borda restrita enquanto a operação recupera o serviço. Preserve a revisão anterior no GHCR e o registro `images.json` da operação.

## Restaurar dados e exercitar alertas

1. Defina retenção e teste a recuperação para um ponto no tempo num destino isolado do PostgreSQL do provedor. Grave um cuidado fictício antes do alvo e outro depois. Verifique que o primeiro existe e que o segundo ficou fora do ponto restaurado. Confira papéis, Flyway e integridade, e registre a perda medida e o tempo até o fim do smoke autenticado.

   No Neon, snapshots exigem uma branch raiz, sem parent. Prepare a origem do ensaio na raiz de um projeto de QA separado do candidato; uma branch filha não atende esse requisito. Registre o LSN antes da alteração e restaure o snapshot em uma nova branch sem finalizar a troca, validando o preview antes de decidir qualquer corte. [Restrição e procedimento oficial](https://neon.com/docs/ai/ai-database-versioning)

2. Antes de reabrir a borda, reconcilie senhas, MFA e revogações ocorridas depois do ponto restaurado. Verifique que as credenciais antigas e sessões anteriores seguem recusadas e que o MFA vigente funciona. Repita Agenda, Histórico, cronograma, perfil e lembretes com uma conta de teste. Mantenha a origem preservada e restrita durante o incidente; use seu estado mais recente de credenciais/revogações como fonte confiável, aplique-o pelo papel de migração no destino restaurado e incremente as versões de credencial, invalidando sessões e resets. Conserve a chave MFA atual separadamente, sem restaurar uma chave antiga junto do banco. Se a origem e uma fonte independente atualizada de identidade estiverem indisponíveis, mantenha a borda fechada: não reabra o login com senhas, MFA ou códigos de recuperação de um backup antigo. A operação deverá recuperar a identidade por um canal independente antes da reativação; o piloto não tem reabertura automática nessa condição.
3. Monitore `/api/health` para o processo, `/api/ready` para a conexão com o banco e um fluxo crítico autenticado no ensaio. Pare o banco de teste, confirme a abertura do incidente de prontidão e a entrega ao responsável. Restaure o acesso, confirme a recuperação da sonda e registre horários e ações. Use as respostas HTTP e o ID de correlação dos logs do proxy para a triagem.

## Validar e-mail antes de abrir o cadastro

Use Cloudflare Email Sending com remetente verificado. O ambiente lê o host SMTP, o remetente e a credencial do arquivo protegido e usa TLS implícito. Na homologação restrita, habilite o cadastro de teste e confirme entrega, expiração e uso único do código de cadastro, primeiro acesso com MFA e recuperação de senha. Provisione a conta administrativa com a operação, confirme seus privilégios após MFA e mantenha o cadastro público fechado até o aceite de identidade. [SMTP da Cloudflare](https://developers.cloudflare.com/email-service/api/send-emails/smtp/)

Para provisionar, carregue no ambiente do terminal os digests ativos em `AUTOCUIDADO_WEB_IMAGE` e `AUTOCUIDADO_API_IMAGE`, conforme `images.json`. Use o nome de projeto de `release.json`. Informe o endereço, a senha inicial e o fator nos prompts e na interface da aplicação.

```sh
docker compose --env-file /etc/autocuidado/publicacao.env -p autocuidado-publicacao \
  -f /opt/autocuidado/deploy/compose.yaml run --rm --no-deps migrate \
  java -jar autocuidado.jar --provision-owner
```

Confirme que uma sessão administrativa sem MFA recebe 401 em `/api/admin/access`, uma conta comum com MFA recebe 403 e a conta administrativa com MFA recebe 204. Um segundo provisionamento deve ser recusado.

## Validar SEO e acompanhar a abertura

Teste por HTTPS a origem canônica, os redirecionamentos dos aliases, os metadados da home e dos guias habilitados, `robots.txt`, sitemap e o HTTP 404. Confira o `noindex` nas telas privadas e a recusa de acesso anônimo pela API. Prepare a propriedade no Search Console com acesso ao DNS, envie o sitemap após abrir a borda e inspecione a home e um guia. Registre o diagnóstico móvel e a decisão de manter, pausar ou reverter ao fim da primeira janela.
