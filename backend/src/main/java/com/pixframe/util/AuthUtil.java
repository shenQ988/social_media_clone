package com.pixframe.util;

import com.pixframe.dao.UserDao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Ports pixframe/api/api_utils.py's check_auth(): session first, then HTTP
 * Basic Auth as a fallback (so the REST API stays usable by non-browser
 * clients, same as the Flask version).
 */
@Component
public class AuthUtil {

    private static final String SESSION_USERNAME_KEY = "username";

    private final UserDao userDao;

    public AuthUtil(UserDao userDao) {
        this.userDao = userDao;
    }

    /** Returns the authenticated username, or null if not authenticated. */
    public String checkAuth(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object username = session.getAttribute(SESSION_USERNAME_KEY);
            if (username != null) {
                return (String) username;
            }
        }

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Basic ")) {
            String[] credentials = decodeBasicAuth(header);
            if (credentials != null
                    && verifyUsernamePassword(credentials[0], credentials[1])) {
                return credentials[0];
            }
        }

        return null;
    }

    /**
     * Session-only check (no Basic Auth fallback) -- mirrors the plain
     * `'username' not in flask.session` guard used by the /uploads/ route
     * in pixframe/views/index.py, distinct from the API's checkAuth().
     */
    public String sessionUsername(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        return (String) session.getAttribute(SESSION_USERNAME_KEY);
    }

    public void login(HttpServletRequest request, String username) {
        request.getSession(true).setAttribute(SESSION_USERNAME_KEY, username);
    }

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    public boolean verifyUsernamePassword(String username, String password) {
        Map<String, Object> user = userDao.findByUsername(username);
        if (user == null) {
            return false;
        }
        return PasswordUtil.verify(password, (String) user.get("password"));
    }

    private String[] decodeBasicAuth(String header) {
        try {
            String base64Credentials = header.substring("Basic ".length()).trim();
            String decoded = new String(
                    Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator < 0) {
                return null;
            }
            return new String[]{
                    decoded.substring(0, separator), decoded.substring(separator + 1)
            };
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
