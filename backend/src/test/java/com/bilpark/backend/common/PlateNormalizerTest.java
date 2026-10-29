package com.bilpark.backend.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

class PlateNormalizerTest {

    @Test
    void validPlates() {
        assertEquals("34ABC123", PlateNormalizer.normalize("34 abc-123"));
        assertEquals("34OZ2001", PlateNormalizer.normalize("34oz2001"));
        assertEquals("06A12345", PlateNormalizer.normalize(" 06 a 12345 "));
        assertEquals("81AB1234", PlateNormalizer.normalize("81-AB-1234"));
        assertEquals("01ABC12", PlateNormalizer.normalize("01 ABC 12"));
    }

    @Test
    void invalidPlates() {
        for (String bad : new String[]{null, "", "   ", "ABC123", "00ABC123", "82ABC123", "34A123", "34ABCD123",
                "34ABC1", "34ABC1234", "34 ABC 12!", "3ABC123"}) {
            ApiException ex = assertThrows(ApiException.class, () -> PlateNormalizer.normalize(bad), String.valueOf(bad));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.INVALID_PLATE, ex.getCode());
        }
    }

    @Test
    void lookupCleansWithoutValidating() {
        // legacy active rows created before validation must stay closable
        assertEquals("TEST1", PlateNormalizer.forLookup(" test-1 "));
        ApiException ex = assertThrows(ApiException.class, () -> PlateNormalizer.forLookup("  "));
        assertEquals(ErrorCodes.INVALID_PLATE, ex.getCode());
    }

    @Test
    void turkishILettersMapToI() {
        assertEquals("34IIX123", PlateNormalizer.normalize("34 ıiX 123"));
        assertEquals("34IIX123", PlateNormalizer.normalize("34 İIx 123"));
        assertEquals("34IX123", PlateNormalizer.clean("34 ıx 123"));
    }

    @Test
    void cleanDoesNotValidate() {
        assertEquals("", PlateNormalizer.clean(null));
        assertEquals("34AB", PlateNormalizer.clean(" 34-ab "));
        assertTrue(PlateNormalizer.isValid("34ABC123"));
        assertFalse(PlateNormalizer.isValid("34abc123"));
    }
}
