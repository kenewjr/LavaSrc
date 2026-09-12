package com.github.topi314.lavasrc.spotify;

import com.github.topi314.lavasrc.mirror.MirroringAudioSourceManager;
import com.github.topi314.lavasrc.mirror.MirroringAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;

public class SpotifyAudioTrack extends MirroringAudioTrack {


	private boolean isrcResolved;
	private String resolvedIsrc;

	public SpotifyAudioTrack(AudioTrackInfo trackInfo, SpotifySourceManager sourceManager) {
		this(trackInfo, null, null, null, null, null, false, sourceManager);
	}

	public SpotifyAudioTrack(AudioTrackInfo trackInfo, String albumName, String albumUrl, String artistUrl, String artistArtworkUrl, String previewUrl, boolean isPreview, MirroringAudioSourceManager sourceManager) {
		super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, previewUrl, isPreview, sourceManager);
	}

	@Override
	protected InternalAudioTrack createAudioTrack(AudioTrackInfo trackInfo, SeekableInputStream stream) {
		return new Mp3AudioTrack(trackInfo, stream);
	}

	@Override
	public synchronized String getResolutionIsrc() {
		if (this.trackInfo.isrc != null && !this.trackInfo.isrc.isBlank()) {
			return this.trackInfo.isrc;
		}
		if (!this.isrcResolved && !this.isPreview && !this.isLocal()) {
			this.isrcResolved = true;
			this.resolvedIsrc = ((SpotifySourceManager) this.sourceManager).resolveIsrc(this.trackInfo.identifier);
		}
		return this.resolvedIsrc;
	}

	@Override
	protected synchronized AudioTrack makeShallowClone() {
		var clone = new SpotifyAudioTrack(this.trackInfo, this.albumName, this.albumUrl,
			this.artistUrl, this.artistArtworkUrl, this.previewUrl, this.isPreview, this.sourceManager);
		clone.isrcResolved = this.isrcResolved;
		clone.resolvedIsrc = this.resolvedIsrc;
		return clone;
	}

	public boolean isLocal() {
		return "local".equals(this.trackInfo.identifier);
	}

}
