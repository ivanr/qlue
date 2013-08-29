package com.webkreator.qlue.util;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

public class BearerToken {

	private static final int TOKEN_LENGTH = 16;

	private byte[] tokenBytes;

	public BearerToken() {
		Random random = new SecureRandom();
		tokenBytes = new byte[TOKEN_LENGTH];
		random.nextBytes(tokenBytes);
	}

	public String getUnmaskedToken() {
		return Hex.encodeHexString(tokenBytes);
	}

	public static String unmaskTokenAsString(String maskedToken) {
		byte[] unmaskedBytes = unmaskToken(maskedToken);
		if (unmaskedBytes == null) {
			return null;
		}

		return Hex.encodeHexString(unmaskedBytes);
	}

	public static byte[] unmaskToken(String maskedToken) {
		// Decode hex-encoded string back into bytes.
		byte[] maskedTokenBytes;

		try {
			maskedTokenBytes = Hex.decodeHex(maskedToken.toCharArray());
		} catch (DecoderException e) {
			return null;
		}

		if (maskedTokenBytes.length != 2 * TOKEN_LENGTH) {
			return null;
		}

		byte[] unmaskedBytes = new byte[TOKEN_LENGTH];

		// Remove the mask.
		for (int i = 0; i < TOKEN_LENGTH; i++) {
			unmaskedBytes[i] = (byte) (maskedTokenBytes[i] ^ maskedTokenBytes[i
					+ TOKEN_LENGTH]);
		}

		return unmaskedBytes;
	}

	public String getMaskedToken() {
		// Generate a batch of random bytes every time this method is called.
		Random random = new SecureRandom();
		byte[] randomBytes = new byte[TOKEN_LENGTH];
		random.nextBytes(randomBytes);

		// Put the random bytes first.
		byte[] maskedBytes = new byte[2 * TOKEN_LENGTH];
		System.arraycopy(randomBytes, 0, maskedBytes, 0, TOKEN_LENGTH);

		// Then follow with the CSRF token bytes, XOR-ed with the random bytes.
		for (int i = 0; i < TOKEN_LENGTH; i++) {
			maskedBytes[TOKEN_LENGTH + i] = (byte) (randomBytes[i] ^ tokenBytes[i]);
		}

		// ...and return a hex-encoded String.
		return Hex.encodeHexString(maskedBytes);
	}

	public boolean checkMaskedToken(String maskedToken) {
		byte[] unmaskedBytes = unmaskToken(maskedToken);
		if (unmaskedBytes == null) {
			return false;
		}

		// Is the token correct?
		return Arrays.equals(unmaskedBytes, tokenBytes);
	}

	public String toString() {
		return getMaskedToken();
	}
}
