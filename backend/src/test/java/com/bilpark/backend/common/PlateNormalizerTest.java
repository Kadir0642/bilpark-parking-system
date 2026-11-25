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
    void foreignRule_acceptsNonTurkishShapes() {
        assertEquals("MAB1234", PlateNormalizer.normalize("M AB 1234", true)); // Almanya
        assertEquals("12ABC3", PlateNormalizer.normalize("12-ABC-3", true));   // Hollanda
        assertEquals("CD1234", PlateNormalizer.normalize("cd 1234", true));    // diplomatik
        assertEquals("AB", PlateNormalizer.normalize("A-B", true));            // en kısa: 2 karakter
        assertEquals("ABCDEF123456", PlateNormalizer.normalize("ABCDEF 123456", true)); // en uzun: 12
        assertEquals("34ABC123", PlateNormalizer.normalize("34 ABC 123", true)); // TR biçimi de geçer
        assertEquals("MAB1234", PlateNormalizer.normalizeAny("m ab-1234"));
    }

    @Test
    void foreignRule_rejectsBadCharactersAndLength() {
        for (String bad : new String[]{null, "", " ", "A", "ABCDEFG1234567", "M.AB 1234", "AB/12", "ÇAB123", "AB_12"}) {
            ApiException ex = assertThrows(ApiException.class, () -> PlateNormalizer.normalize(bad, true), String.valueOf(bad));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.INVALID_PLATE, ex.getCode());
            assertTrue(ex.getMessage().contains("yabancı"), ex.getMessage());
        }
        // bayrak yoksa yabancı plaka TR kuralına takılır
        assertThrows(ApiException.class, () -> PlateNormalizer.normalize("M AB 1234"));
        assertThrows(ApiException.class, () -> PlateNormalizer.normalize("M AB 1234", false));
    }

    @Test
    void cleanDoesNotValidate() {
        assertEquals("", PlateNormalizer.clean(null));
        assertEquals("34AB", PlateNormalizer.clean(" 34-ab "));
        assertTrue(PlateNormalizer.isValid("34ABC123"));
        assertFalse(PlateNormalizer.isValid("34abc123"));
    }
}
