package br.com.autocuidado.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AdminAccessController {
    @GetMapping("/api/admin/access")
    ResponseEntity<Void> access() {
        return ResponseEntity.noContent().build();
    }
}
