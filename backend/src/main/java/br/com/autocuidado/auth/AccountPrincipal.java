package br.com.autocuidado.auth;

import java.util.List;

import br.com.autocuidado.persistence.AccountRepository.LoginCredential;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

public final class AccountPrincipal extends User {

    private final long accountId;
    private final String email;
    private final long credentialVersion;

    AccountPrincipal(LoginCredential account) {
        this(account.getId(), account.getEmail(), account.getEmail() == null ? account.getUsername() : account.getEmail(),
                account.getPasswordHash(), account.getCredentialVersion());
    }

    private AccountPrincipal(long accountId, String email, String identifier, String passwordHash, long credentialVersion) {
        super(identifier, passwordHash, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.accountId = accountId;
        this.email = email;
        this.credentialVersion = credentialVersion;
    }

    public long accountId() {
        return accountId;
    }

    public String email() {
        return email;
    }

    long credentialVersion() {
        return credentialVersion;
    }

    AccountPrincipal afterEnrollment(String verifiedEmail, long version) {
        return new AccountPrincipal(accountId, verifiedEmail, verifiedEmail == null ? getUsername() : verifiedEmail, "", version);
    }
}
