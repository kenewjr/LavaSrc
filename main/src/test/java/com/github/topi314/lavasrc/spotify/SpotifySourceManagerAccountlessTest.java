package com.github.topi314.lavasrc.spotify;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpotifySourceManagerAccountlessTest {
	@Test
	void accountlessModeBlocksSpotifyV1BeforeSendingARequest() {
		var manager = new SpotifySourceManager(
			null,
			null,
			false,
			null,
			null,
			"US",
			unused -> null,
			track -> null
		);
		try {
			var error = assertThrows(IOException.class, () -> manager.getJson(SpotifySourceManager.API_BASE + "tracks/test"));
			assertEquals("Spotify Web API v1 is unavailable without app credentials; use Partner API metadata.", error.getMessage());
		} finally {
			manager.shutdown();
		}
	}
}
