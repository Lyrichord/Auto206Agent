package org.example.util;

/**
 * Milvus 布尔表达式中 JSON 比较用的字符串转义。
 */
public final class MilvusExprEscape {

    private MilvusExprEscape() {
    }

    public static String stringLiteral(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
