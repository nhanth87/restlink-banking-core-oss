package et.restlink.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminPageRendererTest {

    @TempDir
    Path tmp;

    @Test
    void substitutesTokensAndStripsLeftovers() {
        String html = AdminPageRenderer.applyTemplateVars(
                "node={{NODE}} leftover={{UNKNOWN}}",
                Map.of("{{NODE}}", "restlink-1"));
        assertEquals("node=restlink-1 leftover=", html);
    }

    @Test
    void escapesHtml() {
        assertEquals("&lt;script&gt;", AdminPageRenderer.esc("<script>"));
        assertEquals("&amp;quot;", AdminPageRenderer.esc("&quot;"));
    }

    @Test
    void readsDiskPartial() throws Exception {
        Path partials = tmp.resolve("partials");
        Files.createDirectories(partials);
        Files.writeString(partials.resolve("core-status.html"),
                "<span>{{MONEY_READY}}</span>");
        AdminPageRenderer r = new AdminPageRenderer();
        r.uiDir = tmp.toString();
        String out = r.partial("core-status.html", Map.of("{{MONEY_READY}}", "true"));
        assertEquals("<span>true</span>", out);
        assertFalse(out.contains("{{"));
    }

    @Test
    void missingFileFallsBackWithoutRawToken() {
        AdminPageRenderer r = new AdminPageRenderer();
        r.uiDir = tmp.toString();
        String html = r.page("index.html", Map.of("{{TOKEN}}", "k", "{{CORE_STATUS}}", "ok"));
        assertTrue(html.contains("Restlink"));
        assertFalse(html.contains("{{TOKEN}}"));
    }
}
