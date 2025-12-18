package com.mycelia.mc.driver;

import java.util.Map;
import java.util.StringJoiner;

class JsonUtil {
    private JsonUtil() {}

    public static String toJson(Map<String, Object> map) {
        StringJoiner joiner = new StringJoiner(",", "{", "}");
        map.forEach((k, v) -> {
            if (v == null) {
                joiner.add("\"" + k + "\":null");
            } else if (v instanceof Number || v instanceof Boolean) {
                joiner.add("\"" + k + "\":" + v);
            } else {
                joiner.add("\"" + k + "\":\"" + v + "\"");
            }
        });
        return joiner.toString();
    }
}
