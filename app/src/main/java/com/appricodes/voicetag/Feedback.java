package com.appricodes.voicetag;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.accessibility.AccessibilityManager;

import androidx.preference.PreferenceManager;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Spoken output through a chosen TTS engine, and vibration. */
final class Feedback {

	static final String PREF_SPEECH_OUTPUT = "speech_output";
	static final String PREF_VIBRATE = "vibrate";
	/** Speech output value: the system's default TTS engine. */
	static final String OUTPUT_DEFAULT = "default";
	/** Speech output value: leave all speaking to TalkBack. Any other value is an engine package. */
	static final String OUTPUT_TALKBACK = "talkback";

	private final Context context;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final Map<String, Runnable> doneCallbacks = new ConcurrentHashMap<>();
	private TextToSpeech tts;
	private String ttsOutput;
	private boolean ttsReady;
	private int utteranceCounter;

	Feedback(Context context) {
		this.context = context.getApplicationContext();
	}

	/** The chosen output; without a choice, TalkBack when a screen reader runs, else the default engine. */
	static String speechOutput(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		String value = prefs.getString(PREF_SPEECH_OUTPUT, null);
		if (value == null || value.isEmpty()) {
			return isScreenReaderOn(context) ? OUTPUT_TALKBACK : OUTPUT_DEFAULT;
		}
		return value;
	}

	static boolean isScreenReaderOn(Context context) {
		AccessibilityManager am = context.getSystemService(AccessibilityManager.class);
		return am != null && am.isEnabled() && am.isTouchExplorationEnabled();
	}

	static List<TextToSpeech.EngineInfo> installedEngines(Context context) {
		TextToSpeech probe = new TextToSpeech(context.getApplicationContext(), status -> { });
		try {
			return probe.getEngines();
		} finally {
			probe.shutdown();
		}
	}

	/** Applies a changed speech output setting; call from onResume. */
	void refresh() {
		String output = speechOutput(context);
		if (output.equals(ttsOutput)) return;
		shutdown();
		ttsOutput = output;
		if (OUTPUT_TALKBACK.equals(output)) return;

		final TextToSpeech[] created = new TextToSpeech[1];
		TextToSpeech.OnInitListener listener = status -> {
			if (created[0] != tts) return;
			ttsReady = status == TextToSpeech.SUCCESS;
			if (ttsReady) tts.setOnUtteranceProgressListener(progressListener);
		};
		created[0] = OUTPUT_DEFAULT.equals(output)
				? new TextToSpeech(context, listener)
				: new TextToSpeech(context, listener, output);
		tts = created[0];
	}

	/** False in TalkBack mode or while the engine is unavailable. */
	boolean canSpeak() {
		return ttsReady;
	}

	/** Speaks text, interrupting earlier speech; {@code onDone} runs on the main thread afterwards. */
	void speak(String text, String languageTag, Runnable onDone) {
		if (!ttsReady) return;
		Locale locale = languageTag == null || languageTag.isEmpty()
				? Locale.getDefault() : Locale.forLanguageTag(languageTag);
		if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.getDefault());
		doneCallbacks.clear();
		String id = "u" + (++utteranceCounter);
		if (onDone != null) doneCallbacks.put(id, onDone);
		tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
	}

	void stop() {
		doneCallbacks.clear();
		if (ttsReady) tts.stop();
	}

	void shutdown() {
		doneCallbacks.clear();
		if (tts != null) tts.shutdown();
		tts = null;
		ttsReady = false;
		ttsOutput = null;
	}

	void vibrateTick() {
		vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
	}

	void vibrateSuccess() {
		vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE));
	}

	void vibrateError() {
		vibrate(VibrationEffect.createWaveform(new long[] {0, 100, 100, 100, 100, 100}, -1));
	}

	private void vibrate(VibrationEffect effect) {
		if (!PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_VIBRATE, true)) return;
		Vibrator vibrator = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
				? context.getSystemService(VibratorManager.class).getDefaultVibrator()
				: context.getSystemService(Vibrator.class);
		if (vibrator != null && vibrator.hasVibrator()) vibrator.vibrate(effect);
	}

	private final UtteranceProgressListener progressListener = new UtteranceProgressListener() {
		@Override
		public void onStart(String utteranceId) { }

		@Override
		public void onDone(String utteranceId) {
			Runnable r = doneCallbacks.remove(utteranceId);
			if (r != null) mainHandler.post(r);
		}

		@Override
		@SuppressWarnings("deprecation")
		public void onError(String utteranceId) {
			onDone(utteranceId);
		}

		@Override
		public void onStop(String utteranceId, boolean interrupted) {
			doneCallbacks.remove(utteranceId);
		}
	};
}
