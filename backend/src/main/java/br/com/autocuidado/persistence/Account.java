package br.com.autocuidado.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String timeZone;

    @Column(length = 32, unique = true)
    private String username;

    @Column(length = 254, unique = true)
    private String email;

    private String passwordHash;

    protected Account() {}

    Account(String timeZone) {
        this.timeZone = timeZone;
    }

    public Long id() {
        return id;
    }

    public String timeZone() {
        return timeZone;
    }

    public String username() {
        return username;
    }

    public String email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }
}
