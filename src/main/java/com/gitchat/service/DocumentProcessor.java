package com.gitchat.service;

import com.gitchat.model.Document;
import java.util.*;

/**
 * 代码切分器 —— 相当于 Python 的 document_processor.py
 *
 * Python版:  langchain.text_splitter.RecursiveCharacterTextSplitter
 * Java版:   手写 splitText()（功能等价于 LangChain4j 的 DocumentByParagraphSplitter）
 *
 * 把大文件切成小块（第23课学过的），方便后面搜索
 */
public class DocumentProcessor {

    private static final int CHUNK_SIZE = 2000;   // 每块大小（字符数）
    private static final int CHUNK_OVERLAP = 200;  // 重叠量

    /**
     * 切分文档：把传进来的文档列表切成小块
     * @param documents 原始大文档列表
     * @return 切好的碎块列表
     */
    public static List<Document> splitDocs(List<Document> documents) {
        List<Document> finalDocs = new ArrayList<>();

        for (Document doc : documents) {
            String source = doc.getMetadata().getOrDefault("source", "");
            String ext = getFileExtension(source);
            String content = doc.getPageContent();

            if (content == null || content.isEmpty()) continue;

            // 根据文件类型选择不同的切法（第23课：PYTHON / JS / 通用）
            int effectiveChunkSize = CHUNK_SIZE;
            if (ext.equals("md") || ext.equals("txt")) {
                effectiveChunkSize = 1000;
            }

            // 切！（第23课的 RecursiveCharacterTextSplitter 简化版）
            List<String> chunks = splitText(content, effectiveChunkSize, CHUNK_OVERLAP);

            for (int i = 0; i < chunks.size(); i++) {
                Map<String, String> meta = new HashMap<>(doc.getMetadata());
                meta.put("chunk_index", String.valueOf(i));
                finalDocs.add(new Document(chunks.get(i), meta));
            }
        }

        System.out.println("[DocProcessor] 文档切分完成: " + documents.size() + " → " + finalDocs.size() + " 个碎块");
        return finalDocs;
    }

    /** 把一段文字切成小块（简化的 RecursiveCharacterTextSplitter） */
    private static List<String> splitText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text.length() <= chunkSize) {
            chunks.add(text);
            return chunks;
        }

        // 按段落切（优先在段落边界切）
        String[] paragraphs = text.split("\n\n");
        StringBuilder currentChunk = new StringBuilder();

        for (String para : paragraphs) {
            if (currentChunk.length() + para.length() > chunkSize && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
                // 保留重叠部分
                String overlapText = currentChunk.substring(Math.max(0, currentChunk.length() - overlap));
                currentChunk = new StringBuilder(overlapText);
            }
            if (currentChunk.length() > 0) {
                currentChunk.append("\n\n");
            }
            currentChunk.append(para);
        }

        if (currentChunk.length() > 0) {
            String lastChunk = currentChunk.toString().trim();
            if (lastChunk.length() > chunkSize * 1.5) {
                // 太长就强制按行切
                chunks.addAll(splitByLines(lastChunk, chunkSize, overlap));
            } else {
                chunks.add(lastChunk);
            }
        }

        return chunks;
    }

    /** 强制按行切（兜底） */
    private static List<String> splitByLines(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            // 尽量在换行处切
            if (end < text.length()) {
                int nl = text.lastIndexOf('\n', end);
                if (nl > start + chunkSize / 2) {
                    end = nl + 1;
                }
            }
            chunks.add(text.substring(start, end).trim());
            start = Math.max(start + 1, end - overlap);
        }
        return chunks;
    }

    /** 取文件后缀（第24.1课 os.path.splitext） */
    private static String getFileExtension(String path) {
        int dotIdx = path.lastIndexOf('.');
        if (dotIdx < 0) return "";
        return path.substring(dotIdx).toLowerCase();
    }
}
