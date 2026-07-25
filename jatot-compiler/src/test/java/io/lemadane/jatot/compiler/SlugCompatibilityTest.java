package io.lemadane.jatot.compiler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SlugCompatibilityTest {

    private String slug = "aircon-cleaning";

    public String slug() {
        return slug;
    }

    @Test
    public void testSlugKeywordCompatibility() {
        SlugCompatibilityTest test = new SlugCompatibilityTest();
        assertEquals("aircon-cleaning", test.slug());
        
        // This valid Java must remain valid Jatot
        String slug = "local_value";
        assertEquals("local_value", slug);
    }

    @Test
    public void testJavaUsageOfSlugClass() {
        // Pure Java usage of the new standard library feature
        io.lemadane.jatot.web.Slug s = io.lemadane.jatot.web.Slug.from("Aircon Cleaning");
        assertEquals("aircon-cleaning", s.value());
    }
}
