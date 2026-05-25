package com.gitchat.model;

import java.util.HashMap;
import java.util.Map;

/**
 * 代码文档 —— 相当于 Python 里 LangChain 的 Document 对象
 * 
 * 每个 Document 就是"一袋代码"，袋子上贴着标签（metadata），袋子里装着代码（pageContent）
 */
public class Document {
    private String id;              // 唯一ID
    private String pageContent;     // 代码内容（相当于 Python 的 doc.page_content）
    private Map<String, String> metadata; // 额外信息：文件名、来源等（相当于 Python 的 doc.metadata）

    public Document() {
        this.metadata = new HashMap<>();
    }

    public Document(String pageContent, Map<String, String> metadata) {
        this.pageContent = pageContent;
        this.metadata = metadata != null ? metadata : new HashMap<>();
        if (this.metadata.containsKey("source")) {
            this.id = this.metadata.get("source");
        }
    }

    // ── Getter / Setter ──
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPageContent() { return pageContent; }
    public void setPageContent(String pageContent) { this.pageContent = pageContent; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
}
