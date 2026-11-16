package accent.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeBoundaryTest {
    @Test
    void runtimeDoesNotPullInCompilerOrBackend() {
        for (String dependency : new String[]{"accent.compiler.AccentCompiler", "accent.cli.AccentCli",
                "accent.http.App", "accent.html.Html", "accent.sql.Sql", "io.undertow.Undertow"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(dependency), dependency);
        }
    }
}
