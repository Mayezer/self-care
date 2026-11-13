package br.com.autocuidado.auth;

import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import br.com.autocuidado.persistence.AccountRepository;
import org.springframework.stereotype.Service;

@Service
public class AccountTimeZones {
    private static final Set<String> IDS = ZoneId.getAvailableZoneIds();
    private static final List<String> SORTED_IDS = IDS.stream().sorted().toList();
    private final AccountRepository accounts;

    AccountTimeZones(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public static boolean isValid(String zone) {
        return IDS.contains(zone);
    }

    public static List<String> ids() {
        return SORTED_IDS;
    }

    public ZoneId current(long accountId) {
        return ZoneId.of(accounts.findTimeZone(accountId));
    }

    public void update(long accountId, String zone) {
        accounts.updateTimeZone(accountId, zone);
    }
}
