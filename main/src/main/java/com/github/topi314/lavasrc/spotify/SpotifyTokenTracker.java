package com.github.topi314.lavasrc.spotify;

import com.github.topi314.lavasrc.LavaSrcTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public class SpotifyTokenTracker {
	private static final Logger log = LoggerFactory.getLogger(SpotifyTokenTracker.class);

	private static final Pattern SECRET_PATTERN = Pattern.compile("\"secret\":\\[(\\d+(?:,\\d+)+)]");
	private static final long TOKEN_REFRESH_MARGIN_SECONDS = 30;
	private static final int CUSTOM_TOKEN_CONNECT_TIMEOUT_MS = 5_000;
	private static final int CUSTOM_TOKEN_READ_TIMEOUT_MS = 30_000;
	private static final RequestConfig CUSTOM_TOKEN_REQUEST_CONFIG = RequestConfig.custom()
		.setConnectTimeout(CUSTOM_TOKEN_CONNECT_TIMEOUT_MS)
		.setConnectionRequestTimeout(CUSTOM_TOKEN_CONNECT_TIMEOUT_MS)
		.setSocketTimeout(CUSTOM_TOKEN_READ_TIMEOUT_MS)
		.build();

	private final SpotifySourceManager sourceManager;

	private String clientId;
	private String clientSecret;
	private String accessToken;
	private Instant expires;

	private String customTokenEndpoint;
	private volatile Map.Entry<String, Instant> anonymousToken;
	private Instant anonymousRefreshAfter = Instant.EPOCH;
	private String rejectedAnonymousToken;

	private String spDc;
	private String accountAccessToken;
	private Instant accountAccessTokenExpire;

	public SpotifyTokenTracker(SpotifySourceManager source, String clientId, String clientSecret, String spDc) {
		this(source, clientId, clientSecret, spDc, null);
	}

	public SpotifyTokenTracker(SpotifySourceManager source, String clientId, String clientSecret, String spDc, String customTokenEndpoint) {
		this.sourceManager = source;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.customTokenEndpoint = customTokenEndpoint;

		if (!hasValidCredentials()) {
			log.debug("Missing/invalid credentials, falling back to public token.");
		}

		this.spDc = spDc;

		if (!hasValidAccountCredentials()) {
			log.debug("Missing/invalid account credentials");
		}
	}

	public void setClientIDS(String clientId, String clientSecret) {
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.accessToken = null;
		this.expires = null;
	}

	public synchronized void setCustomTokenEndpoint(String customTokenEndpoint) {
		this.customTokenEndpoint = customTokenEndpoint;
		this.anonymousToken = null;
		this.anonymousRefreshAfter = Instant.EPOCH;
		this.rejectedAnonymousToken = null;
		this.accountAccessToken = null;
		this.accountAccessTokenExpire = null;
	}

	boolean hasValidCredentials() {
		return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
	}

	public String getAccessToken(boolean useAnonymousToken) throws IOException {
		if (useAnonymousToken || !hasValidCredentials()) {
			return this.getAnonymousAccessToken();
		}
		if (this.accessToken == null || this.expires == null || this.expires.isBefore(Instant.now())) {
			synchronized (this) {
				if (accessToken == null || this.expires == null || this.expires.isBefore(Instant.now())) {
					log.debug("Access token is invalid or expired, refreshing token...");
					this.refreshAccessToken();
				}
			}
		}
		return this.accessToken;
	}

	private void refreshAccessToken() throws IOException {
		var request = new HttpPost("https://accounts.spotify.com/api/token");
		request.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString((this.clientId + ":" + this.clientSecret).getBytes(StandardCharsets.UTF_8)));
		request.setEntity(new UrlEncodedFormEntity(List.of(new BasicNameValuePair("grant_type", "client_credentials")), StandardCharsets.UTF_8));

		JsonBrowser json;
		try (var http = sourceManager.getHttpInterface()) {
			json = LavaSrcTools.fetchResponseAsJson(http, request);
		}
		if (json == null) {
			throw new RuntimeException("No response from Spotify API");
		}
		if (!json.get("error").isNull()) {
			var error = json.get("error").text();
			throw new RuntimeException("Error while fetching access token: " + error);
		}
		accessToken = json.get("access_token").text();
		expires = Instant.now().plusSeconds(json.get("expires_in").asLong(0));
	}

	public synchronized String getAnonymousAccessToken() throws IOException {
		var cached = this.anonymousToken;
		if (cached == null || (shouldRefresh(cached.getKey(), cached.getValue())
			&& (getCachedAnonymousAccessToken() == null || !Instant.now().isBefore(this.anonymousRefreshAfter)))) {
			try {
				this.refreshAnonymousAccessToken();
				this.anonymousRefreshAfter = Instant.EPOCH;
			} catch (IOException | RuntimeException e) {
				if (getCachedAnonymousAccessToken() == null) {
					throw e;
				}
				this.anonymousRefreshAfter = Instant.now().plusSeconds(5);
				log.warn("Anonymous token refresh failed; using unexpired cache briefly");
			}
		}
		return this.anonymousToken.getKey();
	}

	String getCachedAnonymousAccessToken() {
		var cached = this.anonymousToken;
		return cached != null && isUsable(cached.getKey(), cached.getValue()) ? cached.getKey() : null;
	}

	// A late 401 must not invalidate a token refreshed by another request.
	synchronized void invalidateAnonymousToken(String rejectedToken) {
		if (this.anonymousToken != null && Objects.equals(this.anonymousToken.getKey(), rejectedToken)) {
			this.rejectedAnonymousToken = rejectedToken;
			this.anonymousToken = null;
			this.anonymousRefreshAfter = Instant.EPOCH;
		}
	}

	private static boolean shouldRefresh(String token, Instant expiry) {
		return token == null || expiry == null || !expiry.isAfter(Instant.now().plusSeconds(TOKEN_REFRESH_MARGIN_SECONDS));
	}

	private static boolean isUsable(String token, Instant expiry) {
		return token != null && expiry != null && expiry.isAfter(Instant.now());
	}

	private void refreshAnonymousAccessToken() throws IOException {
		var endpoint = generateGetAccessTokenURL();
		JsonBrowser json;
		if (hasCustomTokenEndpoint()) {
			json = fetchCustomToken(endpoint, null);
		} else {
			try (var http = sourceManager.getHttpInterface()) {
				json = LavaSrcTools.fetchResponseAsJson(http, new HttpGet(endpoint));
			}
		}
		updateAnonymousToken(json);
	}

	private boolean hasCustomTokenEndpoint() {
		return this.customTokenEndpoint != null && !this.customTokenEndpoint.isBlank();
	}

	private JsonBrowser fetchCustomToken(String endpoint, String cookie) throws IOException {
		try (var client = HttpClients.custom().setDefaultRequestConfig(CUSTOM_TOKEN_REQUEST_CONFIG).disableAutomaticRetries().build()) {
			var attempt = 0;
			while (true) {
				attempt++;
				var request = new HttpGet(endpoint);
				request.setConfig(CUSTOM_TOKEN_REQUEST_CONFIG);
				if (cookie != null) {
					request.addHeader("App-Platform", "WebPlayer");
					request.addHeader("Cookie", cookie);
				}

				try (var response = client.execute(request)) {
					var status = response.getStatusLine().getStatusCode();
					if (status >= 500 && status <= 599 && attempt == 1) {
						log.warn("Custom Spotify token endpoint returned HTTP {}; retrying once", status);
						continue;
					}
					if (status != HttpStatus.SC_OK) {
						throw new IOException("Custom Spotify token endpoint returned HTTP " + status);
					}
					if (response.getEntity() == null) {
						throw new IOException("Custom Spotify token endpoint returned no payload");
					}
					try (var input = response.getEntity().getContent()) {
						var body = input.readNBytes((1 << 20) + 1);
						if (body.length > 1 << 20) {
							throw new IOException("Custom Spotify token endpoint returned an oversized payload");
						}
						try {
							return JsonBrowser.parse(new String(body, StandardCharsets.UTF_8));
						} catch (IOException e) {
							// JSON parser exceptions can contain credentials from the body.
							throw new IOException("Custom Spotify token endpoint returned invalid JSON");
						}
					}
				}
			}
		}
	}

	private void updateAnonymousToken(JsonBrowser json) throws IOException {
		if (json == null) {
			throw new IOException("No response from Spotify API while fetching anonymous access token.");
		}
		if (!json.get("error").isNull() || !json.get("isAnonymous").asBoolean(true)) {
			throw new IOException("Invalid anonymous Spotify token response");
		}

		var token = json.get("accessToken").text();
		var expiryMillis = json.get("accessTokenExpirationTimestampMs").asLong(0);
		var expiry = Instant.ofEpochMilli(expiryMillis);
		if (token == null || token.isBlank() || !expiry.isAfter(Instant.now())) {
			throw new IOException("Spotify token response is missing a usable accessToken or expiry.");
		}

		if (Objects.equals(token, this.rejectedAnonymousToken)) {
			throw new IOException("Spotify token endpoint returned a token rejected by Partner API");
		}
		this.rejectedAnonymousToken = null;
		this.anonymousToken = Map.entry(token, expiry);
	}

	public void setSpDc(String spDc) {
		this.spDc = spDc;
		this.accountAccessToken = null;
		this.accountAccessTokenExpire = null;
	}

	public String getAccountAccessToken() throws IOException {
		if (shouldRefresh(this.accountAccessToken, this.accountAccessTokenExpire)) {
			synchronized (this) {
				if (shouldRefresh(this.accountAccessToken, this.accountAccessTokenExpire)) {
					log.debug("Account token is invalid or nearing expiry, refreshing token...");
					try {
						this.refreshAccountAccessToken();
					} catch (IOException | RuntimeException e) {
						if (!isUsable(this.accountAccessToken, this.accountAccessTokenExpire)) {
							throw e;
						}
						log.warn("Account token refresh failed; using cached token until {}", this.accountAccessTokenExpire, e);
					}
				}
			}
		}
		return this.accountAccessToken;
	}

	public void refreshAccountAccessToken() throws IOException {
		var endpoint = generateGetAccessTokenURL();
		var request = new HttpGet(endpoint);
		request.addHeader("App-Platform", "WebPlayer");
		request.addHeader("Cookie", "sp_dc=" + this.spDc);

		JsonBrowser json;
		if (hasCustomTokenEndpoint()) {
			json = fetchCustomToken(endpoint, "sp_dc=" + this.spDc);
		} else {
			try (var http = this.sourceManager.getHttpInterface()) {
				json = LavaSrcTools.fetchResponseAsJson(http, request);
			}
		}
		if (json == null) {
			throw new IOException("No response from Spotify API while fetching account access token.");
		}
		if (!json.get("error").isNull()) {
			throw new IOException("Error while fetching account access token: " + json.get("error").text());
		}

		var token = json.get("accessToken").text();
		var expiry = Instant.ofEpochMilli(json.get("accessTokenExpirationTimestampMs").asLong(0));
		if (token == null || token.isBlank() || !expiry.isAfter(Instant.now())) {
			throw new IOException("Spotify token response is missing a usable account accessToken or expiry.");
		}
		this.accountAccessToken = token;
		this.accountAccessTokenExpire = expiry;
	}

	public boolean hasValidAccountCredentials() {
		return this.spDc != null && !this.spDc.isEmpty();
	}

	private String generateGetAccessTokenURL() throws IOException {
		if (this.customTokenEndpoint != null && !this.customTokenEndpoint.isBlank()) {
			return this.customTokenEndpoint;
		}

		var secret = requestSecret();
		if (secret == null) {
			throw new IOException("Failed to retrieve secret from Spotify.");
		}
		var transformedSecret = convertArrayToTransformedByteArray(secret);
		var hexSecret = toHexString(transformedSecret);
		var totp = generateTOTP(hexSecret, 30, 6);
		var ts = System.currentTimeMillis();
		return "https://open.spotify.com/api/token?reason=init&productType=web-player&totp=" + totp + "&totpVer=7&ts=" + ts;
	}

	private byte[] requestSecret() throws IOException {
		String homepageUrl = "https://open.spotify.com/";
		String scriptPattern = "mobile-web-player";

		log.debug("Requesting secret from Spotify homepage: {}", homepageUrl);

		try (CloseableHttpClient client = HttpClients.createDefault()) {
			HttpGet request = new HttpGet(homepageUrl);
			try (CloseableHttpResponse response = client.execute(request)) {
				String html = EntityUtils.toString(response.getEntity());
				Document doc = Jsoup.parse(html);
				Elements scriptElements = doc.select("script[src]");
				List<String> scriptUrls = new ArrayList<>();
				log.debug("Found {} script elements in the HTML", scriptElements.size());
				for (Element script : scriptElements) {
					String scriptUrl = script.attr("src");
					if (scriptUrl.contains(scriptPattern) && !scriptUrl.contains("vendor")) {
						scriptUrls.add(scriptUrl);
						log.debug("Found relevant script URL: {}", scriptUrl);
					}
				}
				if (scriptUrls.isEmpty()) {
					log.debug("No relevant script URLs found.");
					return null;
				}
				for (String scriptUrl : scriptUrls) {
					log.debug("Attempting to extract secret from script URL: {}", scriptUrl);
					byte[] secret = extractSecret(client, scriptUrl);
					if (secret != null) {
						log.debug("Successfully extracted secret.");
						return secret;
					}
				}
			}
		} catch (IOException e) {
			log.error("Failed to request or parse the secret", e);
			throw new IOException("Failed to request or parse the secret", e);
		}
		log.error("No secret found.");
		return null;
	}

	private static String generateTOTP(String secret, int period, int digits) {
		var time = System.currentTimeMillis() / 1000 / period;
		var buffer = ByteBuffer.allocate(8);
		buffer.putLong(time);
		var timeBytes = buffer.array();

		try {
			var keySpec = new SecretKeySpec(hexStringToByteArray(secret), "HmacSHA1");
			var mac = Mac.getInstance("HmacSHA1");
			mac.init(keySpec);
			var hash = mac.doFinal(timeBytes);
			var offset = hash[hash.length - 1] & 0xF;
			var binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16) |
				((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
			var otp = binary % (int) Math.pow(10, digits);
			return String.format("%0" + digits + "d", otp);
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new RuntimeException("Error generating TOTP", e);
		}
	}

	private static byte[] extractSecret(CloseableHttpClient client, String scriptUrl) throws IOException {
		var scriptRequest = new HttpGet(scriptUrl);
		try (var scriptResponse = client.execute(scriptRequest)) {
			var scriptContent = EntityUtils.toString(scriptResponse.getEntity());

			var matcher = SECRET_PATTERN.matcher(scriptContent);
			if (matcher.find()) {
				var secretArrayString = matcher.group(1);
				var secretArray = secretArrayString.split(",");
				byte[] secretByteArray = new byte[secretArray.length];
				for (int i = 0; i < secretArray.length; i++) {
					secretByteArray[i] = (byte) Integer.parseInt(secretArray[i].trim());
				}

				return secretByteArray;
			} else {
				log.error("No secret array found in script: {}", scriptUrl);
				return null;
			}
		}
	}

	private static byte[] convertArrayToTransformedByteArray(byte[] array) {
		byte[] transformed = new byte[array.length];
		for (int i = 0; i < array.length; i++) {
			// XOR with dat transform
			transformed[i] = (byte) (array[i] ^ ((i % 33) + 9));
		}
		return transformed;
	}

	private static String toHexString(byte[] transformed) {
		StringBuilder joinedString = new StringBuilder();
		for (byte b : transformed) {
			joinedString.append(b);
		}
		byte[] utf8Bytes = joinedString.toString().getBytes(StandardCharsets.UTF_8);
		StringBuilder hexString = new StringBuilder();
		for (byte b : utf8Bytes) {
			hexString.append(String.format("%02x", b));
		}
		return hexString.toString();
	}

	private static byte[] hexStringToByteArray(String s) {
		int len = s.length();
		byte[] data = new byte[len / 2];
		for (int i = 0; i < len; i += 2) {
			data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
				+ Character.digit(s.charAt(i + 1), 16));
		}
		return data;
	}

}
