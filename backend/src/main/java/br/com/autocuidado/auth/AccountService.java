package br.com.autocuidado.auth;

import java.util.Locale;

import br.com.autocuidado.persistence.AccountRepository;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
class AccountService implements UserDetailsService {

    private final AccountRepository accounts;
    AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public AccountPrincipal loadUserByUsername(String identifier) {
        String login = normalizeIdentifier(identifier);
        var account = login.contains("@")
                ? accounts.loginByEmail(login)
                : accounts.loginByUsername(login);
        return account
                .map(AccountPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Conta não encontrada."));
    }

    static String normalizeIdentifier(String identifier) {
        String login = identifier.strip();
        return login.contains("@") ? EmailRegistration.normalizeEmail(login) : login.toLowerCase(Locale.ROOT);
    }
}
