package com.gitchat.service;

import com.gitchat.model.Document;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * RAG 服务核心 - 相当于 Python 的 rag_service.py
 * 整个项目的"大脑"：处理文档 + 流式回答
 */
public class RagService {

    // ── LangChain4j 流式聊天模型（★ 代替手写 OkHttp + SSE 解析）──
    private final StreamingChatLanguageModel chatModel;
    private final String modelName;

    private List<Document> docChunks = new ArrayList<>();
    private String repoName = "";
    private String repoSlug = "";
    private String repoOverviewText = "";
    private final Map<String, String> fullDocuments = new LinkedHashMap<>();
    private Map<String, Object> analysisStats = new LinkedHashMap<>();
    private final List<Map<String, String>> chatHistory = new ArrayList<>();

    private final AdvancedRetriever advancedRetriever = new AdvancedRetriever();
    private final ChatHistoryStore historyStore = new ChatHistoryStore();
    private boolean useAdvancedRetrieval = false;

    public RagService() {
        String apiKey = System.getenv().getOrDefault("OPENROUTER_API_KEY", "");
        String baseUrl = System.getenv().getOrDefault("OPENROUTER_BASE_URL",
                "https://openrouter.ai/api/v1");
        this.modelName = System.getenv().getOrDefault("MODEL_NAME",
                "google/gemini-2.5-flash");

        // ★ 用 LangChain4j 创建流式聊天模型
        // 相当于 Python 的: ChatOpenAI(streaming=True, ...)
        this.chatModel = OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.2)
                .maxTokens(8192)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    public void processDocuments(List<Document> documents, String repoName, String repoSlug) {
        this.repoName = repoName;
        this.repoSlug = (repoSlug != null && !repoSlug.isEmpty())
                ? repoSlug : repoName.replace("_", "/");
        System.out.println("[RAG] Processing: " + repoName + " (" + documents.size() + " files)");
        if (documents.isEmpty()) throw new RuntimeException("No documents");
        fullDocuments.clear();
        for (Document doc : documents) {
            String source = doc.getMetadata().getOrDefault("source", "");
            if (!source.isEmpty()) fullDocuments.put(source.toLowerCase(), doc.getPageContent());
        }
        List<Document> chunks = DocumentProcessor.splitDocs(documents);
        if (chunks.isEmpty()) throw new RuntimeException("Split empty");
        docChunks = chunks;
        repoOverviewText = buildRepoOverview(documents, chunks);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("files", documents.size());
        stats.put("chunks", chunks.size());
        long us = chunks.stream().map(c -> c.getMetadata().getOrDefault("source", "unknown"))
                .distinct().count();
        stats.put("unique_sources", (int) us);
        analysisStats = stats;
        chatHistory.clear();
        historyStore.clearRepo(this.repoSlug);
        try {
            advancedRetriever.buildVectorStore(chunks);
            useAdvancedRetrieval = true;
        } catch (Exception e) {
            useAdvancedRetrieval = false;
        }
    }

    /**
     * 流式回答 —— 通过 SseEmitter 把 AI 回答逐字推给前端
     * 相当于 Python 的 async def stream_answer() + SSE yield
     */
    public SseEmitter streamAnswer(String question) {
        SseEmitter emitter = new SseEmitter(0L); // 0 = 不超时
        new Thread(() -> {
            try {
                if (docChunks.isEmpty()) {
                    emitter.send(SseEmitter.event().name("error").data("No data"));
                    emitter.complete();
                    return;
                }
                List<String> reqFiles = detectRequestedFiles(question);
                boolean hasFull = !reqFiles.isEmpty();
                List<Document> topChunks = useAdvancedRetrieval
                        ? advancedRetriever.ensembleSearch(question, 20)
                        : docChunks.subList(0, Math.min(20, docChunks.size()));
                String baseUrl = repoSlug.isEmpty()
                        ? "https://github.com/OWNER/REPO/blob/HEAD/"
                        : "https://github.com/" + repoSlug + "/blob/HEAD/";
                Map<String, List<String>> grouped = groupBySource(topChunks);
                String fNames = hasFull ? String.join(", ", reqFiles) : "";
                String sysPrompt = buildSystemTemplate(hasFull, baseUrl, fNames) + repoOverviewText;
                String hist = formatHistory(8);
                String ctx = buildContext(grouped, hasFull, reqFiles);
                String prompt = sysPrompt + "\nHISTORY:\n" + hist
                        + "\nQUESTION:\n" + question + "\nCONTEXT:\n" + ctx;
                emitThought(emitter, question, topChunks, grouped);
                String ans = callLLMStream(emitter, prompt);
                if (ans != null && !ans.isEmpty()) {
                    historyStore.saveQaPair(repoSlug, question, ans);
                    chatHistory.add(Map.of("role", "user", "content", question));
                    chatHistory.add(Map.of("role", "assistant", "content", ans));
                    if (chatHistory.size() > 40)
                        chatHistory.subList(0, chatHistory.size() - 40).clear();
                }
                emitEvidence(emitter, baseUrl, grouped);
                emitter.complete();
            } catch (Exception e) {
                try { emitter.send(SseEmitter.event().name("error").data(
                        e.getMessage() != null ? e.getMessage() : "unknown")); }
                catch (Exception ignored) {}
                emitter.complete();
            }
        }).start();
        return emitter;
    }

