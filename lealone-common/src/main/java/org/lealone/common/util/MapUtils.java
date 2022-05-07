/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.common.util;

import java.util.Map;

public class MapUtils {

    private MapUtils() {
        // utility class
    }

    public static int getInt(Map<String, String> map, String key, int def) {
        if (map == null)
            return def;
        String value = map.get(key);
        return Utils.toInt(value, def);
    }

    public static int getIntMB(Map<String, String> map, String key, int def) {
        if (map == null)
            return def;
        String value = map.get(key);
        return Utils.toIntMB(value, def);
    }

    public static long getLong(Map<String, String> map, String key, long def) {
        if (map == null)
            return def;
        String value = map.get(key);
        return Utils.toLong(value, def);
    }

    public static boolean getBoolean(Map<String, String> map, String key, boolean def) {
        if (map == null)
            return def;
        String value = map.get(key);
        return Utils.toBoolean(value, def);
    }

    public static String getString(Map<String, String> map, String key, String def) {
        if (map == null)
            return def;
        String value = map.get(key);
        if (value == null)
            return def;
        else
            return value;
    }
}
