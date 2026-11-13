# Agenda de autocuidado

Agenda web para planejar e acompanhar cuidados com cabelo, pele, maquiagem e unhas, com cronograma capilar, lembretes e histórico.

Gere uma prévia do cronograma capilar sem conta. Entre para salvar as sessões na agenda, registrar cuidados concluídos e consultar o histórico. A aplicação também oferece perfil capilar, guias e lembretes na agenda.

A interface usa Angular e Nginx em `frontend/`. A API usa Spring Boot e Flyway em `backend/`, com PostgreSQL 18.6. O Docker Compose serve a interface e a API na mesma origem.

O projeto está em pré-publicação. Produtos, Web Push, cobrança, diário capilar e fórum vêm desativados no ambiente local padrão.

## Rodar localmente com Docker Compose

Com o Docker ativo, copie `.env.example` para `.env` se ainda não tiver um arquivo local. Preencha `LOCAL_DB_PASSWORD`, `LOCAL_DB_MIGRATION_PASSWORD` e `LOCAL_DB_RUNTIME_PASSWORD` com três senhas distintas de teste. Gere uma chave exclusiva com `openssl rand -hex 32` para `LOCAL_AUTH_MFA_KEY` e mantenha essa chave no ambiente enquanto usar o mesmo volume. Na raiz do projeto, execute o comando abaixo.

```sh
docker compose --profile mail up --build --wait
```

