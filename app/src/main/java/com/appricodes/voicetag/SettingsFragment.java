package com.appricodes.voicetag;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsFragment extends PreferenceFragmentCompat {

	private static final String TAG = "VoiceTag";
	static final String PREF_LANGUAGE = "language_code";
	static final String PREF_AUTOPLAY = "autoplay_voice";
	static final String LANGUAGE_AUTO = "auto";
	private static final String PREF_VOICE_NOTES_INFO = "voice_notes_info";
	private static final String PREF_EXPORT = "export_voice_notes";
	private static final String PREF_IMPORT = "import_voice_notes";

	/** Google speech recognition languages; display names come from the device locale. */
	private static final String[] LANGUAGE_CODES = {
			"af-ZA", "am-ET", "ar-SA", "ar-EG", "ar-AE", "hy-AM", "az-AZ", "eu-ES", "bn-BD", "bn-IN",
			"bg-BG", "ca-ES", "zh-CN", "zh-TW", "yue-Hant-HK", "hr-HR", "cs-CZ", "da-DK", "nl-NL",
			"en-AU", "en-CA", "en-IN", "en-IE", "en-NZ", "en-PH", "en-ZA", "en-GB", "en-US", "fi-FI",
			"fr-FR", "fr-CA", "gl-ES", "ka-GE", "de-DE", "el-GR", "gu-IN", "he-IL", "hi-IN", "hu-HU",
			"is-IS", "id-ID", "it-IT", "ja-JP", "jv-ID", "kn-IN", "km-KH", "ko-KR", "lo-LA", "lv-LV",
			"lt-LT", "ms-MY", "ml-IN", "mr-IN", "ne-NP", "no-NO", "fa-IR", "pl-PL", "pt-BR", "pt-PT",
			"ro-RO", "ru-RU", "sr-RS", "si-LK", "sk-SK", "sl-SI", "es-ES", "es-MX", "es-US", "es-AR",
			"es-CO", "su-ID", "sw-TZ", "sv-SE", "ta-IN", "te-IN", "th-TH", "tr-TR", "uk-UA", "ur-PK",
			"ur-IN", "vi-VN", "zu-ZA"
	};

	private final ExecutorService io = Executors.newSingleThreadExecutor();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
			new ActivityResultContracts.CreateDocument("application/zip"), this::exportTo);
	private final ActivityResultLauncher<String[]> importLauncher = registerForActivityResult(
			new ActivityResultContracts.OpenMultipleDocuments(), this::importFrom);

	@Override
	public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
		setPreferencesFromResource(R.xml.preferences, rootKey);
		setupSpeechOutput();
		setupLanguages();
		requirePreference(PREF_EXPORT).setOnPreferenceClickListener(p -> {
			startExport();
			return true;
		});
		requirePreference(PREF_IMPORT).setOnPreferenceClickListener(p -> {
			importLauncher.launch(new String[] {
					"application/zip", "application/x-zip-compressed", "audio/*", "application/octet-stream"
			});
			return true;
		});
		setupAbout();
	}

	private void setupAbout() {
		requirePreference("about_privacy").setOnPreferenceClickListener(p -> {
			startActivity(InfoActivity.intent(requireContext(), InfoActivity.PAGE_PRIVACY));
			return true;
		});
		requirePreference("about_imprint").setOnPreferenceClickListener(p -> {
			startActivity(InfoActivity.intent(requireContext(), InfoActivity.PAGE_IMPRINT));
			return true;
		});
		requirePreference("about_licenses").setOnPreferenceClickListener(p -> {
			startActivity(InfoActivity.intent(requireContext(), InfoActivity.PAGE_LICENSES));
			return true;
		});
		requirePreference("about_source").setOnPreferenceClickListener(p -> {
			InfoActivity.openUrl(requireContext(), InfoActivity.URL_PROJECT);
			return true;
		});
		requirePreference("about_version").setTitle(getString(R.string.about_version, BuildConfig.VERSION_NAME));
	}

	@Override
	public void onResume() {
		super.onResume();
		updateVoiceNotesInfo();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		io.shutdown();
	}

	private <T extends Preference> T requirePreference(String key) {
		return Objects.requireNonNull(findPreference(key));
	}

	/** Installed TTS engines plus a TalkBack-only option. */
	private void setupSpeechOutput() {
		ListPreference pref = requirePreference(Feedback.PREF_SPEECH_OUTPUT);
		List<CharSequence> labels = new ArrayList<>();
		List<CharSequence> values = new ArrayList<>();
		labels.add(getString(R.string.speech_output_default));
		values.add(Feedback.OUTPUT_DEFAULT);
		for (TextToSpeech.EngineInfo engine : Feedback.installedEngines(requireContext())) {
			labels.add(engine.label);
			values.add(engine.name);
		}
		labels.add(getString(R.string.speech_output_talkback));
		values.add(Feedback.OUTPUT_TALKBACK);
		pref.setEntries(labels.toArray(new CharSequence[0]));
		pref.setEntryValues(values.toArray(new CharSequence[0]));

		// Without a saved choice the effective output depends on whether TalkBack runs.
		Context context = requireContext();
		pref.setSummaryProvider(p -> {
			int index = pref.findIndexOfValue(Feedback.speechOutput(context));
			return labels.get(Math.max(index, 0));
		});
	}

	private void setupLanguages() {
		ListPreference pref = requirePreference(PREF_LANGUAGE);
		Locale ui = Locale.getDefault();
		List<String[]> languages = new ArrayList<>();
		for (String code : LANGUAGE_CODES) {
			languages.add(new String[] {Locale.forLanguageTag(code).getDisplayName(ui), code});
		}
		Collator collator = Collator.getInstance(ui);
		languages.sort((a, b) -> collator.compare(a[0], b[0]));

		CharSequence[] labels = new CharSequence[languages.size() + 1];
		CharSequence[] values = new CharSequence[languages.size() + 1];
		labels[0] = getString(R.string.lang_auto_detect);
		values[0] = LANGUAGE_AUTO;
		for (int i = 0; i < languages.size(); i++) {
			labels[i + 1] = languages.get(i)[0];
			values[i + 1] = languages.get(i)[1];
		}
		pref.setEntries(labels);
		pref.setEntryValues(values);
		if (pref.findIndexOfValue(pref.getValue()) < 0) pref.setValue(LANGUAGE_AUTO);
	}

	private void updateVoiceNotesInfo() {
		List<File> files = VoiceNotes.list(requireContext());
		String size = Formatter.formatShortFileSize(requireContext(), VoiceNotes.totalSize(files));
		requirePreference(PREF_VOICE_NOTES_INFO).setTitle(
				getResources().getQuantityString(R.plurals.voice_notes_count, files.size(), files.size(), size));
	}

	private void startExport() {
		if (VoiceNotes.list(requireContext()).isEmpty()) {
			Toast.makeText(requireContext(), R.string.msg_export_empty, Toast.LENGTH_LONG).show();
			return;
		}
		String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
		exportLauncher.launch("TalkingTags-voice-notes-" + date + ".zip");
	}

	private void exportTo(Uri uri) {
		if (uri == null) return;
		Context app = requireContext().getApplicationContext();
		io.execute(() -> {
			String message;
			try (OutputStream out = app.getContentResolver().openOutputStream(uri, "w")) {
				if (out == null) throw new IOException("No output stream");
				int count = VoiceNotes.exportZip(app, out);
				message = app.getResources().getQuantityString(R.plurals.msg_export_done, count, count);
			} catch (IOException | RuntimeException e) {
				Log.w(TAG, "Export failed", e);
				message = app.getString(R.string.error_export_failed);
			}
			showResult(app, message);
		});
	}

	private void importFrom(List<Uri> uris) {
		if (uris == null || uris.isEmpty()) return;
		Context app = requireContext().getApplicationContext();
		io.execute(() -> {
			int count = 0;
			boolean failed = false;
			ContentResolver resolver = app.getContentResolver();
			for (Uri uri : uris) {
				try (InputStream in = resolver.openInputStream(uri)) {
					if (in == null) throw new IOException("No input stream");
					count += VoiceNotes.importFile(app, displayName(resolver, uri), in);
				} catch (IOException | RuntimeException e) {
					Log.w(TAG, "Import failed for " + uri, e);
					failed = true;
				}
			}
			String message;
			if (count > 0) message = app.getResources().getQuantityString(R.plurals.msg_import_done, count, count);
			else message = app.getString(failed ? R.string.error_import_failed : R.string.msg_import_none);
			showResult(app, message);
		});
	}

	private void showResult(Context app, String message) {
		mainHandler.post(() -> {
			Toast.makeText(app, message, Toast.LENGTH_LONG).show();
			if (isAdded()) updateVoiceNotesInfo();
		});
	}

	private static String displayName(ContentResolver resolver, Uri uri) {
		try (Cursor c = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
			if (c != null && c.moveToFirst()) return c.getString(0);
		} catch (RuntimeException ignored) {
		}
		return uri.getLastPathSegment();
	}
}
