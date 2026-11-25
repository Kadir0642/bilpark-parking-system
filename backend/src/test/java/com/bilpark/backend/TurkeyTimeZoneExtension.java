package com.bilpark.backend;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.TimeZone;

/**
 * Tests start the application without {@link BackendApplication#main}, so they get the Turkey time zone the
 * same way here, before every test class (registered for every test through
 * META-INF/services/org.junit.jupiter.api.extension.Extension and junit-platform.properties). Without it a
 * UTC machine (CI) would run the tests in UTC while production runs in Europe/Istanbul.
 */
public class TurkeyTimeZoneExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        if (!"Europe/Istanbul".equals(TimeZone.getDefault().getID())) {
            BackendApplication.useTurkeyTimeZone();
        }
    }
}
