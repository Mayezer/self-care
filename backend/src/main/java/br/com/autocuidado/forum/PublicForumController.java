package br.com.autocuidado.forum;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/forum/public/topics")
@ConditionalOnProperty(name = "app.forum.public-reading-enabled", havingValue = "true")
class PublicForumController {
    private final PublicForum forum;

    PublicForumController(PublicForum forum) {
        this.forum = forum;
    }

    @GetMapping
    ResponseEntity<PublicForum.Page> list(@RequestParam(defaultValue = "") String q,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(required = false) ForumPosts.Kind kind) {
        String query = q.strip();
        if (page < 1 || query.length() > 120 || query.indexOf(0) >= 0) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(forum.list(query, page, kind));
    }

    @GetMapping("/{topicId}")
    ResponseEntity<PublicForum.Detail> detail(@PathVariable UUID topicId) {
        var detail = forum.detail(topicId);
        return detail == null ? ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
                : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(detail);
    }

    @GetMapping("/{topicId}/images/{imageId}")
    ResponseEntity<byte[]> image(@PathVariable UUID topicId, @PathVariable UUID imageId) {
        var image = forum.image(topicId, imageId);
        return image == null ? ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
                : ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).body(image);
    }
}
