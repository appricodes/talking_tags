package com.appricodes.voicetag;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import com.appricodes.voicetag.databinding.ActivityInfoBinding;
import com.google.android.material.button.MaterialButton;

/** Imprint, privacy summary and open-source credits, opened from Settings → About. */
public class InfoActivity extends AppCompatActivity {

	static final String EXTRA_PAGE = "page";
	static final String PAGE_IMPRINT = "imprint";
	static final String PAGE_PRIVACY = "privacy";
	static final String PAGE_LICENSES = "licenses";

	static final String URL_PROJECT = "https://github.com/appricodes/talking_tags";
	static final String URL_PRIVACY = URL_PROJECT + "/blob/main/PRIVACY.md";
	private static final String URL_APACHE = "https://www.apache.org/licenses/LICENSE-2.0";

	/** Name, licence holder and project page of each bundled library. */
	private static final String[][] LIBRARIES = {
			{"AndroidX (AppCompat, Activity, Core, Preference, ConstraintLayout)",
					"The Android Open Source Project", "https://developer.android.com/jetpack/androidx"},
			{"Material Components for Android", "Google LLC",
					"https://github.com/material-components/material-components-android"},
			{"Material Symbols", "Google LLC", "https://github.com/google/material-design-icons"},
	};

	private ActivityInfoBinding binding;

	static Intent intent(Context context, String page) {
		return new Intent(context, InfoActivity.class).putExtra(EXTRA_PAGE, page);
	}

	/** Opens a web page in the browser; returns false when no browser is installed. */
	static boolean openUrl(Context context, String url) {
		try {
			context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
					.addCategory(Intent.CATEGORY_BROWSABLE));
			return true;
		} catch (ActivityNotFoundException e) {
			Toast.makeText(context, R.string.error_no_app, Toast.LENGTH_LONG).show();
			return false;
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		binding = ActivityInfoBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		setSupportActionBar(binding.toolbar);
		if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		String page = getIntent().getStringExtra(EXTRA_PAGE);
		if (PAGE_PRIVACY.equals(page)) showPrivacy();
		else if (PAGE_LICENSES.equals(page)) showLicenses();
		else showImprint();
	}

	@Override
	public boolean onSupportNavigateUp() {
		finish();
		return true;
	}

	private void showImprint() {
		setTitle(R.string.about_imprint);
		addParagraph(getString(R.string.imprint_intro));
		addEntry(R.string.imprint_operator, getString(R.string.imprint_operator_value));
		addEntry(R.string.imprint_address, getString(R.string.imprint_address_value));
		addEntry(R.string.imprint_contact, getString(R.string.imprint_contact_value));
		addEntry(R.string.imprint_responsible, getString(R.string.imprint_responsible_value));
		addEntry(R.string.imprint_dispute, getString(R.string.imprint_dispute_value));
		addLink(R.string.about_source, URL_PROJECT);
	}

	private void showPrivacy() {
		setTitle(R.string.about_privacy);
		for (String paragraph : getString(R.string.privacy_summary).split("\n\n")) addParagraph(paragraph);
		addLink(R.string.privacy_full_link, URL_PRIVACY);
		addLink(R.string.about_imprint, null);
	}

	private void showLicenses() {
		setTitle(R.string.about_licenses);
		addParagraph(getString(R.string.licenses_intro));
		for (String[] library : LIBRARIES) {
			addEntry(library[0], "© " + library[1] + "\nApache License 2.0\n" + library[2]);
		}
		addLink(R.string.licenses_view, URL_APACHE);
		addLink(R.string.about_source, URL_PROJECT);
	}

	private void addParagraph(String text) {
		TextView view = new TextView(this);
		view.setText(text);
		view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
		view.setPadding(0, 0, 0, dp(16));
		binding.content.addView(view);
	}

	private void addEntry(@StringRes int label, String value) {
		addEntry(getString(label), value);
	}

	/** A heading and its value, read by TalkBack as one item. */
	private void addEntry(String label, String value) {
		LinearLayout group = new LinearLayout(this);
		group.setOrientation(LinearLayout.VERTICAL);
		group.setFocusable(true);
		group.setPadding(0, 0, 0, dp(16));

		TextView heading = new TextView(this);
		heading.setText(label);
		heading.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
		ViewCompat.setAccessibilityHeading(heading, true);
		group.addView(heading);

		TextView body = new TextView(this);
		body.setText(value);
		body.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
		body.setTextIsSelectable(true);
		group.addView(body);

		group.setContentDescription(label + ". " + value);
		binding.content.addView(group);
	}

	/** A button opening {@code url}, or the imprint page when {@code url} is null. */
	private void addLink(@StringRes int label, String url) {
		MaterialButton button = new MaterialButton(this, null,
				com.google.android.material.R.attr.materialButtonOutlinedStyle);
		button.setText(label);
		button.setMinHeight(dp(56));
		button.setOnClickListener(v -> {
			if (url == null) startActivity(intent(this, PAGE_IMPRINT));
			else openUrl(this, url);
		});
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		params.bottomMargin = dp(8);
		binding.content.addView(button, params);
	}

	private int dp(int value) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
	}
}
