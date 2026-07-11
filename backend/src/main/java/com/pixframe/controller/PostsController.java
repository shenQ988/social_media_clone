package com.pixframe.controller;

import com.pixframe.dao.CommentDao;
import com.pixframe.dao.LikeDao;
import com.pixframe.dao.PostDao;
import com.pixframe.util.ApiError;
import com.pixframe.util.AuthUtil;
import com.pixframe.util.FileStorageUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Ports pixframe/api/posts.py. */
@RestController
public class PostsController {
    /* 
    dependency injection 
    */
    private final PostDao postDao;
    private final CommentDao commentDao;
    private final LikeDao likeDao;
    private final AuthUtil authUtil;
    private final FileStorageUtil fileStorage;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public PostsController(PostDao postDao, CommentDao commentDao, LikeDao likeDao,
                            AuthUtil authUtil, FileStorageUtil fileStorage) {
        this.postDao = postDao;
        this.commentDao = commentDao;
        this.likeDao = likeDao;
        this.authUtil = authUtil;
        this.fileStorage = fileStorage;
    }

    /** Formats a Postgres TIMESTAMP as "yyyy-MM-dd HH:mm:ss", matching the
     * plain-text format SQLite used to return (no fractional seconds). */
    private static String formatTimestamp(Object created) {
        if (created instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(TIMESTAMP_FORMAT);
        }
        return String.valueOf(created);
    }

    // GET /api/v1/
    @GetMapping("/api/v1/")
    // routes GET /api/v1/ requests to this method
    public ResponseEntity<?> getAllApi() {
        Map<String, Object> context = new LinkedHashMap<>(); // preserves order
        context.put("comments", "/api/v1/comments/");
        context.put("likes", "/api/v1/likes/");
        context.put("posts", "/api/v1/posts/");
        context.put("url", "/api/v1/");
        return ResponseEntity.ok(context);
    }

    // GET /api/v1/posts/  (paginated feed)
    @GetMapping("/api/v1/posts/")
    public ResponseEntity<?> getPosts(
            @RequestParam(name = "size", defaultValue = "10") int pageSize,
            // pulls values from the query string (? size=10&page=0&postid_lte=5) into method parameters
            @RequestParam(name = "page", defaultValue = "0") int pageNum,
            @RequestParam(name = "postid_lte", required = false) Integer postidLte,
            // required = false means it can be absent. 
            HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }
        if (pageNum < 0 || pageSize < 0) {
            return ApiError.badRequest();
        }

        int offset = pageSize * pageNum;
        // the pagination math is delegated to DAO
        List<Map<String, Object>> posts =
                postDao.findFeedPage(logname, postidLte, pageSize, offset);
        /* shape of the List 
                    [
            {"postid": 12},
            {"postid": 11},
            {"postid": 10}
            ]
        */

        List<Map<String, Object>> postInfo = new ArrayList<>();
        // holds one entry per row returned by the SQL query
        for (Map<String, Object> post : posts) {
            int postid = (int) post.get("postid");
            postInfo.add(Map.of("postid", postid, "url", "/api/v1/posts/" + postid + "/"));
        }

        /* shape of postInfo
                    [
            {"postid": 12, "url": "/api/v1/posts/12/"},
            {"postid": 11, "url": "/api/v1/posts/11/"},
            {"postid": 10, "url": "/api/v1/posts/10/"}
            ]
        */


        String nextUrl = "";
        if (posts.size() >= pageSize) {
            int nextLte = postidLte != null
                    ? postidLte
                    : (int) posts.get(0).get("postid");
            nextUrl = "/api/v1/posts/?size=" + pageSize
                    + "&page=" + (pageNum + 1) + "&postid_lte=" + nextLte;
        }


        String queryString = request.getQueryString();
        // get everything after the ? in the original request

        String currentUrl = request.getRequestURI()
                + (queryString != null ? "?" + queryString : "");
        // request.getRequestURI() return the part on /api/v1/posts/


        Map<String, Object> context = new LinkedHashMap<>();
        context.put("next", nextUrl);
        context.put("results", postInfo);
        context.put("url", currentUrl);
        return ResponseEntity.ok(context);
        // response entity is spring's representation of a complete HTTP response
    }

    // GET /api/v1/posts/{postid}/
    @GetMapping("/api/v1/posts/{postid}/")
    public ResponseEntity<?> getPostDetails(@PathVariable int postid,
                                             HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }

        Map<String, Object> post = postDao.findDetail(postid);
        if (post == null) {
            return ApiError.notFound();
        }

        List<Map<String, Object>> comments = commentDao.findByPostid(postid);
        List<Map<String, Object>> commentsList = new ArrayList<>();
        for (Map<String, Object> comment : comments) {
            String owner = (String) comment.get("owner");
            Map<String, Object> commentJson = new LinkedHashMap<>();
            commentJson.put("commentid", comment.get("commentid"));
            commentJson.put("lognameOwnsThis", owner.equals(logname));
            commentJson.put("owner", owner);
            commentJson.put("ownerShowUrl", "/users/" + owner + "/");
            commentJson.put("text", comment.get("text"));
            commentJson.put("url", "/api/v1/comments/" + comment.get("commentid") + "/");
            commentsList.add(commentJson);
        }

        int numLikes = likeDao.countByPostid(postid);
        Integer likeId = likeDao.findLikeId(logname, postid);
        boolean lognameLikesThis = likeId != null;

        Map<String, Object> likeObject = new LinkedHashMap<>();
        likeObject.put("lognameLikesThis", lognameLikesThis);
        likeObject.put("numLikes", numLikes);
        likeObject.put("url", lognameLikesThis ? "/api/v1/likes/" + likeId + "/" : null);

        String owner = (String) post.get("owner");
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("comments", commentsList);
        context.put("comments_url", "/api/v1/comments/?postid=" + postid);
        context.put("created", formatTimestamp(post.get("created")));
        context.put("imgUrl", "/uploads/" + post.get("post_filename"));
        context.put("likes", likeObject);
        context.put("owner", owner);
        context.put("ownerImgUrl", "/uploads/" + post.get("owner_img_filename"));
        context.put("ownerShowUrl", "/users/" + owner + "/");
        context.put("postShowUrl", "/posts/" + postid + "/");
        context.put("postid", postid);
        context.put("url", request.getRequestURI());
        return ResponseEntity.ok(context);
    }

    // POST /api/v1/posts/  (create post, multipart)
    @PostMapping("/api/v1/posts/")
    public ResponseEntity<?> createPost(@RequestParam(value = "file", required = false)
                                         MultipartFile file,
                                         HttpServletRequest request) throws IOException {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }
        if (file == null || file.isEmpty()) {
            return ApiError.badRequest();
        }

        String filename = fileStorage.save(file);
        int postid = postDao.create(filename, logname);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("postid", postid, "url", "/api/v1/posts/" + postid + "/"));
    }

    // DELETE /api/v1/posts/{postid}/
    @DeleteMapping("/api/v1/posts/{postid}/")
    public ResponseEntity<?> deletePost(@PathVariable int postid, HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }

        Map<String, Object> row = postDao.findOwnership(postid);
        if (row == null) {
            return ApiError.notFound();
        }
        if (!logname.equals(row.get("owner"))) {
            return ApiError.forbidden();
        }

        fileStorage.delete((String) row.get("filename"));
        postDao.delete(postid);
        return ResponseEntity.noContent().build();
    }
}
