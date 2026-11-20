package et.restlink.packaging;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2: packaging contract in {@code tools/package-dist.sh}. Does not claim a host redeploy.
 */
class PackageDistScriptTest {

    @Test
    void scriptGatesJdk25WritesNewConfigAndCopiesHtml() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isRegularFile(root.resolve("tools/package-dist.sh"))) {
            root = root.getParent();
        }
        String script = Files.readString(root.resolve("tools/package-dist.sh"));
        assertTrue(script.contains("JDK 25"), "must gate JDK 25");
        assertTrue(script.contains("[25,") || script.contains("*\\\"25*") || script.contains("\"25"),
                "java -version must match 25");
        assertTrue(script.contains("application.properties.new"), "never clobber host configs");
        assertTrue(script.contains("app/html"), "must ship HTMX UI");
        assertTrue(script.contains("quarkus-run.jar"), "fast-jar run");
        assertFalse(script.toLowerCase().contains(".war"), "never a WAR");
        assertTrue(Files.isRegularFile(root.resolve("app/html/admin/index.html")));
        assertTrue(Files.isRegularFile(root.resolve("app/html/admin/partials/core-status.html")));
        String index = Files.readString(root.resolve("app/html/admin/index.html"));
        assertTrue(index.contains("hx-get"), "HTMX partial poll");
        assertTrue(index.contains("{{TOKEN}}"));
        assertFalse(index.contains("change-me"), "must not bake lab default key into HTML");
    }
}
