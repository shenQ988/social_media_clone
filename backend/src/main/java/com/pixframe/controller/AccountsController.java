package com.pixframe.controller;

import com.pixframe.dao.PostDao;
import com.pixframe.dao.UserDao;
import com.pixframe.util.ApiError;
import com.pixframe.util.AuthUtil;
import com.pixframe.util.FileStorageUtil;
import com.pixframe.util.PasswordUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Ports pixframe/api/auth.py. */
@RestController
public class AccountsController {

    private final UserDao userDao;
    private final PostDao postDao;
    private final AuthUtil authUtil;
    private final FileStorageUtil fileStorage;

    public AccountsController(UserDao userDao, PostDao postDao, AuthUtil authUtil,
                               FileStorageUtil fileStorage) {
        this.userDao = userDao;
        this.postDao = postDao;
        this.authUtil = authUtil;
        this.fileStorage = fileStorage;
    }

    // GET /api/v1/accounts/auth/
    @GetMapping("/api/v1/accounts/auth/")
    public ResponseEntity<?> checkAuth(HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }
        return ResponseEntity.ok(Map.of("logname", logname));
    }

    // POST /api/v1/accounts/login/
    @PostMapping("/api/v1/accounts/login/")
    public ResponseEntity<?> login(@RequestBody(required = false) Map<String, String> body,
                                    HttpServletRequest request) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");
        if (isBlank(username) || isBlank(password)) {
            return ApiError.badRequest();
        }
        if (!authUtil.verifyUsernamePassword(username, password)) {
            return ApiError.forbidden();
        }
        authUtil.login(request, username);
        return ResponseEntity.ok(Map.of("logname", username));
    }

    // GET /api/v1/accounts/  (the logged-in user's own account details)
    @GetMapping("/api/v1/accounts/")
    public ResponseEntity<?> getAccount(HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }
        Map<String, Object> user = userDao.findByUsername(logname);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("username", logname);
        response.put("fullname", user.get("fullname"));
        response.put("email", user.get("email"));
        response.put("user_img_url", "/uploads/" + user.get("filename"));
        return ResponseEntity.ok(response);
    }

    // POST /api/v1/accounts/logout/
    @PostMapping("/api/v1/accounts/logout/")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        authUtil.logout(request);
        return ResponseEntity.noContent().build();
    }

    // POST /api/v1/accounts/  (create account, multipart)
    @PostMapping("/api/v1/accounts/")
    public ResponseEntity<?> createAccount(
            @RequestParam("fullname") String fullname,
            @RequestParam("username") String username,
            @RequestParam("email") String email,
            @RequestParam("password") String password,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws IOException {
        if (isBlank(fullname) || isBlank(username) || isBlank(email)
                || isBlank(password) || file.isEmpty()) {
            return ApiError.badRequest();
        }
        if (userDao.exists(username)) {
            return ApiError.conflict();
        }

        String passwordHash = PasswordUtil.hashPassword(password);
        String savedFilename = fileStorage.save(file);
        userDao.create(username, fullname, email, savedFilename, passwordHash);
        authUtil.login(request, username);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("logname", username));
    }

    // DELETE /api/v1/accounts/
    @DeleteMapping("/api/v1/accounts/")
    public ResponseEntity<?> deleteAccount(HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }

        Map<String, Object> user = userDao.findByUsername(logname);
        fileStorage.delete((String) user.get("filename"));

        List<Map<String, Object>> posts = postDao.findByOwner(logname);
        for (Map<String, Object> post : posts) {
            fileStorage.delete((String) post.get("filename"));
        }

        userDao.delete(logname);
        authUtil.logout(request);
        return ResponseEntity.noContent().build();
    }

    // PATCH /api/v1/accounts/  (edit fullname/email/photo)
    @PatchMapping("/api/v1/accounts/")
    public ResponseEntity<?> editAccount(
            @RequestParam("fullname") String fullname,
            @RequestParam("email") String email,
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest request) throws IOException {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }
        if (isBlank(fullname) || isBlank(email)) {
            return ApiError.badRequest();
        }

        userDao.updateProfile(logname, fullname, email);

        String filename = (String) userDao.findByUsername(logname).get("filename");
        if (file != null && !file.isEmpty()) {
            String oldFilename = filename;
            filename = fileStorage.save(file);
            userDao.updateFilename(logname, filename);
            fileStorage.delete(oldFilename);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("logname", logname);
        response.put("fullname", fullname);
        response.put("email", email);
        response.put("filename", filename);
        return ResponseEntity.ok(response);
    }

    // PUT /api/v1/accounts/password/
    @PutMapping("/api/v1/accounts/password/")
    public ResponseEntity<?> updatePassword(@RequestBody(required = false) Map<String, String> body,
                                             HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }

        String oldPassword = body == null ? null : body.get("password");
        String newPassword1 = body == null ? null : body.get("new_password1");
        String newPassword2 = body == null ? null : body.get("new_password2");
        if (isBlank(oldPassword) || isBlank(newPassword1) || isBlank(newPassword2)) {
            return ApiError.badRequest();
        }

        Map<String, Object> user = userDao.findByUsername(logname);
        if (!PasswordUtil.verify(oldPassword, (String) user.get("password"))) {
            return ApiError.forbidden();
        }
        if (!newPassword1.equals(newPassword2)) {
            return ApiError.badRequest();
        }

        userDao.updatePassword(logname, PasswordUtil.hashPassword(newPassword1));
        return ResponseEntity.noContent().build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
