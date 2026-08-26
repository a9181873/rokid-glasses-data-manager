package tw.dky.rokidfiles.share;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class ShareRouteTest {
    @Test
    public void resolvesEveryKnownRouteByMethodAndPath() {
        for (ShareRoute route : ShareRoute.values()) {
            assertEquals(route, ShareRoute.resolve(route.method(), route.path()));
        }
    }

    @Test
    public void rejectsWrongMethodAndUnknownPath() {
        assertNull(ShareRoute.resolve("GET", "/api/upload"));
        assertNull(ShareRoute.resolve("POST", "/api/files"));
        assertNull(ShareRoute.resolve("DELETE", "/api/file"));
        assertNull(ShareRoute.resolve("GET", "/api/not-real"));
    }
}
