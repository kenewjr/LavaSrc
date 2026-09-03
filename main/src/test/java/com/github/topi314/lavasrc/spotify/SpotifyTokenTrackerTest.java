package com.github.topi314.lavasrc.spotify;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotifyTokenTrackerTest {
	private HttpServer server;
	private ExecutorService executor;

	@AfterEach
	void stopServer() {
		if (this.server != null) {
			this.server.stop(0);
		}
		if (this.executor != null) {
			this.executor.shutdownNow();
		}
	}

	@Test
	void customEndpointCanTakeLongerThanLavaplayerDefaultTimeoutAndCachesToken() throws Exception {
		var requests = new AtomicInteger();
		var expiry = Instant.now().plusSeconds(300).toEpochMilli();
		this.startServer(exchange -> {
			requests.incrementAndGet();
			try {
				Thread.sleep(3_250);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			writeJson(exchange, 200, tokenJson("slow-token", expiry));
		});

		var tracker = new SpotifyTokenTracker(null, null, null, null, this.endpoint());
		assertEquals("slow-token", tracker.getAnonymousAccessToken());
		assertEquals("slow-token", tracker.getAnonymousAccessToken());
		assertEquals(1, requests.get());
	}

	@Test
	void missingAppCredentialsUseAnonymousToken() throws Exception {
		var expiry = Instant.now().plusSeconds(300).toEpochMilli();
		this.startServer(exchange -> writeJson(exchange, 200, tokenJson("anonymous-token", expiry)));
		var tracker = new SpotifyTokenTracker(null, "  ", "", null, this.endpoint());

		assertFalse(tracker.hasValidCredentials());
		assertEquals("anonymous-token", tracker.getAccessToken(false));
	}

	@Test
	void completeAppCredentialsRemainAvailableForV1() {
		var tracker = new SpotifyTokenTracker(null, "client-id", "client-secret", null, null);

		assertTrue(tracker.hasValidCredentials());
	}

	@Test
	void invalidPayloadDoesNotBecomeCachedToken() throws Exception {
		this.startServer(exchange -> writeJson(exchange, 200, "{}"));
		var tracker = new SpotifyTokenTracker(null, null, null, null, this.endpoint());

		var error = assertThrows(IOException.class, tracker::getAnonymousAccessToken);
		assertTrue(error.getMessage().contains("missing a usable accessToken"));
	}

	@Test
	void refreshesThirtySecondsBeforeExpiry() throws Exception {
		var requests = new AtomicInteger();
		var expiry = Instant.now().plusSeconds(300).toEpochMilli();
		this.startServer(exchange -> {
			requests.incrementAndGet();
			writeJson(exchange, 200, tokenJson("fresh-token", expiry));
		});
		var tracker = new SpotifyTokenTracker(null, null, null, null, this.endpoint());
		setTokenCache(tracker, "old-token", Instant.now().plusSeconds(20));

		assertEquals("fresh-token", tracker.getAnonymousAccessToken());
		assertEquals(1, requests.get());
	}

	@Test
	void usesStillValidTokenWhenProactiveRefreshFails() throws Exception {
		this.startServer(exchange -> writeJson(exchange, 503, "{}"));
		var tracker = new SpotifyTokenTracker(null, null, null, null, this.endpoint());
		setTokenCache(tracker, "cached-token", Instant.now().plusSeconds(20));

		assertEquals("cached-token", tracker.getAnonymousAccessToken());
	}

	@Test
	void retriesOneTransientServerError() throws Exception {
		var requests = new AtomicInteger();
		var expiry = Instant.now().plusSeconds(300).toEpochMilli();
		this.startServer(exchange -> {
			if (requests.incrementAndGet() == 1) {
				writeJson(exchange, 500, "{}");
			} else {
				writeJson(exchange, 200, tokenJson("recovered-token", expiry));
			}
		});
		var tracker = new SpotifyTokenTracker(null, null, null, null, this.endpoint());

		assertEquals("recovered-token", tracker.getAnonymousAccessToken());
		assertEquals(2, requests.get());
	}

	@Test
	void customEndpointFailureIncludesStatusWithoutLeakingBody() throws Exception {
		var requests = new AtomicInteger();
		this.startServer(exchange -> {
			requests.incrementAndGet();
			writeJson(exchange, 503, "{\"secret\":\"must-not-leak\"}");
		});
		var tracker = new SpotifyTokenTracker(null, null, null, null, this.endpoint());

		var error = assertThrows(IOException.class, tracker::getAnonymousAccessToken);
		assertEquals("Custom Spotify token endpoint returned HTTP 503", error.getMessage());
		assertEquals(2, requests.get());
	}

	private static void setTokenCache(SpotifyTokenTracker tracker, String token, Instant expiry) throws ReflectiveOperationException {
		setField(tracker, "anonymousAccessToken", token);
		setField(tracker, "anonymousExpires", expiry);
	}

	private static void setField(SpotifyTokenTracker tracker, String name, Object value) throws ReflectiveOperationException {
		Field field = SpotifyTokenTracker.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(tracker, value);
	}

	private void startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/token", handler);
		this.executor = Executors.newCachedThreadPool();
		this.server.setExecutor(this.executor);
		this.server.start();
	}

	private String endpoint() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/token";
	}

	private static String tokenJson(String token, long expiry) {
		return "{\"accessToken\":\"" + token + "\",\"accessTokenExpirationTimestampMs\":" + expiry + "}";
	}

	private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, String json) throws IOException {
		var body = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, body.length);
		try (var output = exchange.getResponseBody()) {
			output.write(body);
		}
	}
}
