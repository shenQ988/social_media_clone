package com.pixframe.dao;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Ports the `users` table queries scattered across pixframe/api/*.py and pixframe/views/*.py. */
@Repository
public class UserDao {

    private final JdbcTemplate jdbc;

    public UserDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the user row (username, fullname, email, filename, password), or null. */
    public Map<String, Object> findByUsername(String username) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT username, fullname, email, filename, password "
                        + "FROM users WHERE username = ?",
                username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean exists(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    public void create(String username, String fullname, String email,
                        String filename, String passwordHash) {
        jdbc.update(
                "INSERT INTO users (username, fullname, email, filename, password) "
                        + "VALUES (?, ?, ?, ?, ?)",
                username, fullname, email, filename, passwordHash);
    }

    public void delete(String username) {
        jdbc.update("DELETE FROM users WHERE username = ?", username);
    }

    public void updateProfile(String username, String fullname, String email) {
        jdbc.update(
                "UPDATE users SET fullname = ?, email = ? WHERE username = ?",
                fullname, email, username);
    }

    public void updateFilename(String username, String filename) {
        jdbc.update(
                "UPDATE users SET filename = ? WHERE username = ?", filename, username);
    }

    public void updatePassword(String username, String passwordHash) {
        jdbc.update(
                "UPDATE users SET password = ? WHERE username = ?", passwordHash, username);
    }

    public int countFollowers(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM following WHERE followee = ?", Integer.class, username);
        return count == null ? 0 : count;
    }

    public int countFollowing(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM following WHERE follower = ?", Integer.class, username);
        return count == null ? 0 : count;
    }

    public boolean isFollowing(String follower, String followee) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT 1 FROM following WHERE follower = ? AND followee = ?",
                follower, followee);
        return !rows.isEmpty();
    }

    public List<Map<String, Object>> findFollowers(String username) {
        return jdbc.queryForList(
                "SELECT users.username, users.filename AS user_img_url "
                        + "FROM users "
                        + "JOIN following ON users.username = following.follower "
                        + "WHERE following.followee = ?",
                username);
    }

    public List<Map<String, Object>> findFollowing(String username) {
        return jdbc.queryForList(
                "SELECT users.username, users.filename AS user_img_url "
                        + "FROM users "
                        + "JOIN following ON users.username = following.followee "
                        + "WHERE following.follower = ?",
                username);
    }

    public List<Map<String, Object>> findNotFollowing(String logname) {
        return jdbc.queryForList(
                "SELECT username, filename FROM users "
                        + "WHERE username != ? "
                        + "AND username NOT IN "
                        + "(SELECT followee FROM following WHERE follower = ?)",
                logname, logname);
    }

    public void follow(String follower, String followee) {
        jdbc.update(
                "INSERT INTO following (follower, followee) VALUES (?, ?)",
                follower, followee);
    }

    public void unfollow(String follower, String followee) {
        jdbc.update(
                "DELETE FROM following WHERE follower = ? AND followee = ?",
                follower, followee);
    }
}
