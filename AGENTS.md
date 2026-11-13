# Agenda de autocuidado

- Quando a documentação local em `docs/` estiver disponível, use `docs/PENDENCIAS.md` para localizar o passo ativo e as dependências. O FSD de cada etapa é a referência de aceite. Preserve o histórico de STATUS e ERROS.
- Para trabalho da Etapa 1, Etapa 2 ou pré-publicação, siga também o `AGENTS.md` da pasta local correspondente em `docs/`, quando disponível. Os comandos de execução e testes da cópia pública estão no [README.md](README.md).
- `frontend/` contém Angular e o Nginx da interface; `backend/` contém Spring Boot e as migrações Flyway. Mantenha titularidade, datas e estados no servidor. Registre novas migrações em arquivos versionados.
- Use o Compose com projeto, banco, contas e integrações de teste separados. Não coloque senhas, cookies, tokens, dumps ou dados pessoais em código, documentação ou evidências.
