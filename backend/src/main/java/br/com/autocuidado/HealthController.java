package br.com.autocuidado;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthController {

    @GetMapping("/api/health")
    Health health() {
        return new Health("UP");
    }

    record Health(String status) {}
}
