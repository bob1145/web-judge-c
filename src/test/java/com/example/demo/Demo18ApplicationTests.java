package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "judge.auth.access-code=task02-test-only-access-material")
class Demo18ApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
    }

    @Test
    void unusedSpringDefaultAccountIsNotCreated() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void defaultBindAddressIsLoopback() {
        assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
    }

}
