package com.pixframe.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Caches the JSON response of GET /api/v1/posts/{postid}/ in Redis.
 *
 * Keyed on (postid, logname) rather than postid alone: the response
 * contains per-viewer fields (likes.lognameLikesThis, each comment's
 * lognameOwnsThis) that differ depending on who's asking, so a
 * postid-only key would leak one user's like/comment-ownership status to
 * a different user viewing the same post.
 *
 * A per-postid tracking set records every (postid, logname) cache key
 * currently populated, so a write (new comment/like/etc.) can invalidate
 * every viewer's cached copy of that post without needing to scan Redis
 * keys by pattern.
 */

//component tells spring there is exactly one instance of this at application startup. 
@Component
public class PostDetailCache {

    private static final Duration ENTRY_TTL = Duration.ofSeconds(60);
    private static final Duration TRACKING_SET_TTL = Duration.ofSeconds(90);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PostDetailCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** Returns the cached response for (postid, logname), if present. */
    public Optional<Map<String, Object>> get(int postid, String logname) {
        try {
            String json = redis.opsForValue().get(entryKey(postid, logname));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(
                    objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { }));
        } catch (Exception e) {
            // Redis being unreachable or a corrupted/unreadable cache entry
            // should behave like a miss, not break the request.
            return Optional.empty();
        }
    }

    /** Caches `data` for (postid, logname) and records the key for invalidation. */
    public void put(int postid, String logname, Map<String, Object> data) {
        try {
            String key = entryKey(postid, logname);
            // e.g. post-detail:9:mkim
            redis.opsForValue().set(key, objectMapper.writeValueAsString(data), ENTRY_TTL);
            // set the value for the key
            // e.g of data: {"comments":[...],"comments_url":"...","created":"...","imgUrl":"...","likes":{...},"owner":"lchen",...}
            String trackingKey = trackingKey(postid);
            // add the post id to tracking list 
            redis.opsForSet().add(trackingKey, key);
            redis.expire(trackingKey, TRACKING_SET_TTL);
        } catch (Exception e) {
            // A cache write failure should never fail the request -- the
            // response was already computed from the DB regardless.
        }
    }

    /** Evicts every viewer's cached copy of `postid` (e.g. after a comment/like/delete). */
    public void invalidate(int postid) {
        String trackingKey = trackingKey(postid);
        Set<String> keys = redis.opsForSet().members(trackingKey);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.delete(trackingKey);
    }

    private static String entryKey(int postid, String logname) {
        return "post-detail:" + postid + ":" + logname;
    }

    private static String trackingKey(int postid) {
        return "post-cache-keys:" + postid;
    }
}
