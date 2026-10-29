package com.bilpark.backend.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlateMaskerTest {

    @Test
    void maskPlate_keepsFirstAndLastTwo_separatorsStay() {
        assertEquals("34 *** *23", PlateMasker.maskPlate("34 ABC 123"));
        assertEquals("34****23", PlateMasker.maskPlate("34ABC123"));
        assertEquals("06 ** **45", PlateMasker.maskPlate("06 AB 1245"));
        assertEquals("34-***-*23", PlateMasker.maskPlate("34-ABC-123"));
    }

    @Test
    void maskPlate_shortValuesFullyMasked_nullStaysNull() {
        assertEquals("***", PlateMasker.maskPlate("ABC"));
        assertEquals("*", PlateMasker.maskPlate("A"));
        assertEquals("", PlateMasker.maskPlate(""));
        assertEquals("34AB", PlateMasker.maskPlate("34AB")); // 4 characters: first 2 + last 2 are all of it
        assertNull(PlateMasker.maskPlate(null));
    }

    @Test
    void maskText_masksEveryPlateShapedPart() {
        assertEquals("34****23 giriş: Saha 1", PlateMasker.maskText("34ABC123 giriş: Saha 1"));
        assertEquals("a 34 *** *23 b 06 ** **45 c",
                PlateMasker.maskText("a 34 ABC 123 b 06 AB 1245 c"));
        assertEquals("34****23 / 06****45", PlateMasker.maskText("34abc123 / 06A12345"));
    }

    @Test
    void maskText_leavesNonPlatesAlone() {
        assertEquals("Saha 3 mesai açıldı", PlateMasker.maskText("Saha 3 mesai açıldı"));
        assertEquals("tutar 1500.50 TL", PlateMasker.maskText("tutar 1500.50 TL"));
        assertEquals("99ABC123 geçersiz il", PlateMasker.maskText("99ABC123 geçersiz il")); // il kodu 99 yok
        assertEquals("2026-11-05T10:15:00", PlateMasker.maskText("2026-11-05T10:15:00"));
        assertEquals("X34ABC123Y", PlateMasker.maskText("X34ABC123Y")); // glued to other letters: not a plate
        assertEquals("", PlateMasker.maskText(""));
        assertNull(PlateMasker.maskText(null));
    }

    @Test
    void maskJson_walksMapsAndLists_hidesPersonalData() {
        Object masked = PlateMasker.maskJson(Map.of(
                "plate", "34ABC123",
                "list", List.of("06 AB 1245", 5),
                "fullName", "Ali Veli",
                "zoneId", 3));
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) masked;
        assertEquals("34****23", m.get("plate"));
        assertEquals(List.of("06 ** **45", 5), m.get("list"));
        assertEquals("***", m.get("fullName"));
        assertEquals(3, m.get("zoneId"));
    }
}
