package br.com.autocuidado.auth;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = AccountController.class)
class PasswordErrors {
    @ExceptionHandler(PasswordPolicy.Rejected.class)
    ResponseEntity<AccountController.FieldErrors> rejected(PasswordPolicy.Rejected exception) {
        return ResponseEntity.badRequest().body(new AccountController.FieldErrors(Map.of("password", exception.getMessage())));
    }

    @ExceptionHandler(PasswordPolicy.VerificationUnavailable.class)
    ResponseEntity<AccountController.FieldErrors> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new AccountController.FieldErrors(
                Map.of("body", "Não foi possível verificar a nova senha. Tente novamente.")));
    }
}
