package com.webkreator.qlue.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps file suffixes to MIME types, using a hard-coded map (borrowed from the
 * Apache httpd project).
 */
public class MimeTypes {

	private Map<String, String> mimeTypes = new HashMap<String, String>();

	private static MimeTypes _instance;

	private MimeTypes() {
	}

	/**
	 * Retrieve MimeTypes instance.
	 * 
	 * @return
	 */
	private synchronized static MimeTypes instance() {
		if (_instance == null) {
			_instance = new MimeTypes();
			_instance.loadMimeTypes();
		}

		return _instance;
	}

	/**
	 * Loads MIME types.
	 */
	private void loadMimeTypes() {
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(
					instance().getClass().getResourceAsStream("mime.types")));

			String line = null;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if ((line.length() == 0) || (line.charAt(0) == '#')) {
					continue;
				}

				String[] tokens = line.split("\\s+");
				if (tokens.length < 2) {
					throw new RuntimeException(
							"Invalid MIME type configuration line: " + line);
				}

				for (int i = 1; i < tokens.length - 1; i++) {
					mimeTypes.put(tokens[i], tokens[0]);
				}
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Returns MIME type for the given suffix.
	 * 
	 * @param suffix
	 * @return MIME type, or null if the suffix could not be mapped.
	 */
	public static String getMimeType(String suffix) {
		return instance().mimeTypes.get(suffix);
	}
}
