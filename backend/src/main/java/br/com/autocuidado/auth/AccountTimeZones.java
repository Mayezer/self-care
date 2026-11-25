package br.com.autocuidado.auth;

import java.time.ZoneId;
import java.util.List;

import br.com.autocuidado.persistence.AccountRepository;
import org.springframework.stereotype.Service;

@Service
public class AccountTimeZones {
    public static final String DEFAULT_ID = "America/Sao_Paulo";
    public static final ZoneId DEFAULT_ZONE = ZoneId.of(DEFAULT_ID);
    private final AccountRepository accounts;

    AccountTimeZones(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public static boolean isValid(String zone) {
        return DEFAULT_ID.equals(zone);
    }

    public static List<String> ids() {
        return List.of(DEFAULT_ID);
    }

    public ZoneId current(long accountId) {
        return DEFAULT_ZONE;
    }

    public void update(long accountId, String zone) {
        if (!isValid(zone)) throw new IllegalArgumentException("O fuso da agenda é America/Sao_Paulo.");
        accounts.updateTimeZone(accountId, DEFAULT_ID);
    }
}
