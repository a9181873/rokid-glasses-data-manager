package tw.dky.rokidfiles.share;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Authenticated local-share API route table. Public assets and pairing are handled separately. */
enum ShareRoute {
    FILES("GET", "/api/files"),
    FILE("GET", "/api/file"),
    THUMB("GET", "/api/thumb"),
    DUPLICATES("GET", "/api/duplicates"),

    TRASH("POST", "/api/trash"),
    RESTORE("POST", "/api/restore"),
    DELETE("POST", "/api/delete"),
    EMPTY_TRASH("POST", "/api/trash/empty"),
    RENAME("POST", "/api/rename"),
    FAVORITE("POST", "/api/favorite"),
    PROTECTED("POST", "/api/protected"),
    UPLOAD("POST", "/api/upload"),
    SCAN_DUPLICATES("POST", "/api/duplicates/scan"),
    REMOTE("POST", "/api/remote");

    private static final Map<String, ShareRoute> ROUTES;

    static {
        Map<String, ShareRoute> routes = new HashMap<>();
        for (ShareRoute route : values()) {
            ShareRoute previous = routes.put(key(route.method, route.path), route);
            if (previous != null) {
                throw new AssertionError("Duplicate share route: " + route.method + " " + route.path);
            }
        }
        ROUTES = Collections.unmodifiableMap(routes);
    }

    private final String method;
    private final String path;

    ShareRoute(String method, String path) {
        this.method = method;
        this.path = path;
    }

    String method() {
        return method;
    }

    String path() {
        return path;
    }

    static ShareRoute resolve(String method, String path) {
        if (method == null || path == null) {
            return null;
        }
        return ROUTES.get(key(method, path));
    }

    private static String key(String method, String path) {
        return method + '\n' + path;
    }
}
