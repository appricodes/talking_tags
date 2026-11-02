package com.appricodes.voicetag;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;

/** Records AAC audio into an .m4a file. */
final class VoiceRecorder {

	private static final int MAX_DURATION_MS = 10 * 60 * 1000;
	/** Peak amplitude (of 32767) above which the microphone is taken to hear speech. */
	private static final int SPEECH_LEVEL = 1500;

	private MediaRecorder recorder;
	private File output;
	private long startedAt;
	/** When speech was last heard, or 0 before the first speech. */
	private long lastSpeechAt;

	boolean isRecording() {
		return recorder != null;
	}

	long elapsedMillis() {
		return recorder == null ? 0 : SystemClock.elapsedRealtime() - startedAt;
	}

	/**
	 * Samples the microphone level; call it regularly while recording. Returns how long it has
	 * been silent since speech was last heard, or 0 while nobody has spoken yet.
	 */
	long silenceAfterSpeechMillis() {
		if (recorder == null) return 0;
		long now = SystemClock.elapsedRealtime();
		int level;
		try {
			level = recorder.getMaxAmplitude();
		} catch (RuntimeException e) {
			return 0;
		}
		if (level >= SPEECH_LEVEL) lastSpeechAt = now;
		return lastSpeechAt == 0 ? 0 : now - lastSpeechAt;
	}

	/** {@code onMaxDuration} runs on the main thread when the length limit stops the recording. */
	void start(Context context, File out, Runnable onMaxDuration) throws IOException {
		if (recorder != null) cancel();
		MediaRecorder r = createRecorder(context);
		try {
			r.setAudioSource(MediaRecorder.AudioSource.MIC);
			r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
			r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
			r.setAudioChannels(1);
			r.setAudioSamplingRate(44100);
			r.setAudioEncodingBitRate(64000);
			r.setMaxDuration(MAX_DURATION_MS);
			r.setOutputFile(out.getAbsolutePath());
			r.setOnInfoListener((mr, what, extra) -> {
				if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxDuration.run();
			});
			r.prepare();
			r.start();
		} catch (IOException | RuntimeException e) {
			r.release();
			//noinspection ResultOfMethodCallIgnored
			out.delete();
			throw e instanceof IOException ? (IOException) e : new IOException(e);
		}
		recorder = r;
		output = out;
		startedAt = SystemClock.elapsedRealtime();
		lastSpeechAt = 0;
		// The first call only resets the peak level.
		silenceAfterSpeechMillis();
	}

	/** Stops and returns the recorded file, or null when nothing usable was recorded. */
	File stop() {
		if (recorder == null) return null;
		File result = output;
		try {
			recorder.stop();
		} catch (RuntimeException e) {
			// stop() throws when no audio data was captured.
			//noinspection ResultOfMethodCallIgnored
			result.delete();
			result = null;
		} finally {
			recorder.release();
			recorder = null;
			output = null;
		}
		return result;
	}

	/** Stops and deletes the recording. */
	void cancel() {
		File f = stop();
		if (f != null) {
			//noinspection ResultOfMethodCallIgnored
			f.delete();
		}
	}

	@SuppressWarnings("deprecation")
	private static MediaRecorder createRecorder(Context context) {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? new MediaRecorder(context) : new MediaRecorder();
	}
}
