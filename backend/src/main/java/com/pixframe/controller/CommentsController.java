package com.pixframe.controller;

import com.pixframe.cache.PostDetailCache;
import com.pixframe.dao.CommentDao;
import com.pixframe.dao.PostDao;
import com.pixframe.util.ApiError;
import com.pixframe.util.AuthUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ports pixframe/api/comments.py. */
@RestController
public class CommentsController {

    private final CommentDao commentDao;
    private final PostDao postDao;
    private final AuthUtil authUtil;
    private final PostDetailCache postDetailCache;

    public CommentsController(CommentDao commentDao, PostDao postDao, AuthUtil authUtil,
                               PostDetailCache postDetailCache) {
        this.commentDao = commentDao;
        this.postDao = postDao;
        this.authUtil = authUtil;
        this.postDetailCache = postDetailCache;
    }

    // POST /api/v1/comments/?postid=<postid>
    @PostMapping("/api/v1/comments/")
    public ResponseEntity<?> addComment(
            @RequestParam(name = "postid", required = false) Integer postid,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }
        if (postid == null) {
            return ApiError.badRequest();
        }
        String text = body == null ? null : body.get("text");
        if (text == null || text.isEmpty()) {
            return ApiError.badRequest();
        }
        if (!postDao.exists(postid)) {
            return ApiError.notFound();
        }

        // a new comment is added, and the postid key got invalidated
        int commentid = commentDao.create(logname, postid, text);
        postDetailCache.invalidate(postid);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("commentid", commentid);
        context.put("lognameOwnsThis", true);
        context.put("owner", logname);
        context.put("ownerShowUrl", "/users/" + logname + "/");
        context.put("text", text);
        context.put("url", "/api/v1/comments/" + commentid + "/");
        return ResponseEntity.status(HttpStatus.CREATED).body(context);
    }

    // DELETE /api/v1/comments/{commentid}/
    @DeleteMapping("/api/v1/comments/{commentid}/")
    public ResponseEntity<?> deleteComment(@PathVariable int commentid,
                                            HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }
        String owner = commentDao.findOwner(commentid);
        if (owner == null) {
            return ApiError.notFound();
        }
        if (!owner.equals(logname)) {
            return ApiError.forbidden();
        }

        // findPostid is defined in commendao, look up the post id
        // of that comment. 
        Integer postid = commentDao.findPostid(commentid);
        commentDao.delete(commentid);
        if (postid != null) {
            postDetailCache.invalidate(postid);
        }
        return ResponseEntity.noContent().build();
    }
}
