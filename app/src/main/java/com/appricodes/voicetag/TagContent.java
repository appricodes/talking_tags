package com.appricodes.voicetag;

import android.net.Uri;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** A record written by another app (link, phone number, Wi-Fi network...), decoded for display. */
final class TagContent {

	enum Kind { WEB, PHONE, EMAIL, SMS, LOCATION, LINK, WIFI, CONTACT, APP, TEXT, UNKNOWN }

	static final int WIFI_AUTH_OPEN = 0x0001;
	static final int WIFI_AUTH_WPA_PERSONAL = 0x0002;
	static final int WIFI_AUTH_WPA2_PERSONAL = 0x0020;

	private static final String MIME_WIFI = "application/vnd.wfa.wsc";
	private static final byte[] TYPE_ANDROID_APP = "android.com:pkg".getBytes(StandardCharsets.US_ASCII);
	private static final int WSC_CREDENTIAL = 0x100E;
	private static final int WSC_SSID = 0x1045;
	private static final int WSC_NETWORK_KEY = 0x1027;
	private static final int WSC_AUTH_TYPE = 0x1003;

	final Kind kind;
	/** Human-readable main value: address, number, network name, text... */
	final String value;
	/** Optional title, e.g. from a smart poster. */
	String title;
	Uri uri;
	String wifiKey;
	int wifiAuth;
	String contactPhone;
	String contactEmail;

	TagContent(Kind kind, String value) {
		this.kind = kind;
		this.value = value;
	}

