package br.com.autocuidado;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@RestController
class HealthController {

    private final JdbcTemplate jdbc;

    HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/health")
    Health health() {
        return new Health("UP");
    }

    @GetMapping("/api/ready")
    ResponseEntity<Health> ready() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Health("UP"));
        } catch (DataAccessException exception) {
            return ResponseEntity.status(SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                    .body(new Health("DOWN"));
        }
    }

    record Health(String status) {}
}
