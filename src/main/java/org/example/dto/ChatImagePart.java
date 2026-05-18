package org.example.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对话中随 JSON 上传的单张图片（Base64，不含 data URL 前缀）。
 */
public class ChatImagePart {

    @JsonProperty("MimeType")
    @JsonAlias({"mimeType", "MIME_TYPE"})
    private String mimeType;

    @JsonProperty("Data")
    @JsonAlias({"data", "base64"})
    private String data;

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }
}
