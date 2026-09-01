package accent.web;

import org.junit.jupiter.api.Test;

import accent.web.Slug;
import accent.web.SlugCharacterPolicy;
import accent.web.SlugOptions;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

public class SlugTest {

    @Test
    public void basicGeneration() {
        Slug slug = Slug.from("Aircon Cleaning Services");
        assertEquals("aircon-cleaning-services", slug.value());
    }

    @Test
    public void whitespaceAndPunctuation() {
        Slug slug = Slug.from("  Hello,   World!!!  ");
        assertEquals("hello-world", slug.value());
    }

    @Test
    public void consecutiveSeparators() {
        Slug slug = Slug.from("Aircon---Cleaning___Services");
        assertEquals("aircon-cleaning-services", slug.value());
    }

    @Test
    public void diacritics() {
        Slug slug = Slug.from("Café Déjà Vu");
        assertEquals("cafe-deja-vu", slug.value());
    }

    @Test
    public void unicodeMode() {
        SlugOptions options = SlugOptions.builder()
                .characterPolicy(SlugCharacterPolicy.UNICODE)
                .build();

        Slug slug = Slug.from("東京 レストラン", options);
        assertEquals("東京-レストラン", slug.value());
    }

    @Test
    public void lowercasingIsLocaleNeutral() {
        Locale defaultLocale = Locale.getDefault();
        try {
            // Test in Turkish locale where 'I' lowercases to dotted 'i' ('ı' vs 'i')
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            
            Slug slug = Slug.from("INPUT");
            assertEquals("input", slug.value());
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    public void validParsing() {
        Slug slug = Slug.parse("aircon-cleaning");
        assertEquals("aircon-cleaning", slug.value());
    }

    @Test
    public void invalidParsing() {
        assertThrows(IllegalArgumentException.class, () -> Slug.parse("Aircon-Cleaning"));
        assertThrows(IllegalArgumentException.class, () -> Slug.parse("-aircon-cleaning"));
        assertThrows(IllegalArgumentException.class, () -> Slug.parse("aircon-cleaning-"));
        assertThrows(IllegalArgumentException.class, () -> Slug.parse("aircon--cleaning"));
        assertThrows(IllegalArgumentException.class, () -> Slug.parse("aircon cleaning"));
        assertThrows(IllegalArgumentException.class, () -> Slug.parse("aircon_cleaning"));
    }

    @Test
    public void validation() {
        assertTrue(Slug.isValid("aircon-cleaning"));
        assertFalse(Slug.isValid("Aircon Cleaning"));
        assertFalse(Slug.isValid(null));
        assertFalse(Slug.isValid(""));
        assertFalse(Slug.isValid("   "));
    }

    @Test
    public void emptyNormalizationResult() {
        assertThrows(IllegalArgumentException.class, () -> Slug.from(""));
        assertThrows(IllegalArgumentException.class, () -> Slug.from("   "));
        assertThrows(IllegalArgumentException.class, () -> Slug.from("!!!"));
        assertThrows(IllegalArgumentException.class, () -> Slug.from("---"));
    }

    @Test
    public void nullHandling() {
        assertThrows(NullPointerException.class, () -> Slug.from(null));
        assertThrows(NullPointerException.class, () -> Slug.parse(null));
        assertThrows(NullPointerException.class, () -> Slug.from("ok").append(null));
    }

    @Test
    public void equality() {
        Slug first = Slug.from("Hello World");
        Slug second = Slug.parse("hello-world");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void inequality() {
        Slug first = Slug.from("Hello World");
        Slug second = Slug.from("Goodbye World");

        assertNotEquals(first, second);
    }

    @Test
    public void stringRepresentation() {
        Slug slug = Slug.from("Aircon Cleaning");
        assertEquals("aircon-cleaning", slug.toString());
    }

    @Test
    public void append() {
        Slug category = Slug.from("Home Services");
        Slug service = category.append("Aircon Cleaning");

        assertEquals("home-services-aircon-cleaning", service.value());
        assertEquals("home-services", category.value());
    }

    @Test
    public void appendNormalization() {
        Slug slug = Slug.from("home-services").append("  Aircon / Cleaning!!! ");
        assertEquals("home-services-aircon-cleaning", slug.value());
    }

    @Test
    public void customSeparator() {
        SlugOptions options = SlugOptions.builder()
                .separator("_")
                .build();

        Slug slug = Slug.from("Aircon Cleaning", options);
        assertEquals("aircon_cleaning", slug.value());
    }

    @Test
    public void maximumLength() {
        SlugOptions options = SlugOptions.builder()
                .maximumLength(20)
                .build();

        Slug slug = Slug.from("Aircon Cleaning Services Area", options);
        // "aircon-cleaning-services-area" -> max length 20: "aircon-cleaning-serv" -> last separator is at index 15
        assertEquals("aircon-cleaning", slug.value());

        // Test boundary where separator isn't used
        SlugOptions optionsShort = SlugOptions.builder()
                .maximumLength(5)
                .build();
        Slug slugShort = Slug.from("aircon", optionsShort);
        assertEquals("airco", slugShort.value());
    }

    @Test
    public void threadSafety() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(16);
        List<Future<Slug>> futures = new ArrayList<>();
        
        for (int i = 0; i < 1000; i++) {
            futures.add(executor.submit(() -> Slug.from("Thread Safety Test Input")));
        }

        Slug expected = Slug.from("Thread Safety Test Input");
        for (Future<Slug> future : futures) {
            Slug slug = future.get();
            assertEquals(expected, slug);
            assertTrue(Slug.isValid(slug.value()));
        }
        
        executor.shutdown();
    }
}
