package com.pixframe.controller;

import com.pixframe.dao.LikeDao;
import com.pixframe.dao.PostDao;
import com.pixframe.util.ApiError;
import com.pixframe.util.AuthUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ports pixframe/api/likes.py. */
@RestController
public class LikesController {

    private final LikeDao likeDao;
    private final PostDao postDao;
    private final AuthUtil authUtil;

    public LikesController(LikeDao likeDao, PostDao postDao, AuthUtil authUtil) {
        this.likeDao = likeDao;
        this.postDao = postDao;
        this.authUtil = authUtil;
    }

    // POST /api/v1/likes/?postid=<postid>
    @PostMapping("/api/v1/likes/")
    public ResponseEntity<?> createLike(
            @RequestParam(name = "postid", required = false) Integer postid,
            HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }
        if (postid == null) {
            return ApiError.badRequest();
        }
        if (!postDao.exists(postid)) {
            return ApiError.notFound();
        }

        Integer existingLikeId = likeDao.findLikeId(logname, postid);
        if (existingLikeId != null) {
            return ResponseEntity.ok(Map.of(
                    "likeid", existingLikeId,
                    "url", "/api/v1/likes/" + existingLikeId + "/"));
        }

        int likeid = likeDao.create(logname, postid);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "likeid", likeid,
                "url", "/api/v1/likes/" + likeid + "/"));
    }

    // DELETE /api/v1/likes/{likeid}/
    @DeleteMapping("/api/v1/likes/{likeid}/")
    public ResponseEntity<?> deleteLike(@PathVariable int likeid, HttpServletRequest request) {
        String logname = authUtil.checkAuth(request);
        if (logname == null) {
            return ApiError.forbidden();
        }
        String owner = likeDao.findOwner(likeid);
        if (owner == null) {
            return ApiError.notFound();
        }
        if (!owner.equals(logname)) {
            return ApiError.forbidden();
        }
        likeDao.delete(likeid);
        return ResponseEntity.noContent().build();
    }
}
