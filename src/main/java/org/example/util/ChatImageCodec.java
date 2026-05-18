package org.example.util;

import org.example.dto.ChatImage;
import org.example.dto.ChatImagePart;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 校验并解码前端传入的聊天配图 Base64。
 */
public final class ChatImageCodec {

    private static final int MAX_IMAGES = 3;
    /** 单张解码后二进制上限（约 4MB） */
    private static final int MAX_BYTES_PER_IMAGE = 4 * 1024 * 1024;

    private static final Set<String> ALLOWED_MIME = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif");

    private ChatImageCodec() {
    }

    public static List<ChatImage> decode(List<ChatImagePart> parts) {
        if (parts == null || parts.isEmpty()) {
            return List.of();
        }
        if (parts.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("最多上传 " + MAX_IMAGES + " 张图片");
        }
        List<ChatImage> out = new ArrayList<>();
        for (ChatImagePart p : parts) {
            if (p == null) {
                continue;
            }
            String mime = p.getMimeType() == null ? "" : p.getMimeType().strip().toLowerCase(Locale.ROOT);
            if ("image/jpg".equals(mime)) {
                mime = "image/jpeg";
            }
            if (!ALLOWED_MIME.contains(mime)) {
                throw new IllegalArgumentException("不支持的图片类型: " + p.getMimeType());
            }
            String b64 = p.getData();
            if (b64 == null || b64.isBlank()) {
                throw new IllegalArgumentException("图片 Base64 为空");
            }
            b64 = stripDataUrlPrefix(b64.strip());
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("图片 Base64 解码失败", e);
            }
            if (raw.length == 0) {
                throw new IllegalArgumentException("图片数据为空");
            }
            if (raw.length > MAX_BYTES_PER_IMAGE) {
                throw new IllegalArgumentException("单张图片过大（解码后超过约 4MB）");
            }
            out.add(new ChatImage(raw, mime));
        }
        if (out.isEmpty()) {
            return List.of();
        }
        return List.copyOf(out);
    }

    private static String stripDataUrlPrefix(String s) {
        int idx = s.indexOf("base64,");
        if (idx >= 0) {
            return s.substring(idx + "base64,".length());
        }
        return s;
    }
}
