package com.github.topi314.lavasrc.spotify;

import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextFilter;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import org.apache.http.Header;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SpotifyPartnerApiClientTest {

	@Test
	void connectionResetOnGraphQLPostIsRetriedExactlyOnceAndRecovers() throws Exception {
		var attempts = new AtomicInteger();
		var httpManager = new FakeHttpInterfaceManager(request -> {
			if (attempts.incrementAndGet() == 1) {
				throw new SocketException("Connection reset");
			}
			return response(200, validTrackPayload("t1", "Track 1", "ISRC1"));
		});

		var tracker = createTrackerWithToken("valid-token");
		var client = new SpotifyPartnerApiClient(tracker, httpManager);

		var json = client.getTrack("spotify:track:t1");
		assertNotNull(json);
		assertEquals("Track 1", json.get("data").get("trackUnion").get("name").text());
		assertEquals(2, attempts.get());
	}

	@Test
	void persistentConnectionResetFailsSafelyAfterTwoAttempts() {
		var attempts = new AtomicInteger();
		var httpManager = new FakeHttpInterfaceManager(request -> {
			attempts.incrementAndGet();
			throw new SocketException("Connection reset");
		});

		var tracker = createTrackerWithToken("valid-token");
		var client = new SpotifyPartnerApiClient(tracker, httpManager);

		var error = assertThrows(FriendlyException.class, () -> client.getTrack("spotify:track:t1"));
		assertTrue(error.getMessage().contains("[NETWORK_ERROR]"));
		assertEquals(2, attempts.get());
	}

	@Test
	void accessDenied403IsNotRetried() {
		var attempts = new AtomicInteger();
		var httpManager = new FakeHttpInterfaceManager(request -> {
			attempts.incrementAndGet();
			return response(403, "{\"message\":\"forbidden\"}");
		});

		var tracker = createTrackerWithToken("valid-token");
		var client = new SpotifyPartnerApiClient(tracker, httpManager);

		var error = assertThrows(FriendlyException.class, () -> client.getTrack("spotify:track:t1"));
		assertTrue(error.getMessage().contains("[ACCESS_DENIED]"));
		assertEquals(1, attempts.get());
	}

	@Test
	void upstream502Or503RetriesOnce() {
		var attempts = new AtomicInteger();
		var httpManager = new FakeHttpInterfaceManager(request -> {
			if (attempts.incrementAndGet() == 1) {
				return response(503, "Service Unavailable");
			}
			return response(200, validTrackPayload("t1", "Track 1", "ISRC1"));
		});

		var tracker = createTrackerWithToken("valid-token");
		var client = new SpotifyPartnerApiClient(tracker, httpManager);

		var json = assertDoesNotThrow(() -> client.getTrack("spotify:track:t1"));
		assertEquals("Track 1", json.get("data").get("trackUnion").get("name").text());
		assertEquals(2, attempts.get());
	}

	@Test
	void token401InvalidatesTokenAndRetriesOnce() throws Exception {
		var attempts = new AtomicInteger();
		var tracker = createTrackerWithToken("old-token");

		var httpManager = new FakeHttpInterfaceManager(request -> {
			int current = attempts.incrementAndGet();
			if (current == 1) {
				// Old token was rejected
				return response(401, "{\"error\":\"invalid_token\"}");
			}
			return response(200, validTrackPayload("t1", "Track 1", "ISRC1"));
		});

		var client = new SpotifyPartnerApiClient(tracker, httpManager);
		// Simulate refresh on next call by preloading new token on tracker when old is cleared
		new Thread(() -> {
			while (tracker.getCachedAnonymousAccessToken() != null) {
				try {
					Thread.sleep(10);
				} catch (InterruptedException ignored) {
				}
			}
			setTrackerToken(tracker, "new-token", Instant.now().plusSeconds(300));
		}).start();

		var json = client.getTrack("spotify:track:t1");
		assertNotNull(json);
		assertEquals(2, attempts.get());
	}

	@Test
	void rateLimit429StoresCooldownAndFailsImmediately() {
		var attempts = new AtomicInteger();
		var httpManager = new FakeHttpInterfaceManager(request -> {
			attempts.incrementAndGet();
			return response(429, "Too Many Requests", new BasicHeader("Retry-After", "30"));
		});

		var tracker = createTrackerWithToken("valid-token");
		var client = new SpotifyPartnerApiClient(tracker, httpManager);

		var error = assertThrows(FriendlyException.class, () -> client.getTrack("spotify:track:t1"));
		assertTrue(error.getMessage().contains("[RATE_LIMITED]"));
		assertEquals(1, attempts.get());

		// Subsequent request within cooldown fails without touching network
		var error2 = assertThrows(FriendlyException.class, () -> client.getTrack("spotify:track:t1"));
		assertTrue(error2.getMessage().contains("[RATE_LIMITED]"));
		assertEquals(1, attempts.get());
	}

	@Test
	void graphQlPersistedQueryOutdatedFailsWithClearDiagnosis() {
		var httpManager = new FakeHttpInterfaceManager(request ->
			response(200, "{\"errors\":[{\"message\":\"PersistedQueryNotFound\",\"extensions\":{\"code\":\"PERSISTED_QUERY_NOT_FOUND\"}}]}"));

		var tracker = createTrackerWithToken("valid-token");
		var client = new SpotifyPartnerApiClient(tracker, httpManager);

		var error = assertThrows(FriendlyException.class, () -> client.getTrack("spotify:track:t1"));
		assertTrue(error.getMessage().contains("[QUERY_OUTDATED]"));
	}

	@Test
	void playlistPaginatesAcrossPagesWithoutSerialIsrcLookups() throws Exception {
		var requests = new ArrayList<String>();
		var httpManager = new FakeHttpInterfaceManager(request -> {
			var uri = request.getURI().toString();
			requests.add(uri);
			if (uri.contains("metadata/4/track/")) {
				fail("SpClient metadata endpoint must NOT be called during playlist loading!");
			}
			try {
				var body = extractRequestBody(request);
				if (body.contains("\"offset\":0")) {
					return response(200, playlistPagePayload(0, 100, 250));
				} else if (body.contains("\"offset\":100")) {
					return response(200, playlistPagePayload(100, 100, 250));
				} else if (body.contains("\"offset\":200")) {
					return response(200, playlistPagePayload(200, 50, 250));
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			return response(200, "{\"data\":{\"playlistV2\":{\"content\":{\"items\":[],\"totalCount\":250}}}}");
		});

		var tracker = createTrackerWithToken("valid-token");
		var client = new SpotifyPartnerApiClient(tracker, httpManager);
		var sourceManager = new SpotifySourceManager(new String[]{"ytsearch:%QUERY%"}, null, null, "US", unused -> null);

		AudioItem result = client.loadPartnerPlaylist("p1", false, 250, sourceManager);
		assertTrue(result instanceof AudioPlaylist);
		var playlist = (AudioPlaylist) result;
		assertEquals(250, playlist.getTracks().size());

		// Exactly 3 GraphQL page requests made, 0 spclient lookups
		assertEquals(3, requests.size());
	}

	@Test
	void emptyPlaylistResultReturnsValidEmptyCollection() throws Exception {
		var httpManager = new FakeHttpInterfaceManager(request ->
			response(200, "{\"data\":{\"playlistV2\":{\"name\":\"Empty List\",\"content\":{\"items\":[],\"totalCount\":0}}}}"));

		var tracker = createTrackerWithToken("valid-token");
		var client = new SpotifyPartnerApiClient(tracker, httpManager);
		var sourceManager = new SpotifySourceManager(new String[]{"ytsearch:%QUERY%"}, null, null, "US", unused -> null);

		AudioItem result = client.loadPartnerPlaylist("p-empty", false, 100, sourceManager);
		assertTrue(result instanceof AudioPlaylist);
		var playlist = (AudioPlaylist) result;
		assertEquals(0, playlist.getTracks().size());
	}

	private static SpotifyTokenTracker createTrackerWithToken(String token) {
		var tracker = new SpotifyTokenTracker(null, null, null, null, null);
		setTrackerToken(tracker, token, Instant.now().plusSeconds(300));
		return tracker;
	}

	private static void setTrackerToken(SpotifyTokenTracker tracker, String token, Instant expiry) {
		try {
			var field = SpotifyTokenTracker.class.getDeclaredField("anonymousToken");
			field.setAccessible(true);
			field.set(tracker, token == null ? null : Map.entry(token, expiry));
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static String validTrackPayload(String id, String name, String isrc) {
		return "{\"data\":{\"trackUnion\":{\"__typename\":\"Track\",\"id\":\"" + id + "\",\"name\":\"" + name
			+ "\",\"duration\":{\"totalMilliseconds\":210000},\"uri\":\"spotify:track:" + id
			+ "\",\"artists\":{\"items\":[{\"profile\":{\"name\":\"Artist 1\"}}]},"
			+ "\"albumOfTrack\":{\"name\":\"Album 1\"},\"externalIds\":{\"isrc\":\"" + isrc + "\"}}}}";
	}

	private static String playlistPagePayload(int offset, int count, int total) {
		var sb = new StringBuilder();
		sb.append("{\"data\":{\"playlistV2\":{\"name\":\"My Playlist\",\"ownerV2\":{\"data\":{\"name\":\"Owner\"}},");
		sb.append("\"content\":{\"totalCount\":").append(total).append(",\"items\":[");
		for (int i = 0; i < count; i++) {
			int trackNum = offset + i + 1;
			if (i > 0) sb.append(",");
			sb.append("{\"itemV2\":{\"data\":{\"__typename\":\"Track\",\"id\":\"t").append(trackNum)
				.append("\",\"name\":\"Track ").append(trackNum)
				.append("\",\"trackDuration\":{\"totalMilliseconds\":180000},\"artists\":{\"items\":[{\"profile\":{\"name\":\"Artist\"}}]},")
				.append("\"albumOfTrack\":{\"name\":\"Album\"},\"externalIds\":{\"isrc\":\"\"}}}}");
		}
		sb.append("]}}}}");
		return sb.toString();
	}

	private static String extractRequestBody(HttpUriRequest request) throws IOException {
		if (request instanceof HttpEntityEnclosingRequest) {
			var entity = ((HttpEntityEnclosingRequest) request).getEntity();
			if (entity != null) {
				return new String(entity.getContent().readAllBytes(), StandardCharsets.UTF_8);
			}
		}
		return "";
	}

	private static CloseableHttpResponse response(int code, String body, Header... headers) {
		var res = new FakeCloseableHttpResponse(code, body);
		for (var h : headers) {
			res.addHeader(h);
		}
		return res;
	}

	private static class FakeCloseableHttpResponse extends BasicHttpResponse implements CloseableHttpResponse {
		FakeCloseableHttpResponse(int code, String body) {
			super(new BasicStatusLine(HttpVersion.HTTP_1_1, code, code == 200 ? "OK" : "Status " + code));
			setEntity(new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON));
		}

		@Override
		public void close() {
		}
	}

	@FunctionalInterface
	private interface RequestHandler {
		HttpResponse handle(HttpUriRequest request) throws IOException;
	}

	private static class FakeHttpInterfaceManager implements HttpInterfaceManager {
		private final RequestHandler handler;

		FakeHttpInterfaceManager(RequestHandler handler) {
			this.handler = handler;
		}

		@Override
		public HttpInterface getInterface() {
			var context = HttpClientContext.create();
			return new HttpInterface(HttpClientTools.createSharedCookiesHttpBuilder().build(),
				context,
				false,
				null) {
				@Override
				public CloseableHttpResponse execute(HttpUriRequest request) throws IOException {
					var res = handler.handle(request);
					if (res instanceof CloseableHttpResponse) {
						return (CloseableHttpResponse) res;
					}
					return new FakeCloseableHttpResponse(res.getStatusLine().getStatusCode(), "");
				}

				@Override
				public void close() {
				}
			};
		}

		@Override
		public void setHttpContextFilter(HttpContextFilter filter) {
		}

		@Override
		public void configureRequests(Function<org.apache.http.client.config.RequestConfig, org.apache.http.client.config.RequestConfig> configurator) {
		}

		@Override
		public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
		}

		@Override
		public void close() {
		}
	}
}
