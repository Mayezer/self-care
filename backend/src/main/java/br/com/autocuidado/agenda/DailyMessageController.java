package br.com.autocuidado.agenda;

import br.com.autocuidado.auth.AccountPrincipal;
import br.com.autocuidado.persistence.DailyMessageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DailyMessageController {

    private final DailyMessageService messages;
    private final boolean enabled;

    DailyMessageController(DailyMessageService messages,
                           @Value("${app.agenda.daily-message-enabled:false}") boolean enabled) {
        this.messages = messages;
        this.enabled = enabled;
    }

    @GetMapping("/api/agenda/daily-message")
    ResponseEntity<DailyMessageCatalog.Message> today(@AuthenticationPrincipal AccountPrincipal account) {
        return enabled ? ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(messages.load(account))
                : ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
