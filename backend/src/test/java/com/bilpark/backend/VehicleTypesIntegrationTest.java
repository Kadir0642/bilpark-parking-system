package com.bilpark.backend;

import com.bilpark.backend.fee.FeeEngine;
import com.bilpark.backend.fee.TariffRules;
import com.bilpark.backend.model.SyncEvent;
import com.bilpark.backend.model.SyncEventType;
import com.bilpark.backend.repository.SyncEventRepository;
import com.bilpark.backend.service.TariffService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vehicle types as data: public/admin lists, check-in rules, tariff form validation and the
 * MOTORCYCLE activation flow. Runs on its own H2 database because activation changes global state.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:vehicle_types_test;DB_CLOSE_DELAY=-1")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VehicleTypesIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TariffService tariffService;
    @Autowired private SyncEventRepository syncEvents;

    private String login(String user, String pass) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException(body);
        return "Bearer " + m.group(1);
    }

    private String admin() throws Exception { return login("admin", "test-admin-pass"); }
    private String officer() throws Exception { return login("tevfik", "test-officer-pass"); }

    private static String draft(String vehicleTypesJson) {
        return "{\"freeMinutes\":5,\"paidStart\":\"08:00\",\"paidEnd\":\"22:00\",\"note\":\"vt\""
                + (vehicleTypesJson == null ? "" : ",\"vehicleTypes\":" + vehicleTypesJson) + "}";
    }

    private static final String ALL_THREE = "["
            + "{\"code\":\"SMALL\",\"enabled\":true,\"firstHour\":25,\"extraHour\":15,\"subscriptionMonthly\":2500},"
            + "{\"code\":\"LARGE\",\"enabled\":true,\"firstHour\":50,\"extraHour\":30,\"subscriptionMonthly\":3000},"
            + "{\"code\":\"MOTORCYCLE\",\"enabled\":true,\"firstHour\":10,\"extraHour\":5,\"subscriptionMonthly\":800}]";

    private String createDraft(String admin, String json) throws Exception {
        String body = mvc.perform(post("/api/admin/tariffs").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
        if (!m.find()) throw new IllegalStateException(body);
        return m.group(1);
    }

    private void checkIn(String auth, String plate, String type, int expectedStatus) throws Exception {
        var req = post("/api/parking/check-in").header("Authorization", auth)
                .param("plate", plate).param("street", "TEVFIK_BEY").param("zoneId", "2");
        if (type != null) req.param("type", type);
        mvc.perform(req).andExpect(status().is(expectedStatus));
    }

    @Test
    @Order(1)
    void publicListHasOnlyActiveTypes() throws Exception {
        mvc.perform(get("/api/vehicle-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("SMALL"))
                .andExpect(jsonPath("$[0].displayName").value("Otomobil"))
                .andExpect(jsonPath("$[0].iconKey").value("car"))
                .andExpect(jsonPath("$[0].sortOrder").value(10))
                .andExpect(jsonPath("$[1].code").value("LARGE"));
    }

    @Test
    @Order(1)
    void adminListHasAllTypesWithActiveFlag() throws Exception {
        mvc.perform(get("/api/admin/vehicle-types")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/vehicle-types").header("Authorization", officer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/vehicle-types").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].code").value("MOTORCYCLE"))
                .andExpect(jsonPath("$[2].active").value(false))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    @Order(1)
    void currentTariffListsAllTypes() throws Exception {
        mvc.perform(get("/api/tariff/current"))
                .andExpect(jsonPath("$.vehicleTypes.length()").value(3))
                .andExpect(jsonPath("$.vehicleTypes[0].enabled").value(true))
                .andExpect(jsonPath("$.vehicleTypes[0].firstHour").value(25.0))
                .andExpect(jsonPath("$.vehicleTypes[2].code").value("MOTORCYCLE"))
                .andExpect(jsonPath("$.vehicleTypes[2].enabled").value(false))
                .andExpect(jsonPath("$.vehicleTypes[2].firstHour").value((Object) null));
    }

    @Test
    @Order(2)
    void checkInTypeRules() throws Exception {
        String admin = admin();
        checkIn(admin, "34VT001", "MOTORCYCLE", 400);
        mvc.perform(post("/api/parking/check-in").header("Authorization", admin)
                        .param("plate", "34VT001").param("street", "TEVFIK_BEY").param("zoneId", "2")
                        .param("type", "MOTORCYCLE"))
                .andExpect(jsonPath("$.code").value("VEHICLE_TYPE_INACTIVE"))
                .andExpect(jsonPath("$.error").value(
                        "Motosiklet şu an tarifede etkin değil. Yönetici tarife sayfasından etkinleştirebilir."));
        mvc.perform(post("/api/parking/check-in").header("Authorization", admin)
                        .param("plate", "34VT001").param("street", "TEVFIK_BEY").param("zoneId", "2")
                        .param("type", "BUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_VEHICLE_TYPE"))
                .andExpect(jsonPath("$.error").value("Bilinmeyen araç tipi: BUS"));

        // lowercase works, omitted type means SMALL
        mvc.perform(post("/api/parking/check-in").header("Authorization", admin)
                        .param("plate", "34VT002").param("street", "TEVFIK_BEY").param("zoneId", "2")
                        .param("type", "small"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentType").value("SMALL"));
        mvc.perform(post("/api/parking/check-in").header("Authorization", admin)
                        .param("plate", "34VT003").param("street", "TEVFIK_BEY").param("zoneId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentType").value("SMALL"));
    }

    @Test
    @Order(2)
    void incomeByTypeDetails() throws Exception {
        String admin = admin();
        checkIn(admin, "34VT004", "LARGE", 200);
        mvc.perform(post("/api/parking/check-out").header("Authorization", admin).param("plate", "34VT004"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/parking/bi/income-by-type/details").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='LARGE')].displayName").value(Matchers.contains("Kamyonet")))
                .andExpect(jsonPath("$[?(@.code=='LARGE')].records").value(Matchers.contains(1)))
                .andExpect(jsonPath("$[?(@.code=='LARGE')].active").value(Matchers.contains(true)))
                .andExpect(jsonPath("$[?(@.code=='MOTORCYCLE')]").isEmpty());
        mvc.perform(get("/api/parking/bi/income-by-type/details")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/parking/bi/income-by-type").header("Authorization", admin))
                .andExpect(jsonPath("$.Otomobil").exists())
                .andExpect(jsonPath("$.Kamyonet").exists())
                .andExpect(jsonPath("$.Motosiklet").doesNotExist());
    }

    @Test
    @Order(2)
    void draftValidation() throws Exception {
        String admin = admin();
        // enabled type without a price
        mvc.perform(post("/api/admin/tariffs").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draft("[{\"code\":\"MOTORCYCLE\",\"enabled\":true,\"extraHour\":5,\"subscriptionMonthly\":800}]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TARIFF"))
                .andExpect(jsonPath("$.error").value("Motosiklet ilk saat ücreti sıfırdan büyük olmalıdır."));
        // unknown code
        mvc.perform(post("/api/admin/tariffs").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draft("[{\"code\":\"BUS\",\"enabled\":true,\"firstHour\":5,\"extraHour\":5,\"subscriptionMonthly\":5}]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TARIFF"));
        // zero enabled types
        mvc.perform(post("/api/admin/tariffs").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draft("[{\"code\":\"SMALL\",\"enabled\":false},{\"code\":\"LARGE\",\"enabled\":false}]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TARIFF"))
                .andExpect(jsonPath("$.error").value("En az bir araç tipi etkin olmalıdır."));
    }

    /** Draft with MOTORCYCLE -> preview -> schedule -> activation -> sync event -> check-in -> fee -> reports. */
    @Test
    @Order(10)
    void motorcycleActivationFlow() throws Exception {
        String admin = admin();
        String id = createDraft(admin, draft(ALL_THREE));

        mvc.perform(post("/api/admin/tariffs/" + id + "/preview").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(30))
                .andExpect(jsonPath("$.rows[?(@.vehicleType=='MOTORCYCLE')].displayName",
                        Matchers.everyItem(Matchers.is("Motosiklet"))))
                .andExpect(jsonPath("$.rows[20].vehicleType").value("MOTORCYCLE"))
                .andExpect(jsonPath("$.rows[20].current").value((Object) null))
                .andExpect(jsonPath("$.rows[20].proposed.fee").exists())
                .andExpect(jsonPath("$.typeChanges.length()").value(1))
                .andExpect(jsonPath("$.typeChanges[0].code").value("MOTORCYCLE"))
                .andExpect(jsonPath("$.typeChanges[0].currentEnabled").value(false))
                .andExpect(jsonPath("$.typeChanges[0].proposedEnabled").value(true));

        long eventsBefore = syncEvents.count();
        String effective = LocalDateTime.now().plusSeconds(2).withNano(0).toString();
        mvc.perform(post("/api/admin/tariffs/" + id + "/schedule").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"" + effective + "\",\"password\":\"test-admin-pass\"}"))
                .andExpect(status().isOk());
        // not active yet
        mvc.perform(get("/api/vehicle-types")).andExpect(jsonPath("$.length()").value(2));

        Thread.sleep(2600);
        tariffService.activateDue(); // the timer may already have done it; the call is idempotent

        mvc.perform(get("/api/vehicle-types"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].code").value("MOTORCYCLE"))
                .andExpect(jsonPath("$[2].iconKey").value("motorcycle"));
        mvc.perform(get("/api/tariff/current"))
                .andExpect(jsonPath("$.vehicleTypes[2].enabled").value(true))
                .andExpect(jsonPath("$.vehicleTypes[2].firstHour").value(10.0));

        long motoEvents = syncEvents.findAll().stream()
                .filter((SyncEvent e) -> e.getType() == SyncEventType.VEHICLE_TYPES_UPDATED).count();
        assertEquals(1, motoEvents);
        assertTrue(syncEvents.count() > eventsBefore);

        // check-in and fee with the motorcycle prices
        checkIn(admin, "34MT001", "MOTORCYCLE", 200);
        mvc.perform(get("/api/parking/fee-preview").header("Authorization", admin).param("plate", "34MT001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleType").value("MOTORCYCLE"));
        TariffRules rules = TariffRules.from(tariffService.current());
        assertEquals(10.0, FeeEngine.priceFor(rules, "MOTORCYCLE", 60));
        assertEquals(15.0, FeeEngine.priceFor(rules, "MOTORCYCLE", 61));

        mvc.perform(post("/api/parking/check-out").header("Authorization", admin).param("plate", "34MT001"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/parking/bi/income-by-type/details").header("Authorization", admin))
                .andExpect(jsonPath("$[?(@.code=='MOTORCYCLE')].records").value(Matchers.contains(1)));
        LocalDate today = LocalDate.now();
        mvc.perform(get("/api/admin/reports/period/summary?from=" + today + "&to=" + today)
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byVehicleType[?(@.code=='MOTORCYCLE')].transactions")
                        .value(Matchers.contains(1)));

        // a legacy request (only small*/large* fields) keeps MOTORCYCLE enabled
        String legacy = "{\"smallFirstHour\":30,\"smallExtraHour\":20,\"largeFirstHour\":60,\"largeExtraHour\":40,"
                + "\"freeMinutes\":10,\"paidStart\":\"09:00\",\"paidEnd\":\"20:00\","
                + "\"subscriptionSmallMonthly\":2800,\"subscriptionLargeMonthly\":3300}";
        mvc.perform(post("/api/admin/tariffs").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(legacy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.small.firstHour").value(30.0))
                .andExpect(jsonPath("$.vehicleTypes[2].enabled").value(true))
                .andExpect(jsonPath("$.vehicleTypes[2].firstHour").value(10.0));
    }
}
