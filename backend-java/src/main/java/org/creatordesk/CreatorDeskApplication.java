package org.creatordesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class CreatorDeskApplication {
    public static void main(String[] args) {
        SpringApplication.run(CreatorDeskApplication.class, args);
    }
}
