package com.appricodes.voicetag;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.PersistableBundle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists what another app stored on a tag and offers the matching actions. Nothing is opened
 * until the user presses a button.
 */
final class TagContentDialog {

	interface Messenger {
		void show(String message, boolean error);
	}

	private TagContentDialog() {}

	/** One spoken/visible line, e.g. "Web address: https://example.com". Wi-Fi passwords are never included. */
	static String describe(Context context, TagContent item) {
		String value = item.value.isEmpty() ? "?" : item.value;
		if (item.kind == TagContent.Kind.CONTACT && item.contactPhone != null) value += ", " + item.contactPhone;
		if (item.title != null) value = item.title + ", " + value;
		return context.getString(R.string.content_line, context.getString(kindLabel(item.kind)), value);
	}

	static void show(Activity activity, List<TagContent> items, Messenger messenger) {
		int padding = dp(activity, 24);
		LinearLayout list = new LinearLayout(activity);
		list.setOrientation(LinearLayout.VERTICAL);
		list.setPadding(padding, dp(activity, 8), padding, 0);
		ScrollView scroll = new ScrollView(activity);
		scroll.addView(list);

		AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.content_title)
				.setView(scroll)
				.setPositiveButton(R.string.action_close, null)
				.create();

		for (TagContent item : items) {
			TextView label = new TextView(activity);
			label.setText(kindLabel(item.kind));
			label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
			label.setPadding(0, dp(activity, 16), 0, 0);
			list.addView(label);

			TextView value = new TextView(activity);
			String shown = item.value.isEmpty() ? "?" : item.value;
			if (item.title != null) shown = item.title + "\n" + shown;
			if (item.kind == TagContent.Kind.CONTACT) {
				if (item.contactPhone != null) shown += "\n" + item.contactPhone;
				if (item.contactEmail != null) shown += "\n" + item.contactEmail;
			}
			value.setText(shown);
			value.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
			value.setTextIsSelectable(true);
			list.addView(value);

			for (Action action : actionsFor(activity, item)) {
				MaterialButton button = new MaterialButton(activity, null,
						com.google.android.material.R.attr.materialButtonTonalStyle);
				button.setText(action.label);
				button.setMinHeight(dp(activity, 56));
				button.setOnClickListener(v -> {
					if (action.intent != null) {
						try {
							activity.startActivity(action.intent);
							dialog.dismiss();
						} catch (ActivityNotFoundException | SecurityException e) {
							messenger.show(activity.getString(R.string.error_no_app), true);
						}
					} else {
						action.run.run();
					}
				});
				LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
				params.topMargin = dp(activity, 8);
				list.addView(button, params);
			}
		}
		dialog.show();
	}

	private static final class Action {
		final @StringRes int label;
		final Intent intent;
		final Runnable run;

		Action(@StringRes int label, Intent intent) {
			this(label, intent, null);
		}

		Action(@StringRes int label, Intent intent, Runnable run) {
			this.label = label;
			this.intent = intent;
			this.run = run;
		}
	}

	private static List<Action> actionsFor(Activity activity, TagContent item) {
		List<Action> actions = new ArrayList<>();
		switch (item.kind) {
			case WEB:
				actions.add(new Action(R.string.action_open_browser,
						new Intent(Intent.ACTION_VIEW, item.uri).addCategory(Intent.CATEGORY_BROWSABLE)));
				break;
			case LINK:
				if (item.canOpenLink()) actions.add(new Action(R.string.action_open, new Intent(Intent.ACTION_VIEW, item.uri)));
				break;
			case PHONE:
				actions.add(new Action(R.string.action_call, new Intent(Intent.ACTION_DIAL, item.uri)));
				break;
			case EMAIL:
				actions.add(new Action(R.string.action_email, new Intent(Intent.ACTION_SENDTO, item.uri)));
				break;
			case SMS:
				actions.add(new Action(R.string.action_sms, new Intent(Intent.ACTION_SENDTO, item.uri)));
				break;
			case LOCATION:
				actions.add(new Action(R.string.action_maps, new Intent(Intent.ACTION_VIEW, item.uri)));
				break;
			case WIFI:
				if (!item.value.isEmpty()) actions.add(new Action(R.string.action_wifi_connect, wifiIntent(item)));
				if (item.wifiKey != null && !item.wifiKey.isEmpty()) {
					actions.add(new Action(R.string.action_copy_password, null,
							() -> copy(activity, item.wifiKey, true)));
				}
				break;
			case CONTACT:
				actions.add(new Action(R.string.action_add_contact, contactIntent(item)));
				break;
			case APP:
				if (!item.value.isEmpty()) {
					actions.add(new Action(R.string.action_play_store, new Intent(Intent.ACTION_VIEW,
							Uri.parse("https://play.google.com/store/apps/details?id=" + Uri.encode(item.value)))));
				}
				break;
			case TEXT:
				if (!item.value.isEmpty()) actions.add(new Action(R.string.action_copy, null, () -> copy(activity, item.value, false)));
				break;
			default:
				break;
		}
		return actions;
	}

	private static Intent wifiIntent(TagContent item) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			try {
				WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder().setSsid(item.value);
				boolean secured = item.wifiKey != null && !item.wifiKey.isEmpty()
						&& item.wifiAuth != TagContent.WIFI_AUTH_OPEN;
				if (secured) builder.setWpa2Passphrase(item.wifiKey);
				ArrayList<WifiNetworkSuggestion> networks = new ArrayList<>();
				networks.add(builder.build());
				// The system asks the user before saving the network.
				return new Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
						.putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, networks);
			} catch (IllegalArgumentException ignored) {
				// Invalid SSID or passphrase: let the user connect manually.
			}
		}
		return new Intent(Settings.ACTION_WIFI_SETTINGS);
	}

	private static Intent contactIntent(TagContent item) {
		Intent intent = new Intent(ContactsContract.Intents.Insert.ACTION)
				.setType(ContactsContract.RawContacts.CONTENT_TYPE);
		if (!item.value.isEmpty()) intent.putExtra(ContactsContract.Intents.Insert.NAME, item.value);
		if (item.contactPhone != null) intent.putExtra(ContactsContract.Intents.Insert.PHONE, item.contactPhone);
		if (item.contactEmail != null) intent.putExtra(ContactsContract.Intents.Insert.EMAIL, item.contactEmail);
		return intent;
	}

	@SuppressWarnings("deprecation")
	private static void copy(Activity activity, String text, boolean sensitive) {
		ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
		if (clipboard == null) return;
		ClipData clip = ClipData.newPlainText(activity.getString(R.string.app_name), text);
		if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			PersistableBundle extras = new PersistableBundle();
			extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
			clip.getDescription().setExtras(extras);
		}
		clipboard.setPrimaryClip(clip);
		View focus = activity.getCurrentFocus();
		if (focus != null) focus.announceForAccessibility(activity.getString(R.string.msg_copied));
	}

	private static @StringRes int kindLabel(TagContent.Kind kind) {
		switch (kind) {
			case WEB: return R.string.kind_web;
			case PHONE: return R.string.kind_phone;
			case EMAIL: return R.string.kind_email;
			case SMS: return R.string.kind_sms;
			case LOCATION: return R.string.kind_location;
			case LINK: return R.string.kind_link;
			case WIFI: return R.string.kind_wifi;
			case CONTACT: return R.string.kind_contact;
			case APP: return R.string.kind_app;
			case TEXT: return R.string.kind_text;
			default: return R.string.kind_unknown;
		}
	}

	private static int dp(Context context, int dp) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
	}
}
