package com.appricodes.voicetag;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.icu.text.DateFormat;
import android.icu.util.ULocale;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;
import androidx.preference.PreferenceManager;

import com.appricodes.voicetag.databinding.ActivityMainBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Workflow: hold a tag to read it (SHOWING) → Edit (COMPOSE) → Write tag → hold the same tag
 * again (WAIT_WRITE). Erase and Lock also confirm first and then wait for the same tag.
 * Cancel and Back always go one step back.
 */
public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {

	private static final String TAG = "VoiceTag";
	private static final int READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A
			| NfcAdapter.FLAG_READER_NFC_B
			| NfcAdapter.FLAG_READER_NFC_F
			| NfcAdapter.FLAG_READER_NFC_V;
	/** Rough TalkBack speaking time, used to start a voice note after the text was read out. */
	private static final long TALKBACK_DELAY_MS = 800;
	private static final long TALKBACK_MS_PER_CHAR = 70;

	private enum State { IDLE, SHOWING, COMPOSE, WAIT_WRITE, WAIT_ERASE, WAIT_LOCK }

	private ActivityMainBinding binding;
	private NfcAdapter nfcAdapter;
	private Feedback feedback;
	private final ExecutorService tagExecutor = Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final VoiceRecorder recorder = new VoiceRecorder();
	private MediaPlayer mediaPlayer;

	private State state = State.IDLE;
	/** A tag operation is running on the executor; new tag events are ignored. */
	private boolean busy;
	private TagData currentTag;
	/** Last discovered Tag object of the current tag; it may still be on the phone. */
	private Tag lastSeenTag;

	// Compose state
	private File pendingRecording;
	private boolean removeVoice;
	private boolean eraseVoiceToo;
	private Runnable pendingPlayback;

	private final Runnable recordingTicker = new Runnable() {
		@Override
		public void run() {
			if (!recorder.isRecording()) return;
			updateVoiceUi();
			handler.postDelayed(this, 500);
		}
	};

	private final OnBackPressedCallback backCallback = new OnBackPressedCallback(false) {
		@Override
		public void handleOnBackPressed() {
			cancelCurrentStep();
		}
	};

	private final BroadcastReceiver nfcStateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			updateNfcState();
		}
	};

	private final ActivityResultLauncher<Intent> speechLauncher = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(), this::onSpeechResult);

	private final ActivityResultLauncher<String> micPermissionLauncher = registerForActivityResult(
			new ActivityResultContracts.RequestPermission(), granted -> {
				if (granted) startRecording();
				else say(getString(R.string.error_mic_permission), true);
			});

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		binding = ActivityMainBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		setSupportActionBar(binding.toolbar);

		feedback = new Feedback(this);
		getOnBackPressedDispatcher().addCallback(this, backCallback);
		setupButtons();

		nfcAdapter = NfcAdapter.getDefaultAdapter(this);
		if (nfcAdapter == null) {
			binding.tvStatus.setText(R.string.error_nfc_not_supported);
			return;
		}
		render();
		handleNfcIntent(getIntent());
	}

	private void setupButtons() {
		binding.btnNfcSettings.setOnClickListener(v -> openNfcSettings());
		binding.btnReplay.setOnClickListener(v -> presentContent(currentTag, true));
		binding.btnShowContents.setOnClickListener(v -> showContentsDialog(currentTag));
		binding.btnEdit.setOnClickListener(v -> enterCompose());
		binding.btnErase.setOnClickListener(v -> confirmErase());
		binding.btnLock.setOnClickListener(v -> confirmLock());
		binding.btnDictate.setOnClickListener(v -> startDictation());
		binding.btnClearText.setOnClickListener(v -> clearText());
		binding.btnRecord.setOnClickListener(v -> toggleRecording());
		binding.btnPlayVoice.setOnClickListener(v -> {
			File voice = composeVoiceFile();
			if (voice != null) playVoice(voice);
		});
		binding.btnRemoveVoice.setOnClickListener(v -> removeComposeVoice());
		binding.btnSave.setOnClickListener(v -> onSavePressed());
		binding.btnCancel.setOnClickListener(v -> cancelCurrentStep());
		binding.etTagInput.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) { }

			@Override
			public void afterTextChanged(Editable s) {
				updateCapacity();
			}
		});
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.menu_main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		if (item.getItemId() == R.id.action_settings) {
			startActivity(new Intent(this, SettingsActivity.class));
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onResume() {
		super.onResume();
		feedback.refresh();
		if (nfcAdapter != null) {
			ContextCompat.registerReceiver(this, nfcStateReceiver,
					new IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
					ContextCompat.RECEIVER_NOT_EXPORTED);
			updateNfcState();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (nfcAdapter != null) {
			unregisterReceiver(nfcStateReceiver);
			nfcAdapter.disableReaderMode(this);
		}
		if (recorder.isRecording()) stopRecording();
		cancelPendingPlayback();
		stopPlayback();
		feedback.stop();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		handler.removeCallbacksAndMessages(null);
		recorder.cancel();
		discardPendingRecording();
		stopPlayback();
		feedback.shutdown();
		tagExecutor.shutdownNow();
	}

	@Override
	protected void onNewIntent(@NonNull Intent intent) {
		super.onNewIntent(intent);
		handleNfcIntent(intent);
	}

	/** The app was opened by holding one of its tags to the phone. */
	private void handleNfcIntent(Intent intent) {
		if (intent == null) return;
		Tag tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag.class);
		if (tag == null) return;
		intent.removeExtra(NfcAdapter.EXTRA_TAG);
		onTag(tag);
	}

	@Override
	public void onTagDiscovered(Tag tag) {
		runOnUiThread(() -> onTag(tag));
	}

	private void onTag(Tag tag) {
		if (busy || isFinishing() || isDestroyed()) return;
		switch (state) {
			case IDLE:
			case SHOWING:
				readTag(tag);
				break;
			case COMPOSE:
				if (currentTag.isSameTag(tag)) lastSeenTag = tag;
				else say(getString(R.string.status_finish_editing), true);
				break;
			default:
				if (!currentTag.isSameTag(tag)) {
					say(getString(R.string.status_wrong_tag), true);
					return;
				}
				lastSeenTag = tag;
				runPendingOperation(tag, false);
				break;
		}
	}

	// region NFC state

	private void updateNfcState() {
		boolean enabled = nfcAdapter.isEnabled();
		binding.cardNfcOff.setVisibility(enabled ? View.GONE : View.VISIBLE);
		if (enabled) nfcAdapter.enableReaderMode(this, this, READER_FLAGS, null);
		if (state == State.IDLE) {
			binding.tvStatus.setText(enabled ? R.string.status_scan_nfc : R.string.status_nfc_off);
		}
	}

	private void openNfcSettings() {
		try {
			startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
		} catch (ActivityNotFoundException e) {
			startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
		}
	}

	// endregion

	// region Reading and showing a tag

	private void readTag(Tag tag) {
		busy = true;
		cancelPendingPlayback();
		stopPlayback();
		feedback.stop();
		tagExecutor.execute(() -> {
			TagData data = TagIo.read(tag);
			runOnUiThread(() -> {
				busy = false;
				if (isDestroyed()) return;
				lastSeenTag = tag;
				feedback.vibrateTick();
				showTag(data, true);
			});
		});
	}

	/** Shows a tag; {@code present} also reads it aloud and plays its voice note. */
	private void showTag(TagData data, boolean present) {
		currentTag = data;
		state = State.SHOWING;
		render();

		File voice = VoiceNotes.find(this, data);
		boolean hasContent = !data.text.isEmpty() || voice != null;

		binding.tvTagContent.setText(data.text);
		binding.tvTagContent.setVisibility(data.text.isEmpty() ? View.GONE : View.VISIBLE);

		List<String> info = new ArrayList<>();
		if (data.writtenAtMillis != null) info.add(getString(R.string.date_prefix, formatDate(data.writtenAtMillis)));
		if (voice != null) info.add(getString(R.string.info_voice_note));
		else if (data.audioId != null) info.add(getString(R.string.info_voice_missing));
		if (data.hasForeignData()) info.add(getString(R.string.info_foreign_data));
		if (data.ndef && !data.writable) info.add(getString(R.string.info_locked));
		if (!data.ndef && !data.formatable) info.add(getString(R.string.info_not_ndef));
		if (data.maxSize > 0) info.add(getString(R.string.info_capacity, data.maxSize));
		binding.tvTagInfo.setText(TextUtils.join("\n", info));
		binding.tvTagInfo.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);

		binding.btnReplay.setVisibility(hasContent || data.hasForeignData() ? View.VISIBLE : View.GONE);
		binding.btnShowContents.setVisibility(data.hasForeignData() ? View.VISIBLE : View.GONE);
		binding.btnEdit.setText(hasContent ? R.string.btn_edit : R.string.btn_add_content);
		binding.btnErase.setVisibility(data.ndef && data.writable && data.hasStoredData() ? View.VISIBLE : View.GONE);
		binding.btnLock.setVisibility(data.canLock && data.hasStoredData() ? View.VISIBLE : View.GONE);

		boolean empty = !hasContent && !data.hasForeignData();
		binding.tvStatus.setText(empty ? R.string.status_empty_tag : R.string.status_tag_read);
		if (present) {
			presentContent(data, false);
			if (data.hasForeignData()) showContentsDialog(data);
		}
	}

	/** Reads the text aloud, then plays the voice note (always when {@code forcePlay}). */
	private void presentContent(TagData data, boolean forcePlay) {
		if (data == null) return;
		cancelPendingPlayback();
		stopPlayback();

		File voice = VoiceNotes.find(this, data);
		boolean autoplay = PreferenceManager.getDefaultSharedPreferences(this)
				.getBoolean(SettingsFragment.PREF_AUTOPLAY, true);
		Runnable play = voice != null && (forcePlay || autoplay) ? () -> playVoice(voice) : null;

		List<String> spoken = new ArrayList<>();
		if (!data.text.isEmpty()) spoken.add(data.text);
		for (TagContent item : data.foreign) spoken.add(TagContentDialog.describe(this, item));
		
		if (!spoken.isEmpty()) {
			speakContent(TextUtils.join(". ", spoken), data.text.isEmpty() ? null : data.language, play);
		} else if (play != null) {
			play.run();
		} else {
			announce(getString(R.string.status_empty_tag));
		}
	}
	
	/** Other apps' records are only described; the user chooses whether to open them. */
	private void showContentsDialog(TagData data) {
		if (data == null || !data.hasForeignData()) return;
		TagContentDialog.show(this, data.foreign, this::say);
	}

	@SuppressWarnings("deprecation")
	private void speakContent(String text, String language, Runnable then) {
		if (feedback.canSpeak()) {
			feedback.speak(text, language, then);
			return;
		}
		boolean screenReader = Feedback.isScreenReaderOn(this);
		if (screenReader) binding.tvTagContent.announceForAccessibility(text);
		if (then != null) {
			pendingPlayback = then;
			handler.postDelayed(then, screenReader ? TALKBACK_DELAY_MS + text.length() * TALKBACK_MS_PER_CHAR : 0);
		}
	}

	private String formatDate(long millis) {
		// ICU picks the locale's calendar, e.g. the Persian calendar for fa-IR.
		DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, ULocale.getDefault());
		return format.format(new Date(millis));
	}

	// endregion

	// region Compose

	private void enterCompose() {
		cancelPendingPlayback();
		stopPlayback();
		feedback.stop();
		state = State.COMPOSE;
		discardPendingRecording();
		removeVoice = false;

		boolean writable = currentTag.writable;
		binding.etTagInput.setText(currentTag.text);
		binding.etTagInput.setSelection(binding.etTagInput.length());
		binding.tilTagInput.setEnabled(writable);
		binding.btnDictate.setVisibility(writable ? View.VISIBLE : View.GONE);
		binding.btnClearText.setVisibility(writable ? View.VISIBLE : View.GONE);
		binding.btnSave.setText(writable ? R.string.btn_write_tag : R.string.btn_save_voice);
		render();
		updateCapacity();
		updateVoiceUi();

		binding.tvStatus.setText(writable ? R.string.status_enter_text : R.string.status_voice_only);
		boolean blank = currentTag.text.isEmpty() && VoiceNotes.find(this, currentTag) == null;
		if (writable && blank) startDictation();
		else announce(binding.tvStatus.getText().toString());
	}

	private String composeText() {
		Editable text = binding.etTagInput.getText();
		return text == null ? "" : text.toString().trim();
	}

	/** Language code of the text record: the dictation language, else the device language. */
	private String textLanguage() {
		String code = PreferenceManager.getDefaultSharedPreferences(this)
				.getString(SettingsFragment.PREF_LANGUAGE, SettingsFragment.LANGUAGE_AUTO);
		String language = code == null || SettingsFragment.LANGUAGE_AUTO.equals(code)
				? Locale.getDefault().getLanguage()
				: Locale.forLanguageTag(code).getLanguage();
		return language.isEmpty() ? "en" : language;
	}

	private void updateCapacity() {
		if (state != State.COMPOSE || currentTag == null) return;
		if (!currentTag.writable || currentTag.maxSize <= 0) {
			binding.tilTagInput.setError(null);
			binding.tilTagInput.setHelperText(null);
			return;
		}
		int used = TagIo.textSize(composeText(), textLanguage());
		if (used > currentTag.maxSize) {
			binding.tilTagInput.setError(getString(R.string.error_text_too_long));
		} else {
			binding.tilTagInput.setError(null);
			binding.tilTagInput.setHelperText(getString(R.string.text_capacity, used, currentTag.maxSize));
		}
	}

	/** The voice note the tag will have if the user saves now. */
	private File composeVoiceFile() {
		if (pendingRecording != null) return pendingRecording;
		return removeVoice ? null : VoiceNotes.find(this, currentTag);
	}

	private boolean wantsVoice() {
		return pendingRecording != null
				|| (!removeVoice && (VoiceNotes.find(this, currentTag) != null || currentTag.audioId != null));
	}

	private void updateVoiceUi() {
		if (recorder.isRecording()) {
			long seconds = recorder.elapsedMillis() / 1000;
			String time = String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
			binding.tvVoiceState.setText(getString(R.string.voice_recording, time));
			binding.btnRecord.setText(R.string.btn_stop_recording);
			binding.btnPlayVoice.setVisibility(View.GONE);
			binding.btnRemoveVoice.setVisibility(View.GONE);
			binding.btnSave.setEnabled(false);
			return;
		}
		binding.btnSave.setEnabled(true);
		File voice = composeVoiceFile();
		boolean linkedButMissing = voice == null && !removeVoice && currentTag.audioId != null;
		if (voice != null) {
			binding.tvVoiceState.setText(pendingRecording != null ? R.string.voice_new : R.string.voice_saved);
			binding.btnRecord.setText(R.string.btn_record_again);
		} else {
			binding.tvVoiceState.setText(linkedButMissing ? R.string.voice_missing : R.string.voice_none);
			binding.btnRecord.setText(R.string.btn_record);
		}
		binding.btnPlayVoice.setVisibility(voice != null ? View.VISIBLE : View.GONE);
		binding.btnRemoveVoice.setVisibility(voice != null || linkedButMissing ? View.VISIBLE : View.GONE);
	}

	private void startDictation() {
		Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
		intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
		String code = PreferenceManager.getDefaultSharedPreferences(this)
				.getString(SettingsFragment.PREF_LANGUAGE, SettingsFragment.LANGUAGE_AUTO);
		if (code != null && !code.trim().isEmpty() && !SettingsFragment.LANGUAGE_AUTO.equals(code)) {
			intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, code);
		}
		intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.prompt_speech_input));
		feedback.stop();
		stopPlayback();
		try {
			speechLauncher.launch(intent);
		} catch (ActivityNotFoundException e) {
			say(getString(R.string.error_speech_not_supported), true);
		}
	}

	private void onSpeechResult(ActivityResult result) {
		if (state != State.COMPOSE) return;
		Intent data = result.getData();
		if (result.getResultCode() != RESULT_OK || data == null) return;
		ArrayList<String> texts = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
		if (texts == null || texts.isEmpty()) return;
		String spoken = texts.get(0).trim();
		if (spoken.isEmpty()) return;

		// Insert at the cursor so dictation can add to existing text.
		Editable text = binding.etTagInput.getText();
		if (text == null) return;
		int at = Math.max(0, binding.etTagInput.getSelectionEnd());
		String insert = at > 0 && !Character.isWhitespace(text.charAt(at - 1)) ? " " + spoken : spoken;
		text.insert(at, insert);
		binding.etTagInput.setSelection(at + insert.length());
		announce(spoken);
	}

	private void clearText() {
		binding.etTagInput.setText("");
		binding.etTagInput.requestFocus();
		announce(getString(R.string.msg_text_cleared));
	}

	private void toggleRecording() {
		if (recorder.isRecording()) {
			stopRecording();
		} else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
				== PackageManager.PERMISSION_GRANTED) {
			startRecording();
		} else {
			micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
		}
	}

	private void startRecording() {
		if (state != State.COMPOSE) return;
		stopPlayback();
		feedback.stop();
		try {
			File out = File.createTempFile("recording", VoiceNotes.EXTENSION, getCacheDir());
			recorder.start(this, out, () -> {
				stopRecording();
				say(getString(R.string.msg_recording_max), false);
			});
		} catch (IOException e) {
			Log.w(TAG, "Recording failed to start", e);
			say(getString(R.string.error_recording), true);
			return;
		}
		// Vibrate only: speech would end up in the recording.
		feedback.vibrateTick();
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		handler.post(recordingTicker);
		updateVoiceUi();
	}

	private void stopRecording() {
		File out = recorder.stop();
		handler.removeCallbacks(recordingTicker);
		getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		if (out != null) {
			discardPendingRecording();
			pendingRecording = out;
			removeVoice = false;
			feedback.vibrateSuccess();
			updateVoiceUi();
			announce(getString(R.string.voice_new));
		} else {
			updateVoiceUi();
			say(getString(R.string.error_recording), true);
		}
	}

	private void removeComposeVoice() {
		stopPlayback();
		discardPendingRecording();
		removeVoice = true;
		updateVoiceUi();
		announce(getString(R.string.voice_none));
	}

	private void discardPendingRecording() {
		if (pendingRecording != null) {
			//noinspection ResultOfMethodCallIgnored
			pendingRecording.delete();
			pendingRecording = null;
		}
	}

	private void onSavePressed() {
		if (recorder.isRecording()) stopRecording();
		if (!currentTag.writable) {
			saveVoiceOnly();
			return;
		}
		String text = composeText();
		if (text.isEmpty() && !wantsVoice()) {
			say(getString(R.string.error_nothing_to_write), true);
			return;
		}
		if (currentTag.maxSize > 0 && TagIo.textSize(text, textLanguage()) > currentTag.maxSize) {
			say(getString(R.string.error_text_too_long), true);
			return;
		}
		enterWaitState(State.WAIT_WRITE, R.string.status_hold_to_write);
	}

	/** Locked or non-NDEF tags: the voice note is linked without writing the tag. */
	private void saveVoiceOnly() {
		if (pendingRecording == null && !removeVoice) {
			showTag(currentTag, false);
			return;
		}
		String key = currentTag.audioId != null
				? VoiceNotes.keyForAudioId(currentTag.audioId)
				: VoiceNotes.keyForUid(currentTag.uid);
		if (key == null) {
			say(getString(R.string.error_voice_link), true);
			return;
		}
		boolean keep = wantsVoice();
		applyVoiceChanges(currentTag, key, keep);
		feedback.vibrateSuccess();
		showTag(currentTag, false);
		say(getString(keep ? R.string.msg_voice_saved : R.string.voice_none), false);
	}

	/**
	 * After a successful save: stores the new recording under {@code key}, moves an existing
	 * note there if its name changed, or deletes it when {@code keep} is false.
	 */
	private void applyVoiceChanges(TagData before, String key, boolean keep) {
		File existing = VoiceNotes.find(this, before);
		if (!keep) {
			if (existing != null) {
				//noinspection ResultOfMethodCallIgnored
				existing.delete();
			}
			discardPendingRecording();
			return;
		}
		if (key == null) {
			discardPendingRecording();
			return;
		}
		File target = VoiceNotes.file(this, key);
		if (pendingRecording != null) {
			boolean moved = VoiceNotes.move(pendingRecording, target);
			pendingRecording = null;
			if (!moved) {
				say(getString(R.string.error_recording), true);
				return;
			}
			if (existing != null && !existing.equals(target)) {
				//noinspection ResultOfMethodCallIgnored
				existing.delete();
			}
		} else if (existing != null && !existing.equals(target)) {
			VoiceNotes.move(existing, target);
		}
	}

	// endregion

	// region Waiting for the tag

	private void enterWaitState(State waitState, @StringRes int statusRes) {
		feedback.stop();
		stopPlayback();
		state = waitState;
		render();
		binding.tvStatus.setText(statusRes);
		// The tag may still be lying on the phone, in which case no new discovery event comes.
		if (lastSeenTag != null && currentTag.isSameTag(lastSeenTag)) {
			runPendingOperation(lastSeenTag, true);
		} else {
			announce(getString(statusRes));
		}
	}

	private void runPendingOperation(Tag tag, boolean quiet) {
		switch (state) {
			case WAIT_WRITE:
				performWrite(tag, quiet);
				break;
			case WAIT_ERASE:
				performErase(tag, quiet);
				break;
			case WAIT_LOCK:
				performLock(tag, quiet);
				break;
			default:
				break;
		}
	}

	private void performWrite(Tag tag, boolean quiet) {
		final TagData before = currentTag;
		final String text = composeText();
		final String language = textLanguage();
		final boolean keepVoice = wantsVoice();
		File existing = VoiceNotes.find(this, before);
		final Long audioId;
		if (!keepVoice) audioId = null;
		else if (before.audioId != null) audioId = before.audioId;
		else if (existing != null && VoiceNotes.isUidFile(existing)) audioId = null;
		else audioId = VoiceNotes.newAudioId();

		busy = true;
		binding.tvStatus.setText(R.string.status_writing);
		tagExecutor.execute(() -> {
			try {
				TagIo.Plan plan = TagIo.plan(text, language, System.currentTimeMillis(), audioId, before.maxSize);
				if (plan == null) throw new TagIo.TagException(TagIo.Failure.TOO_LARGE);
				TagIo.write(tag, plan.message);
				TagData after = TagIo.read(tag, plan.message);
				runOnUiThread(() -> onWriteDone(before, after, plan, audioId, keepVoice));
			} catch (TagIo.TagException e) {
				Log.w(TAG, "Write failed", e);
				runOnUiThread(() -> onOperationFailed(e.failure, quiet,
						R.string.error_write_failed, R.string.status_hold_to_write));
			}
		});
	}

	private void onWriteDone(TagData before, TagData after, TagIo.Plan plan, Long audioId, boolean keepVoice) {
		busy = false;
		if (isDestroyed()) return;
		String key = null;
		if (keepVoice) {
			key = plan.includesAudio ? VoiceNotes.keyForAudioId(audioId) : VoiceNotes.keyForUid(before.uid);
		}
		applyVoiceChanges(before, key, keepVoice);
		feedback.vibrateSuccess();
		showTag(after, false);
		boolean linked = !keepVoice || key != null;
		say(getString(linked ? R.string.msg_write_success : R.string.error_voice_link), !linked);
	}

	private void performErase(Tag tag, boolean quiet) {
		final TagData before = currentTag;
		final boolean deleteVoice = eraseVoiceToo;
		busy = true;
		binding.tvStatus.setText(R.string.status_writing);
		tagExecutor.execute(() -> {
			try {
				TagData after = TagIo.read(tag, TagIo.erase(tag));
				runOnUiThread(() -> {
					busy = false;
					if (isDestroyed()) return;
					File voice = VoiceNotes.find(this, before);
					if (voice != null) {
						if (deleteVoice) {
							//noinspection ResultOfMethodCallIgnored
							voice.delete();
						} else {
							// The id on the tag is gone; keep the note reachable by serial number.
							String uidKey = VoiceNotes.keyForUid(before.uid);
							if (uidKey != null) VoiceNotes.move(voice, VoiceNotes.file(this, uidKey));
						}
					}
					feedback.vibrateSuccess();
					showTag(after, false);
					say(getString(R.string.msg_tag_deleted), false);
				});
			} catch (TagIo.TagException e) {
				Log.w(TAG, "Erase failed", e);
				runOnUiThread(() -> onOperationFailed(e.failure, quiet,
						R.string.error_delete_failed, R.string.status_scan_delete));
			}
		});
	}

	private void performLock(Tag tag, boolean quiet) {
		final TagData before = currentTag;
		busy = true;
		binding.tvStatus.setText(R.string.status_writing);
		tagExecutor.execute(() -> {
			try {
				TagIo.lock(tag);
				runOnUiThread(() -> {
					busy = false;
					if (isDestroyed()) return;
					before.writable = false;
					before.canLock = false;
					feedback.vibrateSuccess();
					showTag(before, false);
					say(getString(R.string.msg_locked), false);
				});
			} catch (TagIo.TagException e) {
				Log.w(TAG, "Lock failed", e);
				runOnUiThread(() -> onOperationFailed(e.failure, quiet,
						R.string.error_lock_failed, R.string.status_hold_to_lock));
			}
		});
	}

	/**
	 * Lost tags and I/O errors keep waiting so the user can simply hold the tag again; a quiet
	 * attempt (tag possibly no longer there) only repeats the instruction.
	 */
	private void onOperationFailed(TagIo.Failure failure, boolean quiet,
								   @StringRes int failedRes, @StringRes int holdRes) {
		busy = false;
		if (isDestroyed()) return;
		switch (failure) {
			case TOO_LARGE:
				state = State.COMPOSE;
				render();
				binding.tvStatus.setText(R.string.status_enter_text);
				say(getString(R.string.error_too_large), true);
				break;
			case READ_ONLY:
				currentTag.writable = false;
				currentTag.canLock = false;
				showTag(currentTag, false);
				say(getString(R.string.error_read_only), true);
				break;
			case NOT_SUPPORTED:
				if (state == State.WAIT_WRITE) {
					state = State.COMPOSE;
					render();
					binding.tvStatus.setText(R.string.status_enter_text);
				} else {
					showTag(currentTag, false);
				}
				say(getString(R.string.error_not_supported), true);
				break;
			default:
				if (quiet) {
					say(getString(holdRes), false);
				} else {
					say(getString(failure == TagIo.Failure.TAG_LOST ? R.string.error_tag_lost : failedRes), true);
				}
				break;
		}
	}

	private void confirmErase() {
		feedback.stop();
		stopPlayback();
		File voice = VoiceNotes.find(this, currentTag);
		eraseVoiceToo = voice != null;
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.confirm_erase_title)
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.btn_erase,
						(d, w) -> enterWaitState(State.WAIT_ERASE, R.string.status_scan_delete));
		if (voice != null) {
			builder.setMultiChoiceItems(new CharSequence[] {getString(R.string.confirm_erase_voice)},
					new boolean[] {true}, (d, which, checked) -> eraseVoiceToo = checked);
		}
		builder.show();
	}

	private void confirmLock() {
		feedback.stop();
		stopPlayback();
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.confirm_lock_title)
				.setMessage(R.string.confirm_lock_message)
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.btn_lock_confirm,
						(d, w) -> enterWaitState(State.WAIT_LOCK, R.string.status_hold_to_lock))
				.show();
	}

	// endregion

	// region Navigation and output

	private void cancelCurrentStep() {
		if (busy) return;
		switch (state) {
			case SHOWING:
				cancelPendingPlayback();
				stopPlayback();
				feedback.stop();
				currentTag = null;
				lastSeenTag = null;
				state = State.IDLE;
				render();
				updateNfcState();
				break;
			case COMPOSE:
				recorder.cancel();
				handler.removeCallbacks(recordingTicker);
				getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
				discardPendingRecording();
				stopPlayback();
				showTag(currentTag, false);
				break;
			case WAIT_WRITE:
				state = State.COMPOSE;
				render();
				binding.tvStatus.setText(R.string.status_enter_text);
				break;
			case WAIT_ERASE:
			case WAIT_LOCK:
				showTag(currentTag, false);
				break;
			default:
				return;
		}
		announce(binding.tvStatus.getText().toString());
	}

	private void render() {
		binding.layoutTag.setVisibility(state == State.SHOWING ? View.VISIBLE : View.GONE);
		binding.layoutCompose.setVisibility(state == State.COMPOSE ? View.VISIBLE : View.GONE);
		boolean canGoBack = state != State.IDLE && state != State.SHOWING;
		binding.btnCancel.setVisibility(canGoBack ? View.VISIBLE : View.GONE);
		backCallback.setEnabled(state != State.IDLE);
	}

	/** Shows a status message and has it spoken. */
	private void say(String message, boolean error) {
		binding.tvStatus.setText(message);
		if (error) feedback.vibrateError();
		announce(message);
	}

	/** Status messages go to TalkBack when it runs, otherwise to the chosen TTS engine. */
	@SuppressWarnings("deprecation")
	private void announce(String message) {
		if (Feedback.isScreenReaderOn(this)) binding.tvStatus.announceForAccessibility(message);
		else feedback.speak(message, null, null);
	}

	private void playVoice(File file) {
		stopPlayback();
		feedback.stop();
		MediaPlayer player = new MediaPlayer();
		try {
			player.setAudioAttributes(new AudioAttributes.Builder()
					.setUsage(AudioAttributes.USAGE_MEDIA)
					.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
					.build());
			player.setDataSource(file.getAbsolutePath());
			player.setOnCompletionListener(mp -> stopPlayback());
			player.prepare();
			player.start();
			mediaPlayer = player;
		} catch (IOException | RuntimeException e) {
			Log.w(TAG, "Playback failed", e);
			player.release();
			say(getString(R.string.error_audio_play), true);
		}
	}

	private void stopPlayback() {
		if (mediaPlayer == null) return;
		try {
			mediaPlayer.stop();
		} catch (IllegalStateException ignored) {
		}
		mediaPlayer.release();
		mediaPlayer = null;
	}

	private void cancelPendingPlayback() {
		if (pendingPlayback != null) {
			handler.removeCallbacks(pendingPlayback);
			pendingPlayback = null;
		}
	}

	// endregion
}
