package et.restlink.admin;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Disk-backed admin HTML ({@code restlink.admin.ui-dir}, default {@code app/html}).
 * Templates use {@code {{TOKEN}}} substitution — leftovers are stripped, never left raw.
 */
@ApplicationScoped
public class AdminPageRenderer {

    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{\\{[A-Z][A-Z0-9_]*\\}\\}");

    @ConfigProperty(name = "restlink.admin.ui-dir", defaultValue = "app/html")
    String uiDir;

    public String page(String name, Map<String, String> vars) {
        return renderFrom(uiRoot(), "admin/" + name, vars, fallbackDashboard());
    }

    public String partial(String name, Map<String, String> vars) {
        String rel = name.startsWith("partials/") ? name : "partials/" + name;
        return renderFrom(uiRoot(), rel, vars, fallbackCoreStatus(vars));
    }

    static String applyTemplateVars(String html, Map<String, String> vars) {
        if (html == null) {
            return "";
        }
        String out = html;
        if (vars != null) {
            for (var entry : vars.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                out = out.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
        }
        Matcher leftover = TEMPLATE_TOKEN.matcher(out);
        if (leftover.find()) {
            leftover.reset();
            out = leftover.replaceAll("");
        }
        return out;
    }

    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    Path uiRoot() {
        return Path.of(uiDir == null || uiDir.isBlank() ? "app/html" : uiDir);
    }

    private static String renderFrom(Path root, String rel, Map<String, String> vars, String fallback) {
        try {
            Path f = root.resolve(rel).normalize();
            if (!f.startsWith(root.normalize()) || !Files.isRegularFile(f)) {
                return applyTemplateVars(fallback, vars);
            }
            String html = Files.readString(f, StandardCharsets.UTF_8);
            return applyTemplateVars(html, vars);
        } catch (IOException e) {
            return applyTemplateVars(fallback, vars);
        }
    }

    static String fallbackDashboard() {
        return """
                <!DOCTYPE html>
                <html lang="en"><head><meta charset="utf-8"><title>Restlink admin</title></head>
                <body>
                <h1>Restlink</h1>
                <div id="core-status" hx-get="/admin/partial/core-status?key={{TOKEN}}" hx-trigger="every 5s" hx-swap="innerHTML">
                {{CORE_STATUS}}
                </div>
                </body></html>
                """;
    }

    static String fallbackCoreStatus(Map<String, String> vars) {
        String money = vars == null ? "" : vars.getOrDefault("{{MONEY_READY}}", "");
        String live = vars == null ? "" : vars.getOrDefault("{{CORE_LIVE}}", "");
        String sagas = vars == null ? "" : vars.getOrDefault("{{SAGA_COUNTS}}", "");
        return "<dl><dt>moneyReady</dt><dd>" + money + "</dd><dt>coreLive</dt><dd>"
                + live + "</dd><dt>sagas</dt><dd>" + sagas + "</dd></dl>";
    }
}
