package com.pixframe.dao;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Ports the `likes` table queries from pixframe/api/likes.py and pixframe/views/*.py. */
@Repository
public class LikeDao {

    private final JdbcTemplate jdbc;

    public LikeDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int countByPostid(int postid) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM likes WHERE postid = ?", Integer.class, postid);
        return count == null ? 0 : count;
    }

    /** Returns the likeid if `owner` already likes `postid`, else null. */
    public Integer findLikeId(String owner, int postid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT likeid FROM likes WHERE owner = ? AND postid = ?", owner, postid);
        return rows.isEmpty() ? null : (Integer) rows.get(0).get("likeid");
    }

    public int create(String owner, int postid) {
        return jdbc.queryForObject(
                "INSERT INTO likes (owner, postid) VALUES (?, ?) RETURNING likeid",
                Integer.class, owner, postid);
    }

    /** Returns the like's owner, or null if it doesn't exist. */
    public String findOwner(int likeid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT owner FROM likes WHERE likeid = ?", likeid);
        return rows.isEmpty() ? null : (String) rows.get(0).get("owner");
    }

    public void delete(int likeid) {
        jdbc.update("DELETE FROM likes WHERE likeid = ?", likeid);
    }
}
