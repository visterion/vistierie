package de.vesterion.vistierie.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class SecurityConfig {
    @Bean BCryptPasswordEncoder bcrypt() { return new BCryptPasswordEncoder(); }
}
