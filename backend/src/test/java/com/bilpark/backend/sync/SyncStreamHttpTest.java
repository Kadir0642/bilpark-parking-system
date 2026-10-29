package com.bilpark.backend.sync;

import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.SyncEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP: HELLO frame, then a published zone event arrives as an SSE frame. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SyncStreamHttpTest {

    @LocalServerPort int port;
    @Autowired ChangePublisher publisher;
    @Autowired JsonMapper mapper;

    @Test
    void streamDeliversHelloAndPublishedEvent() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String loginBody = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"tevfik\",\"password\":\"test-officer-pass\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString()).body();
        String token = mapper.readTree(loginBody).get("token").asString();

        HttpResponse<InputStream> resp = client.sendAsync(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events"))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "text/event-stream")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()).get(10, TimeUnit.SECONDS);
        assertEquals(200, resp.statusCode());

        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = br.readLine()) != null) lines.add(l);
            } catch (Exception ignored) {
                // stream closed by test
            }
        });
        reader.setDaemon(true);
        reader.start();

        try {
            awaitLine(lines, "event:HELLO");
            String hello = awaitPrefix(lines, "data:");
            assertTrue(hello.contains("\"role\":\"OFFICER\""), hello);

            publisher.zoneEvent(SyncEventType.VEHICLE_ENTERED, 1L, StreetLocation.TEVFIK_BEY, Map.of("plate", "34SSE1"));
            awaitLine(lines, "event:VEHICLE_ENTERED");
            String data = awaitPrefix(lines, "data:");
            assertTrue(data.contains("34SSE1") && data.contains("\"zoneId\":1"), data);
        } finally {
            resp.body().close();
        }
    }

    private static void awaitLine(BlockingQueue<String> q, String wanted) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            String l = q.poll(500, TimeUnit.MILLISECONDS);
            if (l != null && l.replace(" ", "").equals(wanted)) return;
        }
        fail("timeout waiting for " + wanted);
    }

    private static String awaitPrefix(BlockingQueue<String> q, String prefix) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            String l = q.poll(500, TimeUnit.MILLISECONDS);
            if (l != null && l.startsWith(prefix)) return l;
        }
        return fail("timeout waiting for " + prefix);
    }
}
