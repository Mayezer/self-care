package br.com.autocuidado.hair;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import br.com.autocuidado.auth.AccountPrincipal;
import br.com.autocuidado.auth.AccountTimeZones;
import br.com.autocuidado.persistence.HairProfileStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@ConditionalOnProperty(name = {"app.hair.profile-enabled", "app.hair.products-enabled"}, havingValue = "true")
class HairProductController {

    private final HairProfileStore profiles;
    private final AccountTimeZones timeZones;
    private final Clock clock;
    private final HairProductRecommendations recommendations;

    HairProductController(HairProfileStore profiles, AccountTimeZones timeZones, Clock clock, JsonMapper json) {
        this.profiles = profiles;
        this.timeZones = timeZones;
        this.clock = clock;
        this.recommendations = new HairProductRecommendations(json);
    }

    @GetMapping("/api/hair-profile/products")
    ResponseEntity<Suggestions> find(@AuthenticationPrincipal AccountPrincipal account) {
        long accountId = account.accountId();
        var answers = profiles.find(accountId);
        if (answers.isEmpty()) {
            return response(new Suggestions(false, List.of()));
        }
        var date = LocalDate.now(clock.withZone(timeZones.current(accountId)));
        return response(new Suggestions(true, recommendations.recommend(answers, date)));
    }

    private static ResponseEntity<Suggestions> response(Suggestions suggestions) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(suggestions);
    }

    record Suggestions(boolean hasProfile, List<HairProductRecommendations.Card> cards) {}
}
