package br.com.autocuidado.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;

@Component
class CredentialMail {
    private final ObjectProvider<MailSender> sender;
    private final String from;

    CredentialMail(ObjectProvider<MailSender> sender, @Value("${app.auth.email.from:}") String from) {
        this.sender = sender;
        this.from = from;
    }

    void send(String email, String subject, String code) {
        var mail = sender.getIfAvailable();
        if (mail == null || from.isBlank()) {
            throw new MailSendException("Credential email unavailable");
        }
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject(subject);
        message.setText("""
                Digite este código na Agenda de autocuidado.

                %s

                O código vale por 10 minutos. Se você não iniciou a solicitação, ignore este e-mail.
                """.formatted(code));
        mail.send(message);
    }
}
