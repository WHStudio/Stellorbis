package com.stellorbis.ringultra2026.util;

/** 十六进制工具 */
public final class HexUtil {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private HexUtil() {
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]).append(' ');
        }
        return sb.toString().trim();
    }

    public static byte[] parse(String hex) {
        String clean = hex.replace(" ", "").replace(":", "");
        int len = clean.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 在原始字节中查找子序列（用于广播识别码匹配） */
    public static boolean contains(byte[] data, byte[] pattern, int from) {
        if (data == null || pattern == null || data.length < pattern.length) {
            return false;
        }
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
