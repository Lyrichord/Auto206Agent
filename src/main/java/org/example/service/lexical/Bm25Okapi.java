package org.example.service.lexical;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Okapi BM25，在「当前候选文档集合」上计算相对 idf（用于与向量分数融合排序）。
 */
public final class Bm25Okapi {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private Bm25Okapi() {
    }

    public static List<Double> scores(List<String> queryTerms, List<String> documents) {
        List<Double> out = new ArrayList<>(documents.size());
        if (documents.isEmpty()) {
            return out;
        }
        int n = documents.size();
        List<List<String>> docTokens = new ArrayList<>(n);
        int totalLen = 0;
        for (String d : documents) {
            List<String> toks = tokenizeDoc(d == null ? "" : d);
            docTokens.add(toks);
            totalLen += toks.size();
        }
        double avgdl = totalLen / (double) n;
        Map<String, Integer> df = new HashMap<>();
        for (List<String> toks : docTokens) {
            LinkedHashSet<String> seen = new LinkedHashSet<>(toks);
            for (String t : seen) {
                df.merge(t.toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String t : queryTerms) {
            if (t == null || t.isBlank()) {
                continue;
            }
            String tl = t.toLowerCase(Locale.ROOT);
            uniq.add(tl);
            boolean allCjk = !tl.isEmpty();
            for (int i = 0; i < tl.length(); i++) {
                char ch = tl.charAt(i);
                if (ch < '\u4e00' || ch > '\u9fff') {
                    allCjk = false;
                    break;
                }
            }
            if (allCjk) {
                for (int i = 0; i < tl.length(); i++) {
                    uniq.add(String.valueOf(tl.charAt(i)).toLowerCase(Locale.ROOT));
                }
            }
        }
        List<String> q = new ArrayList<>(uniq);
        for (int i = 0; i < n; i++) {
            out.add(scoreOne(q, docTokens.get(i), df, n, avgdl));
        }
        return out;
    }

    private static double scoreOne(List<String> queryTerms, List<String> docTokens, Map<String, Integer> df, int n, double avgdl) {
        if (queryTerms.isEmpty() || docTokens.isEmpty()) {
            return 0;
        }
        int dl = docTokens.size();
        Map<String, Integer> tf = new HashMap<>();
        for (String t : docTokens) {
            tf.merge(t.toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        double sum = 0;
        for (String term : queryTerms) {
            int f = tf.getOrDefault(term, 0);
            if (f == 0) {
                continue;
            }
            int dfi = df.getOrDefault(term, 0);
            double idf = Math.log(1 + (n - dfi + 0.5) / (dfi + 0.5));
            double denom = f + K1 * (1 - B + B * dl / avgdl);
            sum += idf * (f * (K1 + 1)) / denom;
        }
        return sum;
    }

    private static List<String> tokenizeDoc(String text) {
        List<String> toks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-') {
                cur.append(Character.toLowerCase(c));
            } else {
                flushTok(cur, toks);
                if (c >= '\u4e00' && c <= '\u9fff') {
                    toks.add(String.valueOf(c).toLowerCase(Locale.ROOT));
                }
            }
        }
        flushTok(cur, toks);
        return toks;
    }

    private static void flushTok(StringBuilder cur, List<String> toks) {
        if (cur.length() > 0) {
            toks.add(cur.toString());
            cur.setLength(0);
        }
    }
}
