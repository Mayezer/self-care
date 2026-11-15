package br.com.autocuidado.auth;

import java.nio.CharBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;

public final class OwnerProvisioning {
    private OwnerProvisioning() {}

    public static void run(boolean existingAccount) {
        var console = System.console();
        if (console == null) {
            throw new IllegalStateException("Use um terminal interativo para provisionar a conta administrativa.");
        }
        String email = AccountController.readEmail(console.readLine("E-mail da conta administrativa: "));
        if (email == null) {
            throw new IllegalArgumentException("Informe um endereço de e-mail válido.");
        }
        char[] password = console.readPassword("Senha inicial: ");
        char[] confirmation = null;
        try {
            confirmation = console.readPassword("Repita a senha inicial: ");
            if (password == null || !Arrays.equals(password, confirmation)) {
                throw new IllegalArgumentException("As senhas não coincidem.");
            }
            if (password.length < 24 || new PasswordPolicy(false).check(new String(password), email) != null) {
                throw new IllegalArgumentException("Use uma senha forte com 24 a 128 caracteres, sem o nome da conta.");
            }
            try (var connection = DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),
                    System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD"))) {
                if (!provision(connection, email, SecurityConfiguration.passwordEncoder().encode(CharBuffer.wrap(password)),
                        existingAccount)) {
                    throw new IllegalStateException("Já existe uma conta administrativa ou o endereço não corresponde ao modo selecionado.");
                }
            } catch (SQLException exception) {
                // JDBC diagnostics may include the email or connection parameters.
                throw new IllegalStateException("O provisionamento falhou. Confirme a migração e a conexão do banco.");
            }
            console.printf("Conta administrativa provisionada. Conclua o MFA no primeiro acesso.%n");
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            if (confirmation != null) {
                Arrays.fill(confirmation, '\0');
            }
        }
    }

    static boolean provision(Connection connection, String email, String passwordHash, boolean existingAccount) throws SQLException {
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.execute("LOCK TABLE app_owner IN SHARE ROW EXCLUSIVE MODE");
            try (var owner = statement.executeQuery("SELECT 1 FROM app_owner")) {
                if (owner.next()) {
                    return false;
                }
            }
            String sql = existingAccount ? """
                    UPDATE accounts SET password_hash = ?, credential_version = credential_version + 1,
                        reset_digest = NULL, reset_expires_at = NULL, reset_requested_at = NULL,
                        totp_seed = NULL, totp_last_step = NULL, recovery_digest = NULL
                    WHERE email = ? AND password_hash IS NOT NULL
                    RETURNING id
                    """ : """
                    INSERT INTO accounts (password_hash, email, time_zone) VALUES (?, ?, 'America/Sao_Paulo')
                    ON CONFLICT (email) DO NOTHING
                    RETURNING id
                    """;
            long accountId;
            try (var account = connection.prepareStatement(sql)) {
                account.setString(1, passwordHash);
                account.setString(2, email);
                try (var rows = account.executeQuery()) {
                    if (!rows.next()) {
                        return false;
                    }
                    accountId = rows.getLong(1);
                }
            }
            try (var owner = connection.prepareStatement("INSERT INTO app_owner (account_id) VALUES (?)")) {
                owner.setLong(1, accountId);
                owner.executeUpdate();
            }
            connection.commit();
            return true;
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }
}
