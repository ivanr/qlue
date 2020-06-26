/* 
 * Qlue Web Application Framework
 * Copyright 2009-2012 Ivan Ristic <ivanr@webkreator.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.webkreator.qlue.util;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

/**
 * This class contains a number of static methods
 * that can be useful when dealing with text.
 */
public class TextUtil {

    /**
     * Is String empty (null, or 0-size).
     *
     * @param input
     * @return
     */
    public static boolean isEmpty(String input) {
        if (input == null) return true;
        if (input.length() == 0) return true;
        return false;
    }

    /**
     * Is String empty (null, zero-size, or contains only whitespace).
     *
     * @param input
     * @return
     */
    public static boolean isEmptyOrWhitespace(String input) {
        if (input == null) return true;
        if (input.length() == 0) return true;

        // Check every character
        for (int i = 0, n = input.length(); i < n; i++) {
            char c = input.charAt(i);
            // If we find one character that is not a whitespace
            // then the string is not empty
            if (Character.isWhitespace(c) == false) {
                return false;
            }
        }

        // All characters are whitespace
        return true;
    }

    public static String toHex(byte[] b) {
        return Hex.encodeHexString(b);
    }

    public static byte[] fromHex(String s) {
        try {
            return Hex.decodeHex(s);
        } catch (DecoderException e) {
            throw new RuntimeException(e);
        }
    }
}
