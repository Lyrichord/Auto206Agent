package org.example.context;

/**
 * 当前 HTTP 对话线程的会话 ID，供向量检索按会话过滤上传文档。
 */
public final class RagRequestContext {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    private RagRequestContext() {
    }

    public static void setSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            SESSION_ID.remove();
        } else {
            SESSION_ID.set(sessionId);
        }
    }

    public static String getSessionId() {
        return SESSION_ID.get();
    }

    public static void clear() {
        SESSION_ID.remove();
    }
}
