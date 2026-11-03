package com.soscreate.universalmodsync;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetadataJsonParserTest {
    @Test
    void parsesObjectsArraysNumbersBooleansNullAndEscapedStrings() throws Exception {
        Map<String, Object> parsed = MetadataJsonParser.parseObject("""
                {
                  "id": "mod\\u005fname",
                  "quoted": "a \\"quoted\\" value",
                  "array": [1, true, null, {"key": "value"}],
                  "nothing": null
                }
                """);

        assertEquals("mod_name", parsed.get("id"));
        assertEquals("a \"quoted\" value", parsed.get("quoted"));
        assertTrue(parsed.get("array") instanceof List<?>);
        assertTrue(parsed.containsKey("nothing"));
        assertNull(parsed.get("nothing"));
    }

    @Test
    void rejectsDuplicateKeysIncludingNullValues() {
        assertThrows(IOException.class, () -> MetadataJsonParser.parseObject(
                "{\"id\":null,\"id\":\"duplicate\"}"));
    }

    @Test
    void rejectsTrailingCommasAndTrailingGarbage() {
        assertThrows(IOException.class, () -> MetadataJsonParser.parseObject("{\"id\":\"x\",}"));
        assertThrows(IOException.class, () -> MetadataJsonParser.parseObject("{\"id\":\"x\"} trailing"));
    }

    @Test
    void rejectsUnexpectedRootTypesAndExcessiveNesting() {
        assertThrows(IOException.class, () -> MetadataJsonParser.parseObject("[1,2,3]"));
        String nested = "{\"a\":".repeat(70) + "\"x\"" + "}".repeat(70);
        assertThrows(IOException.class, () -> MetadataJsonParser.parseObject(nested));
    }
}
