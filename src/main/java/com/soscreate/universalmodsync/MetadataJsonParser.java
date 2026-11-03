package com.soscreate.universalmodsync;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict JSON reader used only for untrusted mod metadata. */
final class MetadataJsonParser {
    private MetadataJsonParser() { }

    static Map<String, Object> parseObject(String source) throws IOException {
        Parser parser = new Parser(source);
        Object value = parser.parseValue(0);
        parser.skipWhitespace();
        if (parser.position != source.length()) {
            throw parser.error("Unexpected content after the JSON value");
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw parser.error("The JSON root must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) map;
        return result;
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 64;
        private final String source;
        private int position;

        private Parser(String source) {
            this.source = source == null ? "" : source;
        }

        private Object parseValue(int depth) throws IOException {
            if (depth > MAX_DEPTH) throw error("JSON nesting is too deep");
            skipWhitespace();
            if (position >= source.length()) throw error("Unexpected end of JSON");
            return switch (source.charAt(position)) {
                case '{' -> parseObject(depth + 1);
                case '[' -> parseArray(depth + 1);
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject(int depth) throws IOException {
            position++;
            skipWhitespace();
            Map<String, Object> result = new LinkedHashMap<>();
            if (consume('}')) return result;
            while (true) {
                skipWhitespace();
                if (position >= source.length() || source.charAt(position) != '"') {
                    throw error("Expected an object key");
                }
                String key = parseString();
                skipWhitespace();
                require(':');
                Object value = parseValue(depth);
                if (result.putIfAbsent(key, value) != null || (value == null && result.containsKey(key)
                        && result.get(key) != null)) {
                    throw error("Duplicate object key: " + key);
                }
                skipWhitespace();
                if (consume('}')) return result;
                require(',');
            }
        }

        private List<Object> parseArray(int depth) throws IOException {
            position++;
            skipWhitespace();
            List<Object> result = new ArrayList<>();
            if (consume(']')) return result;
            while (true) {
                result.add(parseValue(depth));
                skipWhitespace();
                if (consume(']')) return result;
                require(',');
            }
        }

        private String parseString() throws IOException {
            require('"');
            StringBuilder result = new StringBuilder();
            while (position < source.length()) {
                char current = source.charAt(position++);
                if (current == '"') return result.toString();
                if (current < 0x20) throw error("Unescaped control character in string");
                if (current != '\\\\') {
                    result.append(current);
                    continue;
                }
                if (position >= source.length()) throw error("Incomplete escape sequence");
                char escaped = source.charAt(position++);
                switch (escaped) {
                    case '"', '\\\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\\b');
                    case 'f' -> result.append('\\f');
                    case 'n' -> result.append('\\n');
                    case 'r' -> result.append('\\r');
                    case 't' -> result.append('\\t');
                    case 'u' -> result.append(parseUnicodeEscape());
                    default -> throw error("Invalid escape sequence");
                }
            }
            throw error("Unterminated string");
        }

        private char parseUnicodeEscape() throws IOException {
            if (position + 4 > source.length()) throw error("Incomplete Unicode escape");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(source.charAt(position++), 16);
                if (digit < 0) throw error("Invalid Unicode escape");
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Object parseNumber() throws IOException {
            int start = position;
            consume('-');
            if (!consume('0')) {
                if (!hasDigit()) throw error("Invalid number");
                while (hasDigit()) position++;
            }
            if (consume('.')) {
                if (!hasDigit()) throw error("Invalid fraction");
                while (hasDigit()) position++;
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) consume('-');
                if (!hasDigit()) throw error("Invalid exponent");
                while (hasDigit()) position++;
            }
            try {
                return new BigDecimal(source.substring(start, position));
            } catch (NumberFormatException exception) {
                throw error("Invalid number");
            }
        }

        private Object parseLiteral(String literal, Object value) throws IOException {
            if (!source.startsWith(literal, position)) throw error("Invalid literal");
            position += literal.length();
            return value;
        }

        private boolean hasDigit() {
            return position < source.length() && source.charAt(position) >= '0'
                    && source.charAt(position) <= '9';
        }

        private boolean consume(char expected) {
            if (position < source.length() && source.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void require(char expected) throws IOException {
            if (!consume(expected)) throw error("Expected '" + expected + "'");
        }

        private void skipWhitespace() {
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) position++;
        }

        private IOException error(String message) {
            return new IOException(message + " at character " + position);
        }
    }
}
