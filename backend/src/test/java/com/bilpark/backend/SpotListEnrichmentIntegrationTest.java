package com.bilpark.backend;

import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.model.ParkingStatus;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.repository.ParkingRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /spots and /filter return hasSubscription and accumulatedDebt (they used to be null outside check-in). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpotListEnrichmentIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ParkingRecordRepository recordRepository;

    private String admin() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-admin-pass\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\"\s*:\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException("no token in: " + body);
        return "Bearer " + m.group(1);
    }

    private void runaway(String plate, double fee, boolean paid) {
        ParkingRecord r = new ParkingRecord(plate, StreetLocation.ALI_RIZA_OZKAY, null, "Merkez", "Bilecik",
                LocalDateTime.now().minusDays(2));
        r.setExitTime(LocalDateTime.now().minusDays(1));
        r.setStatus(ParkingStatus.RUNAWAY);
        r.setFee(fee);
        r.setDebtPaid(paid);
        recordRepository.save(r);
    }

    private void checkIn(String auth, String plate) throws Exception {
        mvc.perform(post("/api/parking/check-in").header("Authorization", auth)
                        .param("plate", plate).param("street", "ALI_RIZA_OZKAY"))
                .andExpect(status().isOk());
    }

    @Test
    void spotListsCarrySubscriptionAndRunawayDebt() throws Exception {
        String auth = admin();

        // 34LST001: two unpaid runaway records (40 + 30) and one already paid (ignored), then the car comes back
        runaway("34LST001", 40.0, false);
        runaway("34LST001", 30.0, false);
        runaway("34LST001", 99.0, true);
        checkIn(auth, "34LST001");

        // subscriber
        mvc.perform(post("/api/subscription/create").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"licensePlate\":\"34LST002\",\"vehicleType\":\"SMALL\",\"ownerName\":\"T\",\"ownerPhone\":\"5\"}"))
                .andExpect(status().isOk());
        checkIn(auth, "34LST002");

        // plain car
        checkIn(auth, "34LST003");

        for (String url : new String[]{"/api/parking/spots", "/api/parking/filter?street=ALI_RIZA_OZKAY"}) {
            mvc.perform(get(url).header("Authorization", auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.currentPlate=='34LST001')].hasSubscription").value(hasItem(false)))
                    .andExpect(jsonPath("$[?(@.currentPlate=='34LST001')].accumulatedDebt").value(hasItem(70.0)))
                    .andExpect(jsonPath("$[?(@.currentPlate=='34LST002')].hasSubscription").value(hasItem(true)))
                    .andExpect(jsonPath("$[?(@.currentPlate=='34LST002')].accumulatedDebt").value(hasItem(0.0)))
                    .andExpect(jsonPath("$[?(@.currentPlate=='34LST003')].hasSubscription").value(hasItem(false)))
                    .andExpect(jsonPath("$[?(@.currentPlate=='34LST003')].accumulatedDebt").value(hasItem(0.0)));
        }
    }
}
