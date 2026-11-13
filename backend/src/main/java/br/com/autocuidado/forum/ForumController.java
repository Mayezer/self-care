package br.com.autocuidado.forum;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import br.com.autocuidado.auth.AccountPrincipal;
import br.com.autocuidado.persistence.CareCategory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/forum/posts")
@ConditionalOnProperty(name = "app.forum.enabled", havingValue = "true")
class ForumController {
    private static final Set<String> FIELDS = Set.of("kind", "category", "body", "product", "rating");
    private final ForumPosts posts;

    ForumController(ForumPosts posts) {
        this.posts = posts;
    }

    @GetMapping
    ResponseEntity<ForumPosts.Page> list(@AuthenticationPrincipal AccountPrincipal account) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(posts.list(account.accountId()));
    }

    @PutMapping("/{id}")
    ResponseEntity<?> draft(@AuthenticationPrincipal AccountPrincipal account, @PathVariable UUID id,
                            @RequestBody JsonNode request) {
        return save(account, id, request, false);
    }

    @PostMapping("/{id}/submit")
    ResponseEntity<?> submit(@AuthenticationPrincipal AccountPrincipal account, @PathVariable UUID id,
                             @RequestBody JsonNode request) {
        return save(account, id, request, true);
    }

    @PostMapping("/{id}/withdraw")
    ResponseEntity<Void> withdraw(@AuthenticationPrincipal AccountPrincipal account, @PathVariable UUID id) {
        return posts.withdraw(account.accountId(), id) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private ResponseEntity<?> save(AccountPrincipal account, UUID id, JsonNode request, boolean submit) {
        if (!request.isObject() || !FIELDS.containsAll(request.propertyNames())) return invalidBody();
        var errors = new LinkedHashMap<String, String>();
        var kind = choice(ForumPosts.Kind.class, request.path("kind"));
        if (kind == null) errors.put("kind", "Escolha relato ou avaliação.");
        var category = choice(CareCategory.class, request.path("category"));
        if (category == null && (submit || request.hasNonNull("category"))) {
            errors.put("category", "Escolha uma categoria.");
        }
        String body = text(request, "body", 2000, submit, "Escreva um texto de até 2.000 caracteres.", errors);
        String product = null;
        Integer rating = null;
        if (kind == ForumPosts.Kind.REVIEW) {
            product = text(request, "product", 120, submit, "Informe um nome de até 120 caracteres.", errors);
            var note = request.path("rating");
            if (request.hasNonNull("rating")) {
                if (note.isIntegralNumber() && note.canConvertToInt() && note.asInt() >= 1 && note.asInt() <= 5) {
                    rating = note.asInt();
                } else {
                    errors.put("rating", "Escolha uma nota inteira de 1 a 5 ou deixe em branco.");
                }
            }
        } else if (request.hasNonNull("product") || request.hasNonNull("rating")) {
            errors.put("kind", "Nome do produto e nota só podem ser usados em avaliações.");
        }
        if (!errors.isEmpty()) return ResponseEntity.badRequest().body(Map.of("errors", errors));

        var content = new ForumPosts.Content(kind, category, body == null ? "" : body, product, rating);
        if (!submit) {
            var saved = posts.draft(account.accountId(), id, content);
            return saved == null ? ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("errors", Map.of("body", "Essa publicação já foi enviada."))) : ResponseEntity.ok(saved);
        }
        var result = posts.submit(account.accountId(), id, content);
        return result.nextAvailableAt() == null ? ResponseEntity.ok(result.post())
                : ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("nextAvailableAt", result.nextAvailableAt()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> invalidBody() {
        return ResponseEntity.badRequest().body(Map.of("errors", Map.of("body", "Envie apenas os campos da publicação de texto.")));
    }

    private static <T extends Enum<T>> T choice(Class<T> type, JsonNode value) {
        if (!value.isString()) return null;
        try {
            return Enum.valueOf(type, value.asString());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String text(JsonNode request, String field, int limit, boolean required,
                               String message, Map<String, String> errors) {
        var value = request.path(field);
        String text = value.isString() ? value.asString().strip() : "";
        if ((request.hasNonNull(field) && !value.isString()) || text.length() > limit
                || text.indexOf(0) >= 0 || (required && text.isEmpty())) {
            errors.put(field, message);
        }
        return text.isEmpty() ? null : text;
    }
}
