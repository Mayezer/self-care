package br.com.autocuidado.vanity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import br.com.autocuidado.auth.AccountPrincipal;
import br.com.autocuidado.vanity.MakeupInventory.Fields;
import br.com.autocuidado.vanity.MakeupInventory.Item;
import br.com.autocuidado.vanity.MakeupInventory.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/makeup-items")
class MakeupInventoryController {
    private static final Set<String> CREATE_FIELDS = Set.of("id", "name", "category", "status");
    private static final Set<String> EDIT_FIELDS = Set.of("name", "category", "status");
    private final MakeupInventory inventory;

    MakeupInventoryController(MakeupInventory inventory) {
        this.inventory = inventory;
    }

    @GetMapping
    List<Item> list(@AuthenticationPrincipal AccountPrincipal account) {
        return inventory.list(account.accountId());
    }

    @PostMapping
    ResponseEntity<?> create(@AuthenticationPrincipal AccountPrincipal account, @RequestBody JsonNode request) {
        if (!validBody(request, CREATE_FIELDS)) {
            return invalidBody();
        }
        var errors = new LinkedHashMap<String, String>();
        UUID id = null;
        try {
            id = UUID.fromString(request.path("id").asString(""));
            if (!id.toString().equalsIgnoreCase(request.path("id").asString(""))) {
                errors.put("body", "Identificador do item inválido.");
            }
        } catch (IllegalArgumentException invalid) {
            errors.put("body", "Identificador do item inválido.");
        }
        var fields = parse(request, errors);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new FieldErrors(errors));
        }
        var item = inventory.add(account.accountId(), id, fields);
        return item.isPresent() ? ResponseEntity.ok(item.get()) : ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new FieldErrors(Map.of("body", "Já existe um item diferente para este envio. Confira sua lista.")));
    }

    @PutMapping("/{id}")
    ResponseEntity<?> edit(@AuthenticationPrincipal AccountPrincipal account, @PathVariable UUID id,
            @RequestBody JsonNode request) {
        if (!validBody(request, EDIT_FIELDS)) {
            return invalidBody();
        }
        var errors = new LinkedHashMap<String, String>();
        var fields = parse(request, errors);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new FieldErrors(errors));
        }
        return ResponseEntity.of(inventory.update(account.accountId(), id, fields));
    }

    @PostMapping("/{id}/purchased")
    ResponseEntity<Item> purchased(@AuthenticationPrincipal AccountPrincipal account, @PathVariable UUID id) {
        return ResponseEntity.of(inventory.markPurchased(account.accountId(), id));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> remove(@AuthenticationPrincipal AccountPrincipal account, @PathVariable UUID id) {
        return inventory.delete(account.accountId(), id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<FieldErrors> invalidBody() {
        return ResponseEntity.badRequest().body(new FieldErrors(Map.of("body", "Envie somente os dados do item.")));
    }

    private static boolean validBody(JsonNode request, Set<String> allowedFields) {
        return request.isObject() && allowedFields.containsAll(request.propertyNames());
    }

    private static Fields parse(JsonNode request, Map<String, String> errors) {
        var name = request.path("name");
        String itemName = name.isString() ? name.asString().strip() : "";
        if (itemName.isEmpty() || itemName.length() > 100 || itemName.codePoints().anyMatch(Character::isISOControl)) {
            errors.put("name", "Informe um nome válido de até 100 caracteres.");
        }
        var category = request.path("category");
        String itemCategory = category.isString() ? category.asString().strip() : "";
        if (!(category.isMissingNode() || category.isNull() || category.isString()) || itemCategory.length() > 60
                || itemCategory.codePoints().anyMatch(Character::isISOControl)) {
            errors.put("category", "Informe uma categoria de até 60 caracteres.");
        }
        Status status = null;
        try {
            status = Status.valueOf(request.path("status").asString(""));
        } catch (IllegalArgumentException invalid) {
            errors.put("status", "Escolha Tenho, Está acabando ou Comprar.");
        }
        return new Fields(itemName, itemCategory.isEmpty() ? null : itemCategory, status);
    }

    record FieldErrors(Map<String, String> errors) {}
}
