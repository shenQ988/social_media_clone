package com.pixframe.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ports pixframe/views/index.py's spa_shell(): serves the React app shell for
 * any client-routed path so a hard refresh / shared link on a deep route
 * (e.g. /users/alice/followers/) still works. Scoped to the known React
 * Router route prefixes (see pixframe/js/App.jsx) rather than a blanket
 * "/**" wildcard, so it can never shadow /api/**, /uploads/**, or /static/**.
 */
@RestController
public class SpaController {

    @Value("${app.templates-dir}")
    private String templatesDir;

    @GetMapping({"/", "/accounts/**", "/users/**", "/posts/**", "/explore/**"})
    public ResponseEntity<String> shell() throws IOException {
        Path indexHtml = Path.of(templatesDir, "index.html");
        String html = Files.readString(indexHtml);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }
}
