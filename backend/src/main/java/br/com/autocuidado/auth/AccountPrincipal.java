package br.com.autocuidado.auth;

import java.util.List;

import br.com.autocuidado.persistence.AccountRepository.LoginCredential;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

public final class AccountPrincipal extends User {
    public static final String FORUM_MODERATOR = "ROLE_FORUM_MODERATOR";

    private final long accountId;
    private final String email;
    private final long credentialVersion;
    private final boolean owner;

    AccountPrincipal(LoginCredential account) {
        this(account.getId(), account.getEmail(), account.getEmail() == null ? account.getUsername() : account.getEmail(),
                account.getPasswordHash(), account.getCredentialVersion(), account.getOwner(), false);
    }

    private AccountPrincipal(long accountId, String email, String identifier, String passwordHash,
            long credentialVersion, boolean owner, boolean mfaCompleted) {
        super(identifier, passwordHash, owner && mfaCompleted
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_STAFF"), new SimpleGrantedAuthority("ROLE_SUPERUSER"),
                        new SimpleGrantedAuthority(FORUM_MODERATOR))
                : List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.accountId = accountId;
        this.email = email;
        this.credentialVersion = credentialVersion;
        this.owner = owner;
    }

    public long accountId() {
        return accountId;
    }

    public String email() {
        return email;
    }

    public boolean canModerateForum() {
        return getAuthorities().contains(new SimpleGrantedAuthority(FORUM_MODERATOR));
    }

    long credentialVersion() {
        return credentialVersion;
    }

    boolean owner() {
        return owner;
    }

    AccountPrincipal afterMfa(String verifiedEmail, long version) {
        return new AccountPrincipal(accountId, verifiedEmail, verifiedEmail == null ? getUsername() : verifiedEmail,
                "", version, owner, true);
    }
}
