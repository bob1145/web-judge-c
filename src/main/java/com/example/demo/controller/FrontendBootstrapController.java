package com.example.demo.controller;

import com.example.demo.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class FrontendBootstrapController {

    private final UserAccountService userAccountService;

    @GetMapping("/auth/bootstrap")
    public ResponseEntity<FrontendBootstrap> bootstrap(CsrfToken csrfToken) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new FrontendBootstrap(csrfToken.getToken(), csrfToken.getHeaderName(),
                        userAccountService.requiresAccountAuthentication()));
    }

    public record FrontendBootstrap(String csrfToken, String csrfHeader, boolean accountAuthRequired) {
    }
}
