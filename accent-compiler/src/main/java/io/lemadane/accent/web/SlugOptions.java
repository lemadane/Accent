package io.lemadane.accent.web;

import java.util.Objects;

public final class SlugOptions {
    private final SlugCharacterPolicy characterPolicy;
    private final String separator;
    private final int maximumLength;
    private final boolean collapseSeparators;
    private final boolean trimSeparators;

    private SlugOptions(SlugCharacterPolicy characterPolicy, String separator, int maximumLength, boolean collapseSeparators, boolean trimSeparators) {
        this.characterPolicy = characterPolicy;
        this.separator = separator;
        this.maximumLength = maximumLength;
        this.collapseSeparators = collapseSeparators;
        this.trimSeparators = trimSeparators;
    }

    public static SlugOptions defaults() {
        return new SlugOptions(SlugCharacterPolicy.ASCII, "-", 100, true, true);
    }

    public static Builder builder() {
        return new Builder();
    }

    public SlugCharacterPolicy characterPolicy() {
        return characterPolicy;
    }

    public String separator() {
        return separator;
    }

    public int maximumLength() {
        return maximumLength;
    }

    public boolean collapseSeparators() {
        return collapseSeparators;
    }

    public boolean trimSeparators() {
        return trimSeparators;
    }

    public static final class Builder {
        private SlugCharacterPolicy characterPolicy = SlugCharacterPolicy.ASCII;
        private String separator = "-";
        private int maximumLength = 100;
        private boolean collapseSeparators = true;
        private boolean trimSeparators = true;

        public Builder characterPolicy(SlugCharacterPolicy policy) {
            this.characterPolicy = Objects.requireNonNull(policy, "policy must not be null");
            return this;
        }

        public Builder separator(String separator) {
            this.separator = validateSeparator(separator);
            return this;
        }

        public Builder maximumLength(int maximumLength) {
            if (maximumLength <= 0) {
                throw new IllegalArgumentException("maximumLength must be positive: " + maximumLength);
            }
            this.maximumLength = maximumLength;
            return this;
        }

        public Builder collapseSeparators(boolean collapseSeparators) {
            this.collapseSeparators = collapseSeparators;
            return this;
        }

        public Builder trimSeparators(boolean trimSeparators) {
            this.trimSeparators = trimSeparators;
            return this;
        }

        public SlugOptions build() {
            return new SlugOptions(characterPolicy, separator, maximumLength, collapseSeparators, trimSeparators);
        }

        private static String validateSeparator(String separator) {
            Objects.requireNonNull(separator, "separator must not be null");
            if (separator.isEmpty()) {
                throw new IllegalArgumentException("separator must not be empty");
            }
            if (separator.length() != 1) {
                throw new IllegalArgumentException("separator must contain exactly one character");
            }
            char c = separator.charAt(0);
            if (Character.isLetterOrDigit(c)) {
                throw new IllegalArgumentException("separator must not be a letter or digit");
            }
            if (Character.isWhitespace(c)) {
                throw new IllegalArgumentException("separator must not be whitespace");
            }
            if (c == '/' || c == '\\') {
                throw new IllegalArgumentException("separator must not be a slash or backslash");
            }
            if (c == '?' || c == '#') {
                throw new IllegalArgumentException("separator must not be '?' or '#'");
            }
            return separator;
        }
    }
}