Abra a aplicação na porta 4222. O front-end encaminha `/api/` pela mesma origem. A API e o PostgreSQL também ficam acessíveis em `127.0.0.1`, nas portas 8102 e 56422. O perfil `mail` inicia o Mailpit na porta 8025, onde você recebe os códigos de cadastro, configuração do autenticador e recuperação de senha das contas fictícias. [Aplicação local](http://127.0.0.1:4222/) · [Mailpit](http://127.0.0.1:8025/)

As portas, o nome do banco e as credenciais podem ser definidos no `.env`. Mantenha o volume `postgres-data` separado do piloto. Em um volume existente, preserve o nome do banco e as credenciais do administrador e acrescente as duas novas senhas. O serviço `db-roles` prepara o esquema dedicado e os papéis. O serviço `migrate` aplica o Flyway como `autocuidado_migration`. A API inicia como `autocuidado_runtime`, com acesso de leitura e escrita às tabelas da aplicação. Os dois serviços de preparação terminam com código 0.

Para conferir a persistência, gere uma prévia, confirme uma conta fictícia pelo Mailpit, entre e salve o cronograma. Execute `docker compose --profile mail down` e depois `docker compose --profile mail up --wait`. Entre novamente e abra o cronograma salvo. O Flyway aplica as migrações pendentes e valida as existentes a cada preparação. Use `down` para preservar o volume durante o teste.

## Desenvolvimento local

Use JDK 25.0.4.1 e Node.js 22.23.1, conforme `.java-version` e `.nvmrc`. O back-end usa o Gradle Wrapper e o front-end usa npm com versões travadas em `package-lock.json`.

Crie um banco PostgreSQL local e deixe o usuário da aplicação como dono do banco, com permissão para criar tabelas no esquema `public`. O Flyway aplica as migrações de `backend/src/main/resources/db/migration/` na inicialização e o Hibernate valida o mapeamento.

Em um terminal Bash, informe a conexão e a chave MFA de teste antes de iniciar o back-end. Gere a chave com `openssl rand -hex 32` e reutilize-a enquanto usar o mesmo banco. Ajuste a URL se o banco usar outro nome ou porta.

```sh
export SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/autocuidado
read -r -p 'Usuário do PostgreSQL: ' SPRING_DATASOURCE_USERNAME
read -r -s -p 'Senha do PostgreSQL: ' SPRING_DATASOURCE_PASSWORD
printf '\n'
read -r -s -p 'Chave MFA local em hexadecimal: ' APP_AUTH_MFA_KEY
printf '\n'
export SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD APP_AUTH_MFA_KEY
cd backend
./gradlew bootRun --args='--server.address=127.0.0.1'
```

Em outro, inicie o front-end.

```sh
cd frontend
npm ci
npm start -- --host 127.0.0.1
```

Abra [a aplicação local](http://127.0.0.1:4200/). A tela inicial consulta `/api/health` pela mesma origem. O Angular encaminha o pedido para o Spring Boot na porta 8080.

## Criar uma conta de teste e entrar

Para enviar os códigos de confirmação, configuração do autenticador e recuperação, configure `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` e `APP_AUTH_EMAIL_FROM` antes de iniciar o back-end. Ao rodar a API diretamente, use `APP_AUTH_MFA_KEY` para a chave de 32 bytes codificada em hexadecimal. Em desenvolvimento, use um servidor SMTP local que capture os e-mails. O perfil `publicacao` configura TLS e autenticação para um provedor externo. Guarde as credenciais fora do repositório.

1. Na Agenda, selecione **Quero criar uma conta**, informe um e-mail fictício e solicite o código. Copie o código do Mailpit e crie a senha com 15 a 128 caracteres. A verificação de senhas vazadas requer acesso da API ao serviço Pwned Passwords.
2. Entre com o e-mail e a senha. No primeiro acesso, confirme o e-mail com o novo código do Mailpit e adicione a chave de configuração a um aplicativo autenticador com códigos baseados em tempo (TOTP).
3. Guarde a chave de recuperação e confirme com um código do autenticador. Nos próximos acessos, use a senha e o código do aplicativo.

No perfil de publicação, o cadastro começa fechado.

## Testar a prévia capilar (FSD-03A)

Com a aplicação rodando pelo Compose, envie uma data de início, a duração em semanas, os dias selecionados e o fuso IANA para `POST /api/hair-plans/preview`. Para o servidor de desenvolvimento Angular, use a porta 4200. A chamada é pública e não salva o plano.

```sh
start_date=$(TZ=America/Sao_Paulo date +%F)
curl -sS http://127.0.0.1:4222/api/hair-plans/preview \
  -H 'Content-Type: application/json' \
  --data "{\"startDate\":\"${start_date}\",\"weeks\":2,\"weekdays\":[\"MONDAY\",\"THURSDAY\"],\"timeZone\":\"America/Sao_Paulo\"}"
```

A resposta HTTP 200 traz `occurrences`, com `date` em `AAAA-MM-DD` e `stage` igual a `WASH_ONLY` (Somente lavagem). Entradas inválidas retornam HTTP 400 com um objeto `errors` indexado pelo nome do campo. Use os dias de `MONDAY` a `SUNDAY` em maiúsculas.

## Retomar a prévia depois de entrar (FSD-04C)

Na prévia gerada, personalize uma etapa e selecione **Entrar para salvar na agenda**. Depois de entrar, o formulário volta com a data, a duração, os dias e as etapas escolhidas. Com sessão ativa, a API usa o fuso da conta. Se a data de início tiver passado nesse fuso, ajuste a data e gere a prévia de novo. As etapas das datas em comum são mantidas.

## Build e testes

- No back-end, execute `./gradlew clean build` em `backend/` para rodar os testes unitários e de API sem banco.
- Para o aceite do FSD-04A, execute `APP_AUTH_MFA_REQUIRED=false ./gradlew postgresqlTest` em `backend/` com as variáveis `SPRING_DATASOURCE_*` apontando para um banco PostgreSQL vazio e exclusivo para essa execução. As contas fictícias da suíte entram por senha. O teste aplica a migração, grava dados fictícios, verifica a restrição de datas por plano e reinicia a aplicação para validar o esquema e reler os dados. Use um banco novo e vazio para repetir o teste.
- No front-end, execute `npm ci`, `npm run build` e `npm test` em `frontend/`.

O GitHub Actions verifica o histórico com Gitleaks, compila o Angular e o Spring Boot e executa os testes, incluindo integração com PostgreSQL descartável. O deploy segue em preparação. [Workflow de CI](.github/workflows/ci.yml)

## Testar a persistência do perfil capilar (E2-04)

Na instância local, entre com uma conta fictícia. `GET /api/hair-profile` retorna as respostas da própria conta, ou `{}` quando não há respostas. `PUT /api/hair-profile` exige o token de `/api/auth/csrf` e substitui todas as respostas pelo objeto enviado. Por exemplo, `{"rootOiliness":"RARELY"}` salva apenas a oleosidade; `{}` limpa o perfil. Uma resposta inválida retorna HTTP 400 com `errors` por campo, sem alterar o perfil salvo. A agenda, o cronograma e o histórico seguem acessíveis com o perfil vazio.

Os controles de módulos do Compose valem para toda a instância e preservam o banco.

## Testar notificações do navegador (E2-10)

Em uma instância de testes, use HTTPS ou a origem local `http://127.0.0.1:4222`. Gere um par VAPID P-256 dedicado. Preencha `LOCAL_WEB_PUSH_PUBLIC_KEY` com o ponto público de 65 bytes e `LOCAL_WEB_PUSH_PRIVATE_KEY` com o escalar privado de 32 bytes, ambos em Base64url sem preenchimento. Guarde a chave privada no arquivo de ambiente local. Inicie o Compose com `LOCAL_WEB_PUSH_ENABLED=true` e mantenha `LOCAL_AGENDA_REMINDERS_ENABLED=true`.

Com uma conta fictícia, salve em **Configurações → Lembretes** uma categoria e um horário futuro no fuso da conta. Crie um cuidado planejado nessa categoria e selecione **Ativar notificações** em um navegador compatível. Quando o horário chegar, a notificação genérica deve abrir a Agenda. Repita com duas inscrições, recuse a permissão em outro navegador e teste sair da conta antes do próximo horário. Um novo login limpa a inscrição local anterior e permite uma nova ativação explícita.

Para suspender os envios no Compose, inicie novamente com `LOCAL_WEB_PUSH_ENABLED=false`. O envio usa VAPID e uma solicitação sem conteúdo. O navegador consulta a inscrição pelo endereço opaco e monta o texto genérico. O registro `ACCEPTED` indica que o provedor aceitou a solicitação. A janela de despacho e repetição termina dez minutos após o horário previsto ou na virada do dia local, o que ocorrer primeiro. [RFC 8030](https://www.rfc-editor.org/rfc/rfc8030) · [RFC 8292](https://www.rfc-editor.org/rfc/rfc8292)

Na conciliação, consulte `web_push_attempts` filtrando por `state = 'UNCERTAIN'`. Use o ID, o horário e o código HTTP registrado, quando disponível, para confrontar a evidência do provedor. Preserve o estado incerto quando faltar confirmação. A fila automática nunca repete uma tentativa incerta. Mantenha os endereços de inscrição fora dos registros de suporte.