    private String buildRepoOverview(List<Document> docs, List<Document> chunks) {
        Map<String, Integer> extC = new LinkedHashMap<>();
        for (Document d : docs) {
            String src = d.getMetadata().getOrDefault("source", "unknown").toLowerCase();
            String ext = "(no_ext)"; int dot = src.lastIndexOf('.'); if (dot >= 0) ext = src.substring(dot);
            extC.merge(ext, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("Files: " + docs.size() + ", Chunks: " + chunks.size() + ", Types: ");
        extC.forEach((k, v) -> sb.append(k).append(":").append(v).append(" "));
        return sb.toString();
    }

    private String buildSystemTemplate(boolean hasFull, String baseUrl, String fileNames) {
        if (hasFull) return "SYSTEM: Output complete file for: " + fileNames;
        return "SYSTEM: Answer based on context only. Do not invent. Reference: [name](" + baseUrl + "PATH)";
    }

    private List<String> detectRequestedFiles(String question) {
        List<String> req = new ArrayList<>();
        String ql = question.toLowerCase();
        Set<String> cand = new LinkedHashSet<>();
        Pattern p1 = Pattern.compile("(?:[\\w\\-_]+/)+[\\w\\-_\\.]+");
        Matcher m1 = p1.matcher(question);
        while (m1.find()) cand.add(m1.group().trim().toLowerCase());
        Pattern p2 = Pattern.compile("(?:show|read|display|view|open|list|print|get|cat|显示|查看|打开|读|列出)"
                + "\\s*(?:file|文件)?\\s*`?([\\w\\-_\\.]+)`?", Pattern.CASE_INSENSITIVE);
        Matcher m2 = p2.matcher(ql);
        while (m2.find()) cand.add(m2.group(1).trim());
        Pattern p3 = Pattern.compile("[`\"']([\\w\\-_\\./]+)[`\"']");
        Matcher m3 = p3.matcher(question);
        while (m3.find()) cand.add(m3.group(1).trim().toLowerCase());
        Pattern p4 = Pattern.compile("([\\w\\-_\\./]+)\\s*(?:file|文件|代码)", Pattern.CASE_INSENSITIVE);
        Matcher m4 = p4.matcher(ql);
        while (m4.find()) cand.add(m4.group(1).trim());
        Pattern p5 = Pattern.compile("[\\w\\-_\\.]+");
        Matcher m5 = p5.matcher(ql);
        while (m5.find()) {
            String w = m5.group(); int dot = w.lastIndexOf('.');
            if (dot > 0 && dot < w.length() - 1) {
                String ext = w.substring(dot + 1);
                if (ext.length() >= 2 && ext.length() <= 6) cand.add(w);
            }
        }
        for (String c : cand) {
            String cl = c.trim().toLowerCase().replace("\\", "/");
            if (cl.startsWith("/")) cl = cl.substring(1);
            if (cl.isEmpty()) continue;
            if (fullDocuments.containsKey(cl)) { req.add(cl); continue; }
            for (String k : fullDocuments.keySet()) {
                if (k.endsWith("/" + cl) || k.equals(cl) || (cl.length() > 3 && k.contains(cl))) {
                    req.add(k); break;
                }
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(req));
    }

    private Map<String, List<String>> groupBySource(List<Document> chunks) {
        Map<String, List<String>> g = new LinkedHashMap<>();
        for (Document c : chunks)
            g.computeIfAbsent(c.getMetadata().getOrDefault("source","unknown"),
                    k -> new ArrayList<>()).add(c.getPageContent());
        return g;
    }

    private String buildContext(Map<String, List<String>> grouped, boolean hasFull, List<String> reqFiles) {
        StringBuilder ctx = new StringBuilder("=== SEARCH RESULTS ===\n");
        int count = 0;
        for (var e : grouped.entrySet()) {
            if (count >= 12) break;
            String merged = String.join("\n\n", e.getValue());
            if (merged.length() > 3200) merged = merged.substring(0, 3200);
            ctx.append("[").append(e.getKey()).append("]\n").append(merged).append("\n\n---\n\n");
            count++;
        }
        if (hasFull) for (String fp : reqFiles) {
            String content = fullDocuments.get(fp);
            if (content != null) {
                if (content.length() > 40000) content = content.substring(0, 40000);
                ctx.append("[").append(fp).append(" FULL]\n```\n").append(content).append("\n```\n\n");
            }
        }
        return ctx.length() > 0 ? ctx.toString() : "(no context)";
    }

    private void emitThought(SseEmitter emitter, String q, List<Document> top, Map<String, List<String>> grp) {
        try {
            Set<String> srcs = new LinkedHashSet<>();
            for (Document c : top) srcs.add(c.getMetadata().getOrDefault("source","unknown"));
            emitter.send(SseEmitter.event().data(jsonEvent("thought","Searching: "+qPreview(q))));
            Thread.sleep(300);
            emitter.send(SseEmitter.event().data(jsonEvent("thought","Found "+top.size()+" chunks")));
            Thread.sleep(300);
            emitter.send(SseEmitter.event().data(jsonEvent("thought","Covered "+srcs.size()+" files")));
            Thread.sleep(300);
            List<String> sl = new ArrayList<>(srcs);
            if (!sl.isEmpty()) {
                StringBuilder sb = new StringBuilder("Files: ");
                for (int i = 0; i < Math.min(5,sl.size()); i++) {
                    if (i>0) sb.append(", ");
                    String s = sl.get(i); int slash = s.lastIndexOf('/');
                    sb.append(slash>=0 ? s.substring(slash+1) : s);
                }
                emitter.send(SseEmitter.event().data(jsonEvent("thought",sb.toString())));
            }
            Thread.sleep(300);
            emitter.send(SseEmitter.event().data("{\"type\":\"thought_done\"}"));
        } catch (Exception e) { /* ignore */ }
    }

    /**
     * ★ 调用 LangChain4j 流式聊天模型
     * 相当于 Python 的: async for chunk in llm.astream(prompt): yield chunk
     *
     * 以前手写版：150行（OkHttp请求 + SSE解析 + JSON拆包）
     * 现在 LangChain4j：只需实现3个回调
     *
     * 注意：chatModel.generate() 是异步的（立即返回，回调异步触发）
     * 所以用 CountDownLatch 等待所有字吐完再返回完整回答
     */
    private String callLLMStream(SseEmitter emitter, String prompt) {
        StringBuilder fullAnswer = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);

        // ★ LangChain4j 流式调用（0.34.0 API：onNext/onComplete/onError）
        chatModel.generate(List.of(new UserMessage(prompt)), new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                fullAnswer.append(token);
                try {
                    emitter.send(SseEmitter.event()
                            .data(jsonEvent("response", token)));
                } catch (IOException ignored) {}
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                System.err.println("[LLM] Error: " + error.getMessage());
                try {
                    emitter.send(SseEmitter.event()
                            .data(jsonEvent("error", error.getMessage())));
                } catch (IOException ignored) {}
                latch.countDown();
            }
        });

        try { latch.await(120, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        return fullAnswer.toString().trim();
    }

    private void emitEvidence(SseEmitter emitter, String baseUrl, Map<String, List<String>> grouped) {
        List<String> srcs = new ArrayList<>(grouped.keySet());
        if (srcs.isEmpty()) return;
        StringBuilder ev = new StringBuilder("\n\nEVIDENCE:\n");
        for (int i=0; i<Math.min(8,srcs.size()); i++)
            ev.append("- [").append(srcs.get(i)).append("](")
              .append(baseUrl).append(srcs.get(i)).append(")\n");
        try {
            emitter.send(SseEmitter.event().data(jsonEvent("response",ev.toString())));
        } catch (IOException e) { /* ignore */ }
    }

    private String formatHistory(int max) {
        if (chatHistory.isEmpty()) return "(none)";
        int s = Math.max(0,chatHistory.size()-max);
        StringBuilder sb = new StringBuilder();
        for (int i=s; i<chatHistory.size(); i++)
            sb.append(chatHistory.get(i).get("role")).append(": ")
              .append(chatHistory.get(i).get("content")).append("\n");
        return sb.toString();
    }

    private static String jsonEvent(String type, String content) {
        return "{\"type\":\""+type+"\",\"content\":\""+
                content.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"}";
    }

    private static String qPreview(String q) {
        return q.length()>60 ? q.substring(0,60)+"..." : q;
    }

    public List<Map<String, String>> getChatHistory(String slug) {
        return historyStore.getHistory(slug != null ? slug : repoSlug);
    }

    public Map<String, Object> getStats() { return new LinkedHashMap<>(analysisStats); }
}
