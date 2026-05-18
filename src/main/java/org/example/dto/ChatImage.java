package org.example.dto;

/**
 * 解码后的单张聊天图片，供构造 {@link org.springframework.ai.chat.messages.UserMessage}。
 */
public record ChatImage(byte[] bytes, String mimeType) {
}
