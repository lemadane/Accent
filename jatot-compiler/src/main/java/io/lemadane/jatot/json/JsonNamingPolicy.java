package io.lemadane.jatot.json;

public enum JsonNamingPolicy {
    IDENTITY,
    CAMEL_CASE,
    SNAKE_CASE,
    KEBAB_CASE,
    PASCAL_CASE;

    public String translateName(String input) {
        if (input == null || input.isEmpty()) return input;
        switch (this) {
            case IDENTITY: return input;
            case CAMEL_CASE: return toCamel(input);
            case SNAKE_CASE: return toSnake(input);
            case KEBAB_CASE: return toKebab(input);
            case PASCAL_CASE: return toPascal(input);
            default: return input;
        }
    }

    private String toCamel(String s) {
        if (s.isEmpty()) return s;
        if (Character.isUpperCase(s.charAt(0))) {
            return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        }
        return s;
    }

    private String toPascal(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String toSnake(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String toKebab(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('-');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