	static TagContent fromRecord(NdefRecord record) {
		short tnf = record.getTnf();
		byte[] type = record.getType();

		if (tnf == NdefRecord.TNF_EXTERNAL_TYPE && Arrays.equals(type, TYPE_ANDROID_APP)) {
			return new TagContent(Kind.APP, new String(record.getPayload(), StandardCharsets.US_ASCII).trim());
		}
		if (tnf == NdefRecord.TNF_MIME_MEDIA) {
			String mime = new String(type, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
			if (mime.equals(MIME_WIFI)) return parseWifi(record.getPayload());
			if (mime.equals("text/vcard") || mime.equals("text/x-vcard")) {
				return parseVcard(new String(record.getPayload(), StandardCharsets.UTF_8));
			}
			if (mime.startsWith("text/")) {
				return new TagContent(Kind.TEXT, new String(record.getPayload(), StandardCharsets.UTF_8).trim());
			}
			return new TagContent(Kind.UNKNOWN, mime);
		}
		if (tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_TEXT)) {
			return new TagContent(Kind.TEXT, TagText.parseTextPayload(record.getPayload()).text.trim());
		}

		Uri uri = record.toUri();
		if (uri != null && !"vnd.android.nfc".equals(uri.getScheme())) {
			TagContent content = fromUri(uri);
			if (tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_SMART_POSTER)) {
				content.title = smartPosterTitle(record.getPayload());
			}
			return content;
		}
		return new TagContent(Kind.UNKNOWN, describeType(tnf, type));
	}

	static TagContent fromUri(Uri uri) {
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		String ssp = uri.getSchemeSpecificPart() == null ? "" : uri.getSchemeSpecificPart();
		TagContent content;
		switch (scheme) {
			case "http":
			case "https":
				content = new TagContent(Kind.WEB, uri.toString());
				break;
			case "tel":
				content = new TagContent(Kind.PHONE, ssp);
				break;
			case "mailto":
				content = new TagContent(Kind.EMAIL, beforeQuery(ssp));
				break;
			case "sms":
			case "smsto":
				content = new TagContent(Kind.SMS, beforeQuery(ssp));
				break;
			case "geo":
				content = new TagContent(Kind.LOCATION, ssp);
				break;
			default:
				content = new TagContent(Kind.LINK, uri.toString());
				break;
		}
		content.uri = uri;
		return content;
	}

	/** Links to local files or other apps' internals are shown but never opened. */
	boolean canOpenLink() {
		if (uri == null) return false;
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		return !scheme.isEmpty() && !Arrays.asList("file", "content", "intent", "javascript", "data").contains(scheme);
	}

	/** Parses a Wi-Fi Simple Configuration token (TLVs: 2-byte type, 2-byte length, value). */
	static TagContent parseWifi(byte[] payload) {
		String[] ssid = {null};
		String[] key = {null};
		int[] auth = {0};
		readWsc(payload, 0, payload.length, ssid, key, auth);
		TagContent content = new TagContent(Kind.WIFI, ssid[0] == null ? "" : ssid[0]);
		content.wifiKey = key[0];
		content.wifiAuth = auth[0];
		return content;
	}

	private static void readWsc(byte[] data, int start, int end, String[] ssid, String[] key, int[] auth) {
		ByteBuffer buf = ByteBuffer.wrap(data, start, end - start);
		while (buf.remaining() >= 4) {
			int type = buf.getShort() & 0xFFFF;
			int length = buf.getShort() & 0xFFFF;
			if (length > buf.remaining()) return;
			int valueStart = buf.position();
			if (type == WSC_CREDENTIAL) {
				readWsc(data, valueStart, valueStart + length, ssid, key, auth);
			} else if (type == WSC_SSID) {
				ssid[0] = new String(data, valueStart, length, StandardCharsets.UTF_8);
			} else if (type == WSC_NETWORK_KEY) {
				key[0] = new String(data, valueStart, length, StandardCharsets.UTF_8);
			} else if (type == WSC_AUTH_TYPE && length >= 2) {
				auth[0] = ((data[valueStart] & 0xFF) << 8) | (data[valueStart + 1] & 0xFF);
			}
			buf.position(valueStart + length);
		}
	}

	/** Reads the name, first phone number and first email address of a vCard. */
	static TagContent parseVcard(String vcard) {
		String name = null;
		String fallbackName = null;
		String phone = null;
		String email = null;
		// Lines starting with a space continue the previous line.
		for (String line : vcard.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "").split("\n")) {
			int colon = line.indexOf(':');
			if (colon <= 0) continue;
			String property = line.substring(0, colon).toUpperCase(Locale.ROOT);
			String value = line.substring(colon + 1).trim();
			if (value.isEmpty()) continue;
			String base = property.split(";")[0];
			if (base.equals("FN") && name == null) name = value;
			else if (base.equals("N") && fallbackName == null) fallbackName = joinNameParts(value);
			else if (base.equals("TEL") && phone == null) phone = value;
			else if (base.equals("EMAIL") && email == null) email = value;
		}
		if (name == null) name = fallbackName;
		TagContent content = new TagContent(Kind.CONTACT, name == null ? "" : name);
		content.contactPhone = phone;
		content.contactEmail = email;
		return content;
	}

	/** vCard N is "family;given;additional;prefix;suffix". */
	private static String joinNameParts(String value) {
		String[] parts = value.split(";", -1);
		StringBuilder sb = new StringBuilder();
		int[] order = {3, 1, 2, 0, 4};
		for (int i : order) {
			if (i < parts.length && !parts[i].trim().isEmpty()) {
				if (sb.length() > 0) sb.append(' ');
				sb.append(parts[i].trim());
			}
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	private static String smartPosterTitle(byte[] payload) {
		try {
			for (NdefRecord inner : new NdefMessage(payload).getRecords()) {
				if (inner.getTnf() == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(inner.getType(), NdefRecord.RTD_TEXT)) {
					String title = TagText.parseTextPayload(inner.getPayload()).text.trim();
					return title.isEmpty() ? null : title;
				}
			}
		} catch (FormatException ignored) {
		}
		return null;
	}

	private static String describeType(short tnf, byte[] type) {
		return type == null || type.length == 0 ? "TNF " + tnf : new String(type, StandardCharsets.US_ASCII);
	}

	private static String beforeQuery(String s) {
		int q = s.indexOf('?');
		return q == -1 ? s : s.substring(0, q);
	}
}
