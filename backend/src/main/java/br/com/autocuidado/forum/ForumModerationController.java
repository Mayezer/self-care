package br.com.autocuidado.forum;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import br.com.autocuidado.auth.AccountPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/forum")
@ConditionalOnProperty(name = {"app.forum.enabled", "app.forum.public-reading-enabled"}, havingValue = "true")
class ForumModerationController {
    private final ForumModeration moderation;

    ForumModerationController(ForumModeration moderation) {
        this.moderation = moderation;
    }

    @PostMapping("/topics/{topicId}/reports/{kind}/{targetId}")
    ResponseEntity<?> report(@AuthenticationPrincipal AccountPrincipal account, @PathVariable UUID topicId,
                             @PathVariable ForumModeration.Kind kind, @PathVariable UUID targetId,
                             @RequestBody JsonNode request) {
        var errors = new LinkedHashMap<String, String>();
        String message = "Informe um motivo de até 500 caracteres.";
        if (!request.isObject() || !request.propertyNames().equals(Set.of("reason"))) {
            errors.put("reason", message);
        }
        String reason = ForumController.text(request, "reason", 500, true, message, errors);
        if (!errors.isEmpty()) return ResponseEntity.badRequest().body(Map.of("errors", errors));
        return moderation.report(account.accountId(), topicId, kind, targetId, reason)
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/moderation/queue")
    ResponseEntity<ForumModeration.Queue> queue() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(moderation.queue());
    }

    @PostMapping("/moderation/topics/{topicId}/{kind}/{targetId}/{decision}")
    ResponseEntity<Void> decide(@AuthenticationPrincipal AccountPrincipal account, @PathVariable UUID topicId,
                                @PathVariable ForumModeration.Kind kind, @PathVariable UUID targetId,
                                @PathVariable ForumModeration.Decision decision) {
        return moderation.decide(account.accountId(), topicId, kind, targetId, decision)
                ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @PostMapping("/moderation/reports/{reportId}/close")
    ResponseEntity<Void> close(@AuthenticationPrincipal AccountPrincipal account, @PathVariable UUID reportId) {
        moderation.closeReport(account.accountId(), reportId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/moderation/topics/{topicId}/images/{imageId}")
    ResponseEntity<byte[]> image(@PathVariable UUID topicId, @PathVariable UUID imageId) {
        byte[] bytes = moderation.image(topicId, imageId);
        return bytes == null ? ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
                : ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).body(bytes);
    }
}
