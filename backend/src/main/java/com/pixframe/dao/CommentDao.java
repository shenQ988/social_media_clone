package com.pixframe.dao;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Ports the `comments` table queries from pixframe/api/comments.py and pixframe/views/*.py. */
@Repository
public class CommentDao {

    private final JdbcTemplate jdbc;

    public CommentDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> findByPostid(int postid) {
        return jdbc.queryForList(
                "SELECT commentid, owner, text FROM comments "
                        + "WHERE postid = ? ORDER BY commentid ASC",
                postid);
    }

    public int create(String owner, int postid, String text) {
        return jdbc.queryForObject(
                "INSERT INTO comments (owner, postid, text) VALUES (?, ?, ?) "
                        + "RETURNING commentid",
                Integer.class, owner, postid, text);
    }

    /** Returns the comment's owner, or null if it doesn't exist. */
    public String findOwner(int commentid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT owner FROM comments WHERE commentid = ?", commentid);
        return rows.isEmpty() ? null : (String) rows.get(0).get("owner");
    }

    /** Returns the postid a comment belongs to, or null if it doesn't exist.
     * Used to invalidate PostDetailCache when a comment is deleted. */
    public Integer findPostid(int commentid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT postid FROM comments WHERE commentid = ?", commentid);
        return rows.isEmpty() ? null : (Integer) rows.get(0).get("postid");
    }

    public void delete(int commentid) {
        jdbc.update("DELETE FROM comments WHERE commentid = ?", commentid);
    }
}
