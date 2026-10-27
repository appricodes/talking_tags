package com.appricodes.voicetag;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/** Parsing helpers for NDEF text records, including the old "text|yyyy-MM-dd HH:mm" format. */
final class TagText {

	private static final Pattern LEGACY_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
	private static final String LEGACY_DATE_FORMAT = "yyyy-MM-dd HH:mm";

	private TagText() {}

	static final class Parsed {
		final String language;
		final String text;

		Parsed(String language, String text) {
			this.language = language;
			this.text = text;
		}
	}

	static final class Legacy {
		final String text;
		final Long writtenAtMillis;

		Legacy(String text, Long writtenAtMillis) {
			this.text = text;
			this.writtenAtMillis = writtenAtMillis;
		}
	}

	/** Decodes an RTD_TEXT payload: status byte, language code, then UTF-8 or UTF-16 text. */
	static Parsed parseTextPayload(byte[] payload) {
		if (payload == null || payload.length == 0) return new Parsed(null, "");
		boolean utf16 = (payload[0] & 0x80) != 0;
		int languageLength = payload[0] & 0x3F;
		if (1 + languageLength > payload.length) {
			return new Parsed(null, new String(payload, StandardCharsets.UTF_8));
		}
		String language = languageLength == 0 ? null
				: new String(payload, 1, languageLength, StandardCharsets.US_ASCII);
		int textStart = 1 + languageLength;
		String text = new String(payload, textStart, payload.length - textStart,
				utf16 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8);
		return new Parsed(language, text);
	}

	/**
	 * Splits the date suffix written by version 1.1. That version formatted the date with the
	 * device locale, so Persian and Arabic digits are accepted too.
	 */
	static Legacy splitLegacy(String raw) {
		int separator = raw.lastIndexOf('|');
		if (separator == -1) return new Legacy(raw, null);
		String suffix = normalizeDigits(raw.substring(separator + 1));
		if (!LEGACY_DATE.matcher(suffix).matches()) return new Legacy(raw, null);
		try {
			Date date = new SimpleDateFormat(LEGACY_DATE_FORMAT, Locale.US).parse(suffix);
			return new Legacy(raw.substring(0, separator), date == null ? null : date.getTime());
		} catch (ParseException e) {
			return new Legacy(raw, null);
		}
	}

	/** Replaces Persian (U+06F0..) and Arabic-Indic (U+0660..) digits with ASCII digits. */
	static String normalizeDigits(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c >= '۰' && c <= '۹') c = (char) ('0' + (c - '۰'));
			else if (c >= '٠' && c <= '٩') c = (char) ('0' + (c - '٠'));
			sb.append(c);
		}
		return sb.toString();
	}
}
