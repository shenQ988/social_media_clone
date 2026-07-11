package com.pixframe.controller;

import com.pixframe.util.AuthUtil;
import com.pixframe.util.FileStorageUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Ports pixframe/views/index.py's download_file(). */
@RestController
public class UploadsController {

    private final AuthUtil authUtil;
    private final FileStorageUtil fileStorage;

    public UploadsController(AuthUtil authUtil, FileStorageUtil fileStorage) {
        this.authUtil = authUtil;
        this.fileStorage = fileStorage;
    }

    @GetMapping("/uploads/{filename:.+}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename,
                                                  HttpServletRequest request)
            throws IOException {
        if (authUtil.sessionUsername(request) == null) {
            return ResponseEntity.status(403).build();
        }

        Path path = fileStorage.resolve(filename);
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new UrlResource(path.toUri());
        String contentType = Files.probeContentType(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE,
                        contentType != null ? contentType : "application/octet-stream")
                .body(resource);
    }
}
