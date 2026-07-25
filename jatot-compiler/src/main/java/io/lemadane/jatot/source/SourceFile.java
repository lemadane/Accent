package io.lemadane.jatot.source;

import java.nio.file.Path;
import java.util.Objects;

/** A Jatot source file loaded into memory. */
public record SourceFile(Path path, String content) {
    public SourceFile {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        content = Objects.requireNonNull(content, "content");
    }

    public String displayName() {
        return path.getFileName().toString();
    }
}
