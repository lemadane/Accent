package accent.compiler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Guards the classpath consumed by a future Android build-time integration. */
class CoreBoundaryTest {
    @Test
    void coreIncludesLanguageRuntimeWithoutBackendLibraries() throws Exception {
        assertNotNull(Class.forName("accent.runtime.AccentRuntime"));
        for (String backend : new String[]{"accent.http.App", "accent.html.Html", "accent.sql.Sql",
                "io.undertow.Undertow", "org.springframework.boot.SpringApplication"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(backend), backend);
        }
    }
}
