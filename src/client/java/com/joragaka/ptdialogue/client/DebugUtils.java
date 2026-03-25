package com.joragaka.ptdialogue.client;

import java.lang.reflect.Field;

public class DebugUtils {
    /**
     * Safe reflection-based dump of an object's public and private fields to a single-line string.
     * Avoids heavy recursion and large outputs; truncates long values.
     */
    public static String dumpObjectShallow(Object obj) {
        if (obj == null) return "<null>";
        try {
            Class<?> c = obj.getClass();
            StringBuilder sb = new StringBuilder();
            sb.append(c.getName()).append("{");
            Field[] fields = c.getDeclaredFields();
            boolean first = true;
            for (Field f : fields) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    String vs = String.valueOf(v);
                    if (vs.length() > 200) vs = vs.substring(0, 200) + "...";
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(f.getName()).append('=').append(vs);
                } catch (Throwable t) {
                    // ignore individual field errors
                }
            }
            sb.append('}');
            return sb.toString();
        } catch (Throwable t) {
            try { return obj.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(obj)); } catch (Throwable ignored) { return "<dump-failed>"; }
        }
    }
}

