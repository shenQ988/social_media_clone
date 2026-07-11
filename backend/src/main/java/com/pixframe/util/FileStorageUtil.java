package com.pixframe.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Ports pixframe/views/utils.py's save_uploaded_file(). */
@Component
public class FileStorageUtil {

    @Value("${app.upload-dir}")
    private String uploadDir;

    /** Saves the uploaded file under a fresh UUID name, returns that filename. */
    public String save(MultipartFile file) throws IOException {
        String originalName = file.getOriginalFilename();
        String extension = "";
        if (originalName != null && originalName.contains(".")) {
            extension = originalName
                    .substring(originalName.lastIndexOf('.'))
                    .toLowerCase();
        }
        String filename = UUID.randomUUID().toString().replace("-", "") + extension;
        Path destination = Path.of(uploadDir, filename);
        Files.createDirectories(destination.getParent());
        file.transferTo(destination);
        return filename;
    }

    public boolean delete(String filename) {
        try {
            return Files.deleteIfExists(Path.of(uploadDir, filename));
        } catch (IOException e) {
            return false;
        }
    }

    public Path resolve(String filename) {
        return Path.of(uploadDir, filename);
    }
}
