package com.example.demo;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendTemplateContractTest {

    private String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void bothRoutesServeTheSameReactShellWithCsrfCompatibility() throws Exception {
        String index = read("src/main/resources/templates/index.html");
        String auth = read("src/main/resources/templates/auth.html");
        assertThat(index).isEqualTo(auth)
                .contains("id=\"root\"", "type=\"module\"", "/js/app/app.js", "lang=\"zh-CN\"")
                .contains("th:content=\"${_csrf.token}\"", "th:content=\"${_csrf.headerName}\"")
                .doesNotContain("bootstrap.min", "cdn.jsdelivr", "onclick=", "dangerouslySetInnerHTML");
    }

    @Test
    void generatedEntryStylesheetAndBrandAssetsArePackagedLocally() throws Exception {
        String html = read("src/main/resources/templates/index.html");
        var matcher = Pattern.compile("(?:src|href)=\"(/js/app/[^\"]+)\"").matcher(html);
        int assets = 0;
        while (matcher.find()) {
            Path asset = Path.of("src/main/resources/static" + matcher.group(1));
            assertThat(asset).isRegularFile();
            assets++;
        }
        assertThat(assets).isGreaterThanOrEqualTo(3);
        assertThat(Path.of("src/main/resources/static/js/app/images/judge-illustration.svg")).isRegularFile();
    }

    @Test
    void businessBehaviorIsCoveredByExecutableReactTests() throws Exception {
        assertThat(read("frontend/src/hooks/useJudge.test.tsx"))
                .contains("creates exactly once", "reconciles the original ID", "does not synthesize CANCELLED",
                        "without trusting cached browser results");
        assertThat(read("frontend/src/lib/tracker.test.ts"))
                .contains("never overlaps polls", "rejects late stale responses", "never auto-start");
        assertThat(read("frontend/src/lib/judge.test.ts"))
                .contains("terminal regressions", "high-volume summary", "late mismatches");
        assertThat(read("frontend/src/lib/api.test.ts"))
                .contains("server-provided CSRF header", "does not trim passwords", "never automatically retries");
    }

    @Test
    void motionAndEditorAreScopedAndSupportReducedMotion() throws Exception {
        assertThat(read("frontend/src/components/Motion.tsx"))
                .contains("useGSAP", "scope: container", "revertOnUpdate: true", "prefers-reduced-motion");
        assertThat(read("frontend/src/styles-responsive.css"))
                .contains("prefers-reduced-motion: reduce", "forced-colors: active", "env(safe-area-inset-bottom)");
        assertThat(read("frontend/src/components/CodeWorkspace.tsx"))
                .contains("lazy(() => import('./CodeEditor'))", "role=\"tablist\"", "ArrowRight", "ArrowLeft");
    }
}
