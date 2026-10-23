package com.bilpark.backend;

import com.bilpark.backend.common.PlateNormalizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The JVM default locale is Turkish on developer machines and may be on servers. Under tr "I".toLowerCase() is "ı",
 * which broke Hibernate's "MIN" function lookup (/api/sync, /api/events -> 500) and can turn "i" into "İ" in plates.
 * The context is started while the default locale is tr (the property below forces a fresh, uncached context).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "bilpark.test.locale=tr")
class TurkishLocaleIntegrationTest {

    private static final Locale TR = Locale.forLanguageTag("tr-TR");
    private static Locale original;

    @Autowired
    private MockMvc mvc;

    @BeforeAll
    static void turkishDefaultLocale() {
        original = Locale.getDefault();
        Locale.setDefault(TR);
    }

    @AfterAll
    static void restoreLocale() {
        Locale.setDefault(original);
    }

    private String admin() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-admin-pass\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException("no token in: " + body);
        return "Bearer " + m.group(1);
    }

    @Test
    void syncAndEventsWorkUnderTurkishLocale() throws Exception {
        assertEquals(TR, Locale.getDefault());
        String auth = admin();
        // creates a sync event, so the catch-up path below has rows to compare against
        mvc.perform(post("/api/parking/check-in").header("Authorization", auth)
                        .param("plate", "34TRL001").param("street", "CUMHURIYET"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/sync").param("since", "0").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resetRequired").value(false));

        mvc.perform(get("/api/events").param("since", "0").header("Authorization", auth))
                .andExpect(request().asyncStarted());
    }

    @Test
    void plateWithLowercaseIIsNormalizedToAsciiI() throws Exception {
        assertEquals("34ABI123", PlateNormalizer.normalize("34 abi 123"));

        mvc.perform(post("/api/parking/check-in").header("Authorization", admin())
                        .param("plate", "34 tri 42").param("street", "CUMHURIYET").param("type", "large"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPlate").value("34TRI42"))
                .andExpect(jsonPath("$.currentType").value("LARGE"));
    }

    @Test
    void mainForcesRootLocale() {
        Locale.setDefault(TR);
        try {
            BackendApplication.useRootLocale();
            assertEquals(Locale.ROOT, Locale.getDefault());
            assertEquals("i", "I".toLowerCase());
        } finally {
            Locale.setDefault(TR);
        }
    }
}
