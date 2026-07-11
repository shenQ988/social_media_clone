package com.pixframe.controller;

import com.pixframe.dao.PostDao;
import com.pixframe.dao.UserDao;
import com.pixframe.util.ApiError;
import com.pixframe.util.AuthUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Ports pixframe/api/users.py. */
@RestController
public class UsersController {

    private final UserDao userDao;
    private final PostDao postDao;
    private final AuthUtil authUtil;

    public UsersController(UserDao userDao, PostDao postDao, AuthUtil authUtil) {
        this.userDao = userDao;
        this.postDao = postDao;
        this.authUtil = authUtil;
    }

    // GET /api/v1/users/{username}/
    @GetMapping("/api/v1/users/{username}/")
    public ResponseEntity<?> showUser(@PathVariable String username,
                                       HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }
        Map<String, Object> user = userDao.findByUsername(username);
        if (user == null) {
            return ApiError.notFound();
        }

        List<Map<String, Object>> posts = postDao.findByOwner(username);
        List<Map<String, Object>> postsList = new ArrayList<>();
        for (Map<String, Object> post : posts) {
            Map<String, Object> postJson = new LinkedHashMap<>();
            postJson.put("postid", post.get("postid"));
            postJson.put("imgUrl", "/uploads/" + post.get("filename"));
            postJson.put("url", "/posts/" + post.get("postid") + "/");
            postsList.add(postJson);
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("username", username);
        context.put("fullname", user.get("fullname"));
        context.put("user_img_url", "/uploads/" + user.get("filename"));
        context.put("logname", logname);
        context.put("logname_follows_username", userDao.isFollowing(logname, username));
        context.put("followers", userDao.countFollowers(username));
        context.put("following", userDao.countFollowing(username));
        context.put("total_posts", postDao.countByOwner(username));
        context.put("posts", postsList);
        return ResponseEntity.ok(context);
    }

    // GET /api/v1/users/{username}/followers/
    @GetMapping("/api/v1/users/{username}/followers/")
    public ResponseEntity<?> showFollowers(@PathVariable String username,
                                            HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }
        if (userDao.findByUsername(username) == null) {
            return ApiError.notFound();
        }

        List<Map<String, Object>> rows = userDao.findFollowers(username);
        List<Map<String, Object>> followers = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String rowUsername = (String) row.get("username");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("username", rowUsername);
            entry.put("user_img_url", "/uploads/" + row.get("user_img_url"));
            entry.put("logname_follows_username", userDao.isFollowing(logname, rowUsername));
            followers.add(entry);
        }
        return ResponseEntity.ok(Map.of("followers", followers));
    }

    // GET /api/v1/users/{username}/following/
    @GetMapping("/api/v1/users/{username}/following/")
    public ResponseEntity<?> showFollowing(@PathVariable String username,
                                            HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }
        if (userDao.findByUsername(username) == null) {
            return ApiError.notFound();
        }

        List<Map<String, Object>> rows = userDao.findFollowing(username);
        List<Map<String, Object>> following = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String rowUsername = (String) row.get("username");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("username", rowUsername);
            entry.put("user_img_url", "/uploads/" + row.get("user_img_url"));
            entry.put("logname_follows_username", userDao.isFollowing(logname, rowUsername));
            following.add(entry);
        }
        return ResponseEntity.ok(Map.of("following", following));
    }

    // GET /api/v1/explore/
    @GetMapping("/api/v1/explore/")
    public ResponseEntity<?> explore(HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }

        List<Map<String, Object>> rows = userDao.findNotFollowing(logname);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("username", row.get("username"));
            entry.put("user_img_url", "/uploads/" + row.get("filename"));
            results.add(entry);
        }
        return ResponseEntity.ok(Map.of("results", results));
    }
}
