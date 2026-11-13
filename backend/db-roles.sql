\getenv migration_password LOCAL_DB_MIGRATION_PASSWORD
\getenv runtime_password LOCAL_DB_RUNTIME_PASSWORD
\getenv admin_password PGPASSWORD

BEGIN;
SET LOCAL log_statement = 'none';
SET LOCAL log_min_error_statement = 'panic';
DO $$
BEGIN
    IF current_user IN ('autocuidado_migration', 'autocuidado_runtime') THEN
        RAISE EXCEPTION 'O administrador do banco precisa ter um nome distinto dos papéis do aplicativo.';
    END IF;
END
$$;

SELECT :'migration_password' <> :'runtime_password'
   AND :'migration_password' <> :'admin_password'
   AND :'runtime_password' <> :'admin_password' AS distinct_passwords
\gset
\if :distinct_passwords
\else
    DO $$ BEGIN RAISE EXCEPTION 'Use uma senha diferente para cada papel do banco.'; END $$;
\endif

SELECT pg_advisory_xact_lock(917230488);
SELECT 'CREATE ROLE autocuidado_migration'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'autocuidado_migration')
\gexec
SELECT 'CREATE ROLE autocuidado_runtime'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'autocuidado_runtime')
\gexec

ALTER ROLE autocuidado_migration LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'migration_password';
ALTER ROLE autocuidado_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'runtime_password';

SELECT current_database() AS app_db
\gset
REVOKE CONNECT, TEMPORARY ON DATABASE :"app_db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"app_db" TO autocuidado_migration, autocuidado_runtime;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO autocuidado_migration;
GRANT USAGE ON SCHEMA public TO autocuidado_runtime;

-- Transfer the existing application objects in this dedicated database.
-- The identity sequences follow ownership of their tables.
SELECT format('ALTER TABLE %I.%I OWNER TO autocuidado_migration', n.nspname, c.relname)
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
    AND c.relowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
    AND NOT EXISTS (SELECT FROM pg_depend d WHERE d.classid = 'pg_class'::regclass
        AND d.objid = c.oid AND d.deptype = 'e')
\gexec

COMMIT;
