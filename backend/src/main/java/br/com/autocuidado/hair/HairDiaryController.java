package br.com.autocuidado.hair;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.autocuidado.auth.AccountPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/hair-diary/photos")
@ConditionalOnProperty(name = {"app.billing.enabled", "app.hair.diary-enabled"}, havingValue = "true")
class HairDiaryController {
    private final HairDiary diary;

    HairDiaryController(HairDiary diary) {
        this.diary = diary;
    }

    @GetMapping
    ResponseEntity<List<HairDiary.Photo>> list(@AuthenticationPrincipal AccountPrincipal account) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(diary.list(account.accountId()));
    }

    @GetMapping("/{id}")
    ResponseEntity<byte[]> photo(@AuthenticationPrincipal AccountPrincipal account, @PathVariable UUID id) {
        byte[] data = diary.photo(account.accountId(), id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG)
                .header("Cross-Origin-Resource-Policy", "same-origin").body(data);
    }

    @PutMapping(path = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Void> save(@AuthenticationPrincipal AccountPrincipal account, @PathVariable UUID id,
                              @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                              @RequestParam("photo") MultipartFile photo) {
        diary.save(account.accountId(), id, date, photo);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal AccountPrincipal account, @PathVariable UUID id) {
        diary.delete(account.accountId(), id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(PhotoRaster.Rejected.class)
    ResponseEntity<Map<String, String>> invalidPhoto(PhotoRaster.Rejected error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
