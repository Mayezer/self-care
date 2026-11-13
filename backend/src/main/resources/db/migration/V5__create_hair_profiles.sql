CREATE TABLE hair_profiles (
    account_id bigint PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
    answers jsonb NOT NULL CHECK (jsonb_typeof(answers) = 'object' AND answers <> '{}'::jsonb)
);
