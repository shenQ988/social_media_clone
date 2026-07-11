package com.pixframe.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Ports the `posts` table queries from pixframe/api/posts.py and pixframe/views/*.py. */
@Repository
public class PostDao {

    private final JdbcTemplate jdbc;

    public PostDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Feed page: posts by `logname` or people they follow, newest first. */
    public List<Map<String, Object>> findFeedPage(String logname, Integer postidLte,
                                                   int pageSize, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT postid FROM posts "
                        + "WHERE (posts.owner = ? OR posts.owner IN "
                        + "(SELECT followee FROM following WHERE following.follower = ?)) ");
        List<Object> params = new ArrayList<>(List.of(logname, logname));

        if (postidLte != null) {
            sql.append("AND postid <= ? ");
            params.add(postidLte);
        }
        sql.append("ORDER BY postid DESC LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add(offset);
        //offset is saying skip first first N matching rows before applying that limit
        // returns post id 
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    /** Single post + owner's avatar, for the post-detail endpoint. */
    public Map<String, Object> findDetail(int postid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.filename AS post_filename, p.owner, p.created, "
                        + "u.filename AS owner_img_filename "
                        + "FROM posts p JOIN users u ON p.owner = u.username "
                        + "WHERE p.postid = ?",
                postid);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Bare row (filename, owner) used for ownership checks on delete. */
    public Map<String, Object> findOwnership(int postid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT filename, owner FROM posts WHERE postid = ?", postid);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean exists(int postid) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE postid = ?", Integer.class, postid);
        return count != null && count > 0;
    }

    public int create(String filename, String owner) {
        return jdbc.queryForObject(
                "INSERT INTO posts (filename, owner) VALUES (?, ?) RETURNING postid",
                Integer.class, filename, owner);
    }

    public void delete(int postid) {
        jdbc.update("DELETE FROM posts WHERE postid = ?", postid);
    }

    public int countByOwner(String owner) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE owner = ?", Integer.class, owner);
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> findByOwner(String owner) {
        return jdbc.queryForList(
                "SELECT postid, filename FROM posts WHERE owner = ? ORDER BY postid DESC",
                owner);
    }
}
