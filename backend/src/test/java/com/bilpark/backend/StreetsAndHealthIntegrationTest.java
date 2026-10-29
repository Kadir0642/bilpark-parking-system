package com.bilpark.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StreetsAndHealthIntegrationTest {

    @Autowired MockMvc mvc;
    @MockitoSpyBean DataSource dataSource;

    @Test
    void healthIsAnonymousAndNeverTouchesTheDatabase() throws Exception {
        clearInvocations(dataSource);
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.time").isNotEmpty());
        mvc.perform(head("/api/health")).andExpect(status().isOk());
        verify(dataSource, never()).getConnection();
        verify(dataSource, never()).getConnection(anyString(), anyString());
    }

    @Test
    void streetsArePublicWithZones() throws Exception {
        mvc.perform(get("/api/streets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("TEVFIK_BEY"))
                .andExpect(jsonPath("$[0].displayName").value("Tevfik Bey Caddesi"))
                .andExpect(jsonPath("$[0].sortOrder").value(1))
                .andExpect(jsonPath("$[0].zones.length()").value(3))
                .andExpect(jsonPath("$[0].zones[0].zoneNumber").value(1))
                .andExpect(jsonPath("$[1].code").value("ALI_RIZA_OZKAY"))
                .andExpect(jsonPath("$[1].zones.length()").value(2))
                .andExpect(jsonPath("$[2].code").value("CUMHURIYET"))
                .andExpect(jsonPath("$[2].zones.length()").value(1));
    }
}
