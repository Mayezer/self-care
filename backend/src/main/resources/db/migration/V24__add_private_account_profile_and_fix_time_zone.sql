UPDATE accounts SET time_zone = 'America/Sao_Paulo'
WHERE time_zone <> 'America/Sao_Paulo';

ALTER TABLE accounts ALTER COLUMN time_zone SET DEFAULT 'America/Sao_Paulo';
ALTER TABLE accounts ADD CONSTRAINT accounts_fixed_time_zone
    CHECK (time_zone = 'America/Sao_Paulo');

CREATE TABLE account_profiles (
    account_id bigint PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    display_name varchar(80),
    photo_jpeg bytea,
    photo_version bigint NOT NULL DEFAULT 0 CHECK (photo_version >= 0),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_profile_name_length CHECK (
        display_name IS NULL OR char_length(trim(display_name)) BETWEEN 1 AND 80
    ),
    CONSTRAINT account_profile_photo_size CHECK (
        photo_jpeg IS NULL OR octet_length(photo_jpeg) BETWEEN 4 AND 2000000
    )
);

ALTER TABLE recurring_plans ADD COLUMN hair_routine jsonb
    CHECK (hair_routine IS NULL OR jsonb_typeof(hair_routine) = 'object');
