package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.example.config.ResearchFeedProperties;
import org.example.dto.ResearchFeedItemDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 聚合 arXiv（论文）与 GitHub（相关仓库），按「最近一周 + 自动驾驶关键词」过滤；会话内随机「再来10篇」不重复直至池耗尽。
 */
@Service
public class ResearchFeedService {

    private static final Logger logger = LoggerFactory.getLogger(ResearchFeedService.class);

    private static final String ATOM_NS = "http://www.w3.org/2005/Atom";
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final int BATCH = 10;

    private final ResearchFeedProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public ResearchFeedService(ResearchFeedProperties properties) {
        this.properties = properties;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofMillis(Math.max(1000, properties.getConnectTimeoutMs())))
                .readTimeout(java.time.Duration.ofMillis(Math.max(1000, properties.getReadTimeoutMs())))
                .build();
    }

    public Map<String, Object> startFeed() throws Exception {
        Instant weekEnd = Instant.now();
        Instant weekStart = weekEnd.minus(7, ChronoUnit.DAYS);

        List<ResearchFeedItemDto> pool = new ArrayList<>();
        pool.addAll(fetchArxiv());
        pool.addAll(fetchGithub(weekStart, weekEnd));

        pool = dedupeAndFilter(pool, weekStart, weekEnd);
        Collections.shuffle(pool, ThreadLocalRandom.current());

        if (pool.isEmpty()) {
            return Map.of(
                    "ok", false,
                    "error", "最近一周内未检索到符合条件的 arXiv/GitHub 条目，请稍后重试或放宽关键词。"
            );
        }

        String sessionId = UUID.randomUUID().toString();
        Session s = new Session(sessionId, pool, System.currentTimeMillis(), weekStart, weekEnd);
        sessions.put(sessionId, s);

        List<ResearchFeedItemDto> first = takeBatch(s, BATCH);
        return responseMap(s, first);
    }

    public Map<String, Object> more(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of("ok", false, "error", "缺少 sessionId");
        }
        Session s = sessions.get(sessionId.trim());
        if (s == null || isExpired(s)) {
            sessions.remove(sessionId.trim());
            return Map.of("ok", false, "error", "会话已过期，请重新点击「获取最近自动驾驶领域文章」。");
        }
        List<ResearchFeedItemDto> next = takeBatch(s, BATCH);
        if (next.isEmpty()) {
            return Map.of(
                    "ok", true,
                    "sessionId", s.sessionId,
                    "items", List.of(),
                    "hasMore", false,
                    "remaining", 0,
                    "weekStart", s.weekStart.toString(),
                    "weekEnd", s.weekEnd.toString(),
                    "message", "本周池内已无未展示条目，可重新获取以拉取最新一周数据。"
            );
        }
        return responseMap(s, next);
    }

    private boolean isExpired(Session s) {
        return System.currentTimeMillis() - s.createdAt > properties.getSessionTtlMs();
    }

    private Map<String, Object> responseMap(Session s, List<ResearchFeedItemDto> batch) {
        int remaining = (int) s.pool.stream().filter(it -> !s.servedUrls.contains(it.getUrl())).count();
        return Map.of(
                "ok", true,
                "sessionId", s.sessionId,
                "items", batch,
                "hasMore", remaining > 0,
                "remaining", remaining,
                "weekStart", s.weekStart.toString(),
                "weekEnd", s.weekEnd.toString()
        );
    }

    private List<ResearchFeedItemDto> takeBatch(Session s, int n) {
        List<ResearchFeedItemDto> candidates = s.pool.stream()
                .filter(it -> !s.servedUrls.contains(it.getUrl()))
                .collect(Collectors.toList());
        Collections.shuffle(candidates, ThreadLocalRandom.current());
        List<ResearchFeedItemDto> out = new ArrayList<>();
        for (ResearchFeedItemDto it : candidates) {
            if (out.size() >= n) {
                break;
            }
            s.servedUrls.add(it.getUrl());
            out.add(it);
        }
        return out;
    }

    private List<ResearchFeedItemDto> dedupeAndFilter(List<ResearchFeedItemDto> raw,
                                                      Instant weekStart, Instant weekEnd) {
        Set<String> seen = new LinkedHashSet<>();
        List<ResearchFeedItemDto> out = new ArrayList<>();
        for (ResearchFeedItemDto it : raw) {
            if (it.getUrl() == null || it.getUrl().isBlank()) {
                continue;
            }
            if (!seen.add(it.getUrl())) {
                continue;
            }
            Instant pub = parseInstantLenient(it.getPublishedAt());
            if (pub != null && (pub.isBefore(weekStart) || pub.isAfter(weekEnd))) {
                continue;
            }
            if (!matchesAdKeywords(it)) {
                continue;
            }
            out.add(it);
        }
        out.sort(Comparator.comparing(ResearchFeedItemDto::getPublishedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    private boolean matchesAdKeywords(ResearchFeedItemDto it) {
        String blob = ((it.getTitle() != null ? it.getTitle() : "")
                + " " + (it.getSummary() != null ? it.getSummary() : "")).toLowerCase(Locale.ROOT);
        return blob.contains("autonomous")
                || blob.contains("self-driving")
                || blob.contains("self driving")
                || blob.contains("vehicle")
                || blob.contains("driving")
                || blob.contains("adas")
                || blob.contains("end-to-end")
                || blob.contains("navigation")
                || blob.contains("slam")
                || blob.contains("robotaxi");
    }

    private Instant parseInstantLenient(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            try {
                return ZonedDateTime.parse(iso).toInstant();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private List<ResearchFeedItemDto> fetchArxiv() {
        List<ResearchFeedItemDto> list = new ArrayList<>();
        String urlStr = "http://export.arxiv.org/api/query?search_query=" + java.net.URLEncoder.encode(
                "(cat:cs.RO OR cat:cs.CV OR cat:cs.SY) AND all:autonomous", StandardCharsets.UTF_8)
                + "&start=0&max_results=50&sortBy=submittedDate&sortOrder=descending";
        Request req = new Request.Builder().url(urlStr).get().build();
        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                logger.warn("arXiv HTTP {}", resp.code());
                return list;
            }
            String xml = resp.body().string();
            list.addAll(parseArxivAtom(xml));
        } catch (Exception e) {
            logger.warn("arXiv 拉取失败: {}", e.getMessage());
        }
        return list;
    }

    private List<ResearchFeedItemDto> parseArxivAtom(String xml) throws Exception {
        List<ResearchFeedItemDto> out = new ArrayList<>();
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList entries = doc.getElementsByTagNameNS(ATOM_NS, "entry");
        for (int i = 0; i < entries.getLength(); i++) {
            Node n = entries.item(i);
            if (!(n instanceof Element el)) {
                continue;
            }
            String id = textChild(el, "id");
            String title = collapseWs(textChild(el, "title"));
            String summary = collapseWs(textChild(el, "summary"));
            String published = textChild(el, "published");
            if (id == null || id.isBlank()) {
                continue;
            }
            String absUrl = id.replace("http://arxiv.org/abs/", "https://arxiv.org/abs/");
            ResearchFeedItemDto dto = new ResearchFeedItemDto();
            dto.setSource("arXiv");
            dto.setTitle(title != null && !title.isEmpty() ? title : absUrl);
            dto.setUrl(absUrl);
            dto.setSummary(trimSummary(summary, 320));
            dto.setPublishedAt(published != null && !published.isBlank() ? Instant.parse(published).toString() : null);
            out.add(dto);
        }
        return out;
    }

    private static String textChild(Element entry, String local) {
        NodeList nl = entry.getElementsByTagNameNS(ATOM_NS, local);
        if (nl.getLength() == 0) {
            return "";
        }
        return nl.item(0).getTextContent();
    }

    private static String collapseWs(String s) {
        if (s == null) {
            return "";
        }
        return WS.matcher(s.trim()).replaceAll(" ");
    }

    private static String trimSummary(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = collapseWs(s);
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private List<ResearchFeedItemDto> fetchGithub(Instant weekStart, Instant weekEnd) {
        List<ResearchFeedItemDto> list = new ArrayList<>();
        String day = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(weekStart);
        String q = "(autonomous driving OR self-driving OR autonomous vehicle OR robotaxi) pushed:>" + day;
        String urlStr = "https://api.github.com/search/repositories?q=" + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&sort=pushed&order=desc&per_page=30";
        Request.Builder rb = new Request.Builder()
                .url(urlStr)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Auto206Agent/1.0 (research-feed; +https://github.com/)");
        String tok = properties.getGithubToken();
        if (tok != null && !tok.isBlank()) {
            rb.header("Authorization", "token " + tok.trim());
        }
        try (Response resp = httpClient.newCall(rb.get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                logger.warn("GitHub Search HTTP {}", resp.code());
                return list;
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            JsonNode items = root.path("items");
            if (!items.isArray()) {
                return list;
            }
            for (JsonNode it : items) {
                String htmlUrl = it.path("html_url").asText("");
                String fullName = it.path("full_name").asText("");
                String desc = it.path("description").asText("");
                String pushed = it.path("pushed_at").asText("");
                if (htmlUrl.isBlank()) {
                    continue;
                }
                ResearchFeedItemDto dto = new ResearchFeedItemDto();
                dto.setSource("GitHub");
                dto.setTitle(fullName.isBlank() ? htmlUrl : fullName);
                dto.setUrl(htmlUrl);
                dto.setSummary(trimSummary(desc, 280));
                try {
                    dto.setPublishedAt(Instant.parse(pushed).toString());
                } catch (Exception e) {
                    dto.setPublishedAt(null);
                }
                list.add(dto);
            }
        } catch (Exception e) {
            logger.warn("GitHub 拉取失败: {}", e.getMessage());
        }
        return list;
    }

    private static final class Session {
        final String sessionId;
        final List<ResearchFeedItemDto> pool;
        final Set<String> servedUrls = ConcurrentHashMap.newKeySet();
        final long createdAt;
        final Instant weekStart;
        final Instant weekEnd;

        Session(String sessionId, List<ResearchFeedItemDto> pool, long createdAt,
                Instant weekStart, Instant weekEnd) {
            this.sessionId = sessionId;
            this.pool = pool;
            this.createdAt = createdAt;
            this.weekStart = weekStart;
            this.weekEnd = weekEnd;
        }
    }
}
