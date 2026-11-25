package br.com.autocuidado.auth;

import java.util.Map;
import java.util.Set;

import br.com.autocuidado.media.PhotoRaster;
import br.com.autocuidado.persistence.AccountProfileStore;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/account/profile")
class AccountProfileController {
    private final AccountProfileStore profiles;

    AccountProfileController(AccountProfileStore profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    ResponseEntity<AccountProfileStore.Profile> find(@AuthenticationPrincipal AccountPrincipal account) {
        return response(account.accountId());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> rename(@AuthenticationPrincipal AccountPrincipal account, @RequestBody JsonNode request) {
        if (!request.isObject() || !request.has("displayName")
                || !Set.of("displayName").containsAll(request.propertyNames())) return invalidBody();
        var value = request.path("displayName");
        if (!value.isString() && !value.isNull()) return invalid("displayName", "Informe seu nome como texto.");
        String name = value.isNull() ? "" : value.asString().strip();
        if (name.codePointCount(0, name.length()) > 80 || name.codePoints().anyMatch(Character::isISOControl)) {
            return invalid("displayName", "Use até 80 caracteres, sem caracteres de controle.");
        }
        profiles.rename(account.accountId(), name.isEmpty() ? null : name);
        return response(account.accountId());
    }

    @GetMapping("/photo")
    ResponseEntity<byte[]> photo(@AuthenticationPrincipal AccountPrincipal account) {
        byte[] photo = profiles.photo(account.accountId());
        if (photo == null) return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG)
                .header("Cross-Origin-Resource-Policy", "same-origin").body(photo);
    }

    @PutMapping(path = "/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<AccountProfileStore.Profile> replacePhoto(@AuthenticationPrincipal AccountPrincipal account,
                                                            @RequestParam("photo") MultipartFile photo) {
        profiles.replacePhoto(account.accountId(), PhotoRaster.normalize(photo, 512));
        return response(account.accountId());
    }

    @DeleteMapping("/photo")
    ResponseEntity<AccountProfileStore.Profile> deletePhoto(@AuthenticationPrincipal AccountPrincipal account) {
        profiles.replacePhoto(account.accountId(), null);
        return response(account.accountId());
    }

    @ExceptionHandler(PhotoRaster.Rejected.class)
    ResponseEntity<?> invalidPhoto(PhotoRaster.Rejected error) {
        return invalid("photo", error.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> oversizedPhoto() {
        return ResponseEntity.status(413).body(Map.of("errors", Map.of("photo", "Escolha uma foto JPEG ou PNG de até 5 MiB.")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> invalidBody() {
        return invalid("body", "Envie apenas o nome da própria conta em um objeto JSON válido.");
    }

    private ResponseEntity<AccountProfileStore.Profile> response(long accountId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.find(accountId));
    }

    private static ResponseEntity<?> invalid(String field, String message) {
        return ResponseEntity.badRequest().body(Map.of("errors", Map.of(field, message)));
    }
}
