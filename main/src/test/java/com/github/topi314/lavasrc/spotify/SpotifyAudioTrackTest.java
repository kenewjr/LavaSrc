package com.github.topi314.lavasrc.spotify;

import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpotifyAudioTrackTest {

	@Test
	void existingIsrcInMetadataDoesNotTriggerLazyLookup() {
		var lookups = new AtomicInteger();
		var sourceManager = new SpotifySourceManager(
			new String[]{"ytsearch:\"%ISRC%\"", "ytsearch:%QUERY%"},
			null,
			null,
			"US",
			unused -> null
		) {
			@Override
			String resolveIsrc(String trackId) {
				lookups.incrementAndGet();
				return "NEW-ISRC-12345";
			}
		};

		var track = new SpotifyAudioTrack(
			new AudioTrackInfo("Title", "Author", 200_000, "track-1", false, "https://open.spotify.com/track/track-1", null, "METADATA-ISRC"),
			"Album",
			"https://open.spotify.com/album/album-1",
			null,
			null,
			null,
			false,
			sourceManager
		);

		assertEquals("METADATA-ISRC", track.getResolutionIsrc());
		assertEquals(0, lookups.get());
	}

	@Test
	void missingIsrcPerformsAtMostOneLazyLookupPerInstance() {
		var lookups = new AtomicInteger();
		var sourceManager = new SpotifySourceManager(
			new String[]{"ytsearch:\"%ISRC%\"", "ytsearch:%QUERY%"},
			null,
			null,
			"US",
			unused -> null
		) {
			@Override
			String resolveIsrc(String trackId) {
				lookups.incrementAndGet();
				return "RESOLVED-ISRC";
			}
		};

		var track = new SpotifyAudioTrack(
			new AudioTrackInfo("Title", "Author", 200_000, "track-2", false, "https://open.spotify.com/track/track-2", null, null),
			"Album",
			"https://open.spotify.com/album/album-1",
			null,
			null,
			null,
			false,
			sourceManager
		);

		assertNull(track.getInfo().isrc);
		assertEquals("RESOLVED-ISRC", track.getResolutionIsrc());
		assertEquals("RESOLVED-ISRC", track.getResolutionIsrc());
		assertEquals(1, lookups.get());
		// trackInfo isrc remains immutable
		assertNull(track.getInfo().isrc);
	}

	@Test
	void previewAndLocalTracksSkipLazyLookup() {
		var lookups = new AtomicInteger();
		var sourceManager = new SpotifySourceManager(
			new String[]{"ytsearch:\"%ISRC%\""},
			null,
			null,
			"US",
			unused -> null
		) {
			@Override
			String resolveIsrc(String trackId) {
				lookups.incrementAndGet();
				return "SHOULD-NOT-HAPPEN";
			}
		};

		var previewTrack = new SpotifyAudioTrack(
			new AudioTrackInfo("Preview", "Author", 30_000, "track-prev", false, "https://open.spotify.com/track/track-prev", null, null),
			"Album",
			null,
			null,
			null,
			"https://preview.mp3",
			true,
			sourceManager
		);
		assertNull(previewTrack.getResolutionIsrc());

		var localTrack = new SpotifyAudioTrack(
			new AudioTrackInfo("Local", "Author", 100_000, "local", false, "https://open.spotify.com/track/local", null, null),
			"Album",
			null,
			null,
			null,
			null,
			false,
			sourceManager
		);
		assertNull(localTrack.getResolutionIsrc());
		assertEquals(0, lookups.get());
	}

	@Test
	void clonePreservesResolvedIsrcAndMetadata() {
		var lookups = new AtomicInteger();
		var sourceManager = new SpotifySourceManager(
			new String[]{"ytsearch:\"%ISRC%\""},
			null,
			null,
			"US",
			unused -> null
		) {
			@Override
			String resolveIsrc(String trackId) {
				lookups.incrementAndGet();
				return "CLONE-ISRC";
			}
		};

		var track = new SpotifyAudioTrack(
			new AudioTrackInfo("Title", "Author", 200_000, "track-3", false, "https://open.spotify.com/track/track-3", null, null),
			"Album Name",
			"https://open.spotify.com/album/3",
			"https://open.spotify.com/artist/3",
			"https://artist-art.jpg",
			null,
			false,
			sourceManager
		);

		assertEquals("CLONE-ISRC", track.getResolutionIsrc());
		assertEquals(1, lookups.get());

		var clone = (SpotifyAudioTrack) track.makeClone();
		assertEquals("CLONE-ISRC", clone.getResolutionIsrc());
		// Clone reused already resolved value without additional lookup
		assertEquals(1, lookups.get());
		assertEquals("Album Name", clone.getAlbumName());
		assertEquals("https://open.spotify.com/album/3", clone.getAlbumUrl());
		assertEquals("https://open.spotify.com/artist/3", clone.getArtistUrl());
		assertEquals("https://artist-art.jpg", clone.getArtistArtworkUrl());
	}

	@Test
	void resolverUsesResolutionIsrcThenFallsBackToQuery() {
		var queries = new java.util.ArrayList<String>();
		var resolver = new DefaultMirroringAudioTrackResolver(new String[]{"ytsearch:\"%ISRC%\"", "ytsearch:%QUERY%"});

		var sourceManager = new SpotifySourceManager(
			null,
			null,
			"US",
			unused -> null,
			resolver
		) {
			@Override
			String resolveIsrc(String trackId) {
				return "US-RC1-76-05442";
			}

			@Override
			public AudioItem getSearch(String query, boolean preview) {
				queries.add(query);
				return AudioReference.NO_TRACK;
			}
		};

		var track = new SpotifyAudioTrack(
			new AudioTrackInfo("Song Title", "Artist Name", 200_000, "track-4", false, "https://open.spotify.com/track/track-4", null, null),
			"Album",
			null,
			null,
			null,
			null,
			false,
			sourceManager
		) {
			@Override
			public AudioItem loadItem(String query) {
				queries.add(query);
				return AudioReference.NO_TRACK;
			}
		};

		AudioItem result = resolver.apply(track);
		assertEquals(AudioReference.NO_TRACK, result);

		// Verified order: ISRC without hyphens, then text query
		assertEquals(2, queries.size());
		assertEquals("ytsearch:\"USRC17605442\"", queries.get(0));
		assertEquals("ytsearch:Song Title Artist Name", queries.get(1));
	}

	@Test
	void decodeTrackRoundTripSupportsLazyResolution() throws IOException {
		var sourceManager = new SpotifySourceManager(
			new String[]{"ytsearch:\"%ISRC%\""},
			null,
			null,
			"US",
			unused -> null
		) {
			@Override
			String resolveIsrc(String trackId) {
				return "DECODED-ISRC";
			}
		};

		var originalTrack = new SpotifyAudioTrack(
			new AudioTrackInfo("Song", "Artist", 180_000, "track-5", false, "https://open.spotify.com/track/track-5", null, null),
			"Album",
			"https://album",
			"https://artist",
			"https://art",
			"https://preview",
			false,
			sourceManager
		);

		var bout = new ByteArrayOutputStream();
		var dout = new DataOutputStream(bout);
		sourceManager.encodeTrack(originalTrack, dout);

		var din = new DataInputStream(new ByteArrayInputStream(bout.toByteArray()));
		var decodedTrack = (SpotifyAudioTrack) sourceManager.decodeTrack(originalTrack.getInfo(), din);

		assertEquals("Album", decodedTrack.getAlbumName());
		assertEquals("DECODED-ISRC", decodedTrack.getResolutionIsrc());
	}
}
