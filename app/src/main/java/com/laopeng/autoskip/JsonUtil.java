package com.laopeng.autoskip;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class JsonUtil {

    /**
     * 正则编译缓存。
     *
     * 规则集里动辄几千条正则，而一次界面扫描要把「规则 × 节点」全过一遍，
     * 每次都 Pattern.compile 的话主线程会被拖到卡死，所以编译结果必须复用。
     */
    private static final Map<String, Pattern> REGEX_CACHE = new ConcurrentHashMap<>();

    /** 编译失败时的占位：永不匹配。避免每次碰到坏正则都重试编译。 */
    private static final Pattern NEVER_MATCH = Pattern.compile("(?!)");

    /**
     * 宽松解析：剥掉 // 与 /* *\/ 注释、去掉对象/数组的尾逗号、去掉 BOM。
     * 让 App 能直接吃下社区里常见的 json5 规则文件。
     */
    public static String stripJson5(String s) {
        if (s == null) return "{}";
        if (s.startsWith("\uFEFF")) s = s.substring(1);
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        boolean inStr = false;
        boolean esc = false;
        while (i < n) {
            char c = s.charAt(i);
            if (inStr) {
                sb.append(c);
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                i++;
                continue;
            }
            if (c == '"') {
                inStr = true;
                sb.append(c);
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n) {
                char c2 = s.charAt(i + 1);
                if (c2 == '/') {
                    while (i < n && s.charAt(i) != '\n') i++;
                    continue;
                }
                if (c2 == '*') {
                    i += 2;
                    while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                    i += 2;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString().replaceAll(",\\s*([}\\]])", "$1");
    }

    /** 字段值可能是字符串，也可能是字符串数组，统一成 List。 */
    public static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o == null || o == org.json.JSONObject.NULL) return out;
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                if (v == null || v == org.json.JSONObject.NULL) continue;
                if (v instanceof String) {
                    String s = ((String) v).trim();
                    if (s.length() > 0) out.add(s);
                } else if (v instanceof Number || v instanceof Boolean) {
                    out.add(String.valueOf(v));
                }
            }
        } else if (o instanceof String) {
            String s = ((String) o).trim();
            if (s.length() > 0) out.add(s);
        } else if (o instanceof Number || o instanceof Boolean) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    public static boolean contains(String src, String pattern, boolean regex) {
        if (src == null || pattern == null || pattern.length() == 0) return false;
        if (regex) {
            try {
                Pattern p = REGEX_CACHE.get(pattern);
                if (p == null) {
                    try {
                        p = Pattern.compile(pattern);
                    } catch (Throwable t) {
                        p = NEVER_MATCH;
                    }
                    if (REGEX_CACHE.size() < 8192) REGEX_CACHE.put(pattern, p);
                }
                return p.matcher(src).find();
            } catch (Throwable t) {
                // 病态正则（比如灾难性回溯）抛的是 StackOverflowError，
                // 它是 Error 不是 Exception，只 catch Exception 会漏掉 → 直接崩进程。
                return false;
            }
        }
        return src.contains(pattern) || src.toLowerCase().contains(pattern.toLowerCase());
    }
}
