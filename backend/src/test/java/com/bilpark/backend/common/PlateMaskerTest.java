package com.bilpark.backend.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertEquals("**", PlateMasker.maskPlate("CD"));
        assertEquals("3**B", PlateMasker.maskPlate("34AB")); // 4-6 characters: only the first and the last stay
        assertNull(PlateMasker.maskPlate(null));
    }

    @Test
    void maskPlate_foreignPlates_neverShownInFull() {
        assertEquals("C**2", PlateMasker.maskPlate("CD12"));
        assertEquals("C***4", PlateMasker.maskPlate("CD234"));
        assertEquals("1****3", PlateMasker.maskPlate("12ABC3"));     // Hollanda
        assertEquals("MA***34", PlateMasker.maskPlate("MAB1234"));   // Almanya
        assertEquals("AB********56", PlateMasker.maskPlate("ABCDEF123456"));
        for (String p : new String[]{"AB", "CD1", "CD12", "TAXI1", "12ABC3", "MAB1234", "ABCDEF123456"}) {
            assertNotEquals(p, PlateMasker.maskPlate(p), p);
        }
    }

    @Test
    void maskText_masksForeignPlateTokens() {
        assertEquals("MA***34 giriş: Saha 1", PlateMasker.maskText("MAB1234 giriş: Saha 1"));
        assertEquals("iptal: 1****3 yanlış", PlateMasker.maskText("iptal: 12ABC3 yanlış"));
        assertEquals("q=C***4", PlateMasker.maskText("q=CD234"));
        assertEquals("plaka \"MA***34\".", PlateMasker.maskText("plaka \"MAB1234\"."));
        // not plates: words, amounts, times, ids glued with other characters
        assertEquals("15.00 TL (CASH)", PlateMasker.maskText("15.00 TL (CASH)"));
        assertEquals("2026-11-05T10:15:00Z", PlateMasker.maskText("2026-11-05T10:15:00Z"));
        assertEquals("Saha 3 mesai", PlateMasker.maskText("Saha 3 mesai"));
        assertEquals("a1b2c3d4-e5f6", PlateMasker.maskText("a1b2c3d4-e5f6"));
    }

    @Test
    void maskLeadingPlate_masksLetterOnlyForeignPlateToo() {
        assertEquals("T**I giriş: Saha 1", PlateMasker.maskLeadingPlate("TAXI giriş: Saha 1"));
        assertEquals("MA***34 çıkış: 15.00 TL (CASH)", PlateMasker.maskLeadingPlate("MAB1234 çıkış: 15.00 TL (CASH)"));
        assertEquals("34****23 kaçak: 20.00 TL borç", PlateMasker.maskLeadingPlate("34ABC123 kaçak: 20.00 TL borç"));
        assertEquals("C**2", PlateMasker.maskLeadingPlate("CD12"));
        assertNull(PlateMasker.maskLeadingPlate(null));
    }

    @Test
    void maskJson_plateKeysMaskedWhateverTheShape() {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) PlateMasker.maskJson(Map.of("plate", "TAXI", "foreignPlate", true));
        assertEquals("T**I", m.get("plate"));
        assertEquals(true, m.get("foreignPlate"));
    }

    @Test
    void maskJson_countryCodeIsNotPersonalData_staysReadable_plateStillMasked() {
        // V11: the country code (D, NL, BIH) is not personal data and is never masked; the plate next to it is
        for (String code : List.of("D", "NL", "GB", "BIH")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) PlateMasker.maskJson(
                    Map.of("plate", "MAB1234", "foreignPlate", true, "foreignCountryCode", code));
            assertEquals("MA***34", m.get("plate"));
            assertEquals(code, m.get("foreignCountryCode"));
        }
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
        // il kodu 99 yok, TR plakası değil; ama yabancı plaka biçimine uyduğu için yine maskelenir (V10)
        assertEquals("99****23 geçersiz il", PlateMasker.maskText("99ABC123 geçersiz il"));
        assertEquals("2026-11-05T10:15:00", PlateMasker.maskText("2026-11-05T10:15:00"));
        assertEquals("x34ABC123y", PlateMasker.maskText("x34ABC123y")); // glued to other letters: not a plate
        // an upper-case A-Z0-9 word of 2-12 characters may be a foreign plate, so it is masked (V10)
        assertEquals("X3******3Y", PlateMasker.maskText("X34ABC123Y"));
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
