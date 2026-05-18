package org.example.service.lexical;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从问句抽取用于 Milvus like 与 BM25 的短语/词项（轻量规则，无外部分词依赖）。
 */
public final class QueryLexicalTerms {

    private static final Pattern LATIN = Pattern.compile("[a-zA-Z][a-zA-Z0-9\\-]{1,48}");
    private static final Set<String> STOP = Set.of(
            "的", "了", "和", "与", "或", "是", "在", "有", "为", "吗", "呢", "啊", "什么", "哪些", "如何", "怎么", "请", "问", "我", "你", "他", "她", "它",
            "the", "a", "an", "is", "are", "was", "were", "what", "how", "when", "where", "which", "who", "and", "or", "not", "to", "of", "in", "on", "for");

    private QueryLexicalTerms() {
    }

    public static List<String> extract(String query, int maxTerms, int minLength) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String q = query.strip();
        // 不把整句直接作为 like 子串（过长且易触发 Milvus 限制）；仅用语片/词项
        if (q.length() >= minLength && q.length() <= 24) {
            terms.add(q);
        }
        Matcher m = LATIN.matcher(q);
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            if (w.length() >= minLength && !STOP.contains(w)) {
                terms.add(w);
            }
        }
        addCjkBigrams(q, minLength, terms);
        terms.removeIf(t -> t == null || t.length() < minLength || STOP.contains(t) || STOP.contains(t.toLowerCase(Locale.ROOT)));
        List<String> out = new ArrayList<>();
        for (String t : terms) {
            if (out.size() >= maxTerms) {
                break;
            }
            String s = sanitize(t);
            if (s.length() >= minLength && !out.contains(s)) {
                out.add(s);
            }
        }
        out.sort((a, b) -> Integer.compare(b.length(), a.length()));
        if (out.size() > maxTerms) {
            return new ArrayList<>(out.subList(0, maxTerms));
        }
        return out;
    }

    private static void addCjkBigrams(String q, int minLength, LinkedHashSet<String> terms) {
        if (minLength > 2) {
            return;
        }
        char[] ch = q.toCharArray();
        for (int i = 0; i + 1 < ch.length; i++) {
            if (isCjk(ch[i]) && isCjk(ch[i + 1])) {
                terms.add(new String(ch, i, 2));
            }
        }
    }

    private static boolean isCjk(char c) {
        return (c >= '\u4e00' && c <= '\u9fff') || (c >= '\u3400' && c <= '\u4dbf');
    }

    /** 仅保留字母数字与中日韩统一表意文字，避免破坏 Milvus like 表达式 */
    private static String sanitize(String t) {
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || isCjk(c)) {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }
}
