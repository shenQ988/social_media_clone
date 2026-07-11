package com.pixframe.controller;

import com.pixframe.dao.UserDao;
import com.pixframe.util.ApiError;
import com.pixframe.util.AuthUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Ports pixframe/api/following.py. */
@RestController
public class FollowingController {

    private final UserDao userDao;
    private final AuthUtil authUtil;

    public FollowingController(UserDao userDao, AuthUtil authUtil) {
        this.userDao = userDao;
        this.authUtil = authUtil;
    }

    // POST /api/v1/following/
    @PostMapping("/api/v1/following/")
    public ResponseEntity<?> follow(@RequestBody(required = false) Map<String, String> body,
                                     HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }
        String username = body == null ? null : body.get("username");
        if (username == null || username.isBlank()) {
            return ApiError.badRequest();
        }
        if (userDao.findByUsername(username) == null) {
            return ApiError.notFound();
        }
        if (userDao.isFollowing(logname, username)) {
            return ApiError.conflict();
        }

        userDao.follow(logname, username);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("follower", logname, "followee", username));
    }

    // DELETE /api/v1/following/{username}/
    @DeleteMapping("/api/v1/following/{username}/")
    public ResponseEntity<?> unfollow(@PathVariable String username,
                                       HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }
        if (!userDao.isFollowing(logname, username)) {
            return ApiError.notFound();
        }
        userDao.unfollow(logname, username);
        return ResponseEntity.noContent().build();
    }
}
