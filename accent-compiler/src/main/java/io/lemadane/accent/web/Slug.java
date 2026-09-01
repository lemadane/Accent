package io.lemadane.accent.web;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class Slug {
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final String value;

    private Slug(String value) {
        this.value = value;
    }

    public static Slug from(String text) {
        return from(text, SlugOptions.defaults());
    }

    public static Slug from(String text, SlugOptions options) {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(options, "options must not be null");

        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }

        String canonical = normalize(text, options);
        return new Slug(canonical);
    }

    public static Slug parse(String value) {
        return parse(value, SlugOptions.defaults());
    }

    public static Slug parse(String value, SlugOptions options) {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(options, "options must not be null");

        if (!isValid(value, options)) {
            throw new IllegalArgumentException("Invalid slug: " + value);
        }

        return new Slug(value);
    }

    public static boolean isValid(String value) {
        return isValid(value, SlugOptions.defaults());
    }

    public static boolean isValid(String value, SlugOptions options) {
        if (value == null || options == null) {
            return false;
        }
        if (value.isBlank()) {
            return false;
        }
        try {
            String canonical = normalize(value, options);
            return canonical.equals(value);
        } catch (Exception e) {
            return false;
        }
    }

    public String value() {
        return value;
    }

    public Slug append(String text) {
        return append(text, SlugOptions.defaults());
    }

    public Slug append(String text, SlugOptions options) {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(options, "options must not be null");

        Slug appended = Slug.from(text, options);
        return Slug.parse(this.value + options.separator() + appended.value, options);
    }

    private static String normalize(String text, SlugOptions options) {
        String current = text.trim();
        if (current.isEmpty()) {
            throw new IllegalArgumentException("Slug cannot be empty after normalization.");
        }

        current = Normalizer.normalize(current, Normalizer.Form.NFD);
        current = COMBINING_MARKS.matcher(current).replaceAll("");
        current = current.toLowerCase(Locale.ROOT);

        char sepChar = options.separator().charAt(0);
        StringBuilder sb = new StringBuilder();
        boolean lastWasSeparator = false;

        for (int i = 0; i < current.length(); i++) {
            char c = current.charAt(i);
            boolean isValidChar = false;
            if (options.characterPolicy() == SlugCharacterPolicy.ASCII) {
                isValidChar = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            } else {
                isValidChar = Character.isLetter(c) || Character.isDigit(c);
            }

            if (isValidChar) {
                sb.append(c);
                lastWasSeparator = false;
            } else {
                if (!options.collapseSeparators() || !lastWasSeparator) {
                    sb.append(sepChar);
                    lastWasSeparator = true;
                }
            }
        }

        String result = sb.toString();

        if (options.trimSeparators()) {
            int start = 0;
            int end = result.length();
            while (start < end && result.charAt(start) == sepChar) {
                start++;
            }
            while (end > start && result.charAt(end - 1) == sepChar) {
                end--;
            }
            result = result.substring(start, end);
        }

        if (result.length() > options.maximumLength()) {
            int maxLen = options.maximumLength();
            String truncated = result.substring(0, maxLen);
            int lastSep = truncated.lastIndexOf(sepChar);
            if (lastSep > 0) {
                result = truncated.substring(0, lastSep);
            } else {
                result = truncated;
            }

            if (options.trimSeparators()) {
                int start = 0;
                int end = result.length();
                while (start < end && result.charAt(start) == sepChar) {
                    start++;
                }
                while (end > start && result.charAt(end - 1) == sepChar) {
                    end--;
                }
                result = result.substring(start, end);
            }
        }

        if (result.isEmpty()) {
            throw new IllegalArgumentException("Slug cannot be empty after normalization.");
        }

        return result;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Slug slug && value.equals(slug.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
