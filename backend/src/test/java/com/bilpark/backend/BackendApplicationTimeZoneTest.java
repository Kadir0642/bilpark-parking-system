package com.bilpark.backend;

import org.junit.jupiter.api.Test;

import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** main() sets the Turkey time zone before Spring starts (no longer a @PostConstruct). */
class BackendApplicationTimeZoneTest {

    @Test
    void useTurkeyTimeZoneSetsTheJvmDefault() {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            BackendApplication.useTurkeyTimeZone();
            assertEquals("Europe/Istanbul", TimeZone.getDefault().getID());
        } finally {
            TimeZone.setDefault(before);
        }
    }
}
