package com.gitchat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitchat.model.Document;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

@Service
public class RagService {

    private final StreamingChatLanguageModel chatModel;
    private final String modelName;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private List<Document> docChunks = new ArrayList<>();
    private String repoName = "";
    private String repoSlug = "";
    private String repoOverviewText = "";
    private final Map<String, String> fullDocuments = new LinkedHashMap<>();
    private Map<String, Object> analysisStats = new LinkedHashMap<>();

    // LangChain4j ChatMemory — per-repo conversation memory
    private final Map<String, ChatMemory> repoMemories = new ConcurrentHashMap<>();

    private final AdvancedRetriever advancedRetriever;
    private final ChatHistoryStore historyStore = new ChatHistoryStore();
    private boolean useAdvancedRetrieval = false;

    public RagService(
            @Value("${openrouter.api-key}") String apiKey,
            @Value("${openrouter.base-url}") String baseUrl,
            @Value("${openrouter.model}") String modelName) {
        this.modelName = modelName;
        this.advancedRetriever = new AdvancedRetriever();

        this.chatModel = OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.2)
                .maxTokens(8192)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private ChatMemory getMemory() {
        return repoMemories.computeIfAbsent(repoSlug,
                k -> MessageWindowChatMemory.withMaxMessages(20));
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
        // Clear memory for this repo when re-analyzed
        ChatMemory old = repoMemories.remove(this.repoSlug);
        if (old != null) old.clear();
        historyStore.clearRepo(this.repoSlug);
        try {
            advancedRetriever.buildVectorStore(chunks);
            useAdvancedRetrieval = true;
        } catch (Exception e) {
            System.err.println("[RAG] Vector store build failed, falling back to keyword: " + e.getMessage());
            useAdvancedRetrieval = false;
        }
    }

    public SseEmitter streamAnswer(String question) {
        SseEmitter emitter = new SseEmitter(0L);
        new Thread(() -> {
            try {
                if (docChunks.isEmpty()) {
                    sendEvent(emitter, "error", "No data loaded. Please analyze a repository first.");
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

                // Build RAG context
                String ctx = buildContext(grouped, hasFull, reqFiles);

                // Build system message with RAG context + markdown formatting instructions
                String systemText = buildSystemPrompt(baseUrl, ctx);
                SystemMessage sysMsg = new SystemMessage(systemText);

                // Build message list: system + memory history + current question
                ChatMemory memory = getMemory();
                List<ChatMessage> messages = new ArrayList<>();
                messages.add(sysMsg);
                messages.addAll(memory.messages());
                messages.add(new UserMessage(question));

                // Emit thinking indicators
                emitThought(emitter, question, topChunks, grouped);

                // Stream the answer
                String ans = callLLMStream(emitter, messages);

                if (ans != null && !ans.isEmpty()) {
                    // Save to LangChain4j memory
                    memory.add(new UserMessage(question));
                    memory.add(new AiMessage(ans));
                    // Also persist to SQLite
                    historyStore.saveQaPair(repoSlug, question, ans);
                }
                emitEvidence(emitter, baseUrl, grouped);
                emitter.complete();
            } catch (Exception e) {
                try {
                    sendEvent(emitter, "error",
                            e.getMessage() != null ? e.getMessage() : "unknown error");
                } catch (Exception ignored) {}
                emitter.complete();
            }
        }).start();
        return emitter;
    }

    // ── Prompt building ──

    private String buildSystemPrompt(String baseUrl, String context) {
        return """
            You are GitChat, an expert code analyst. Answer questions about the codebase using the provided context.

            ## Formatting Rules
            - Use Markdown for all responses: headings, lists, bold, italic, links.
            - Wrap ALL code in fenced code blocks with the language tag: ```python, ```java, ```javascript, etc.
            - Use inline `code` for identifiers, file names, and short symbols.
            - Use blockquotes for quoting code or logs.
            - Use tables when comparing options or listing items with attributes.
            - Keep responses clear and well-structured — separate sections with headings.

            ## Accuracy Rules
            - Answer ONLY based on the provided context. Do NOT invent or guess.
            - If the context doesn't contain enough information, say so clearly.
            - When referencing files, use the format: [`filename`](%sPATH)

            ## Context
            %s
            """.formatted(baseUrl, context);
    }

    private String buildRepoOverview(List<Document> docs, List<Document> chunks) {
        Map<String, Integer> extC = new LinkedHashMap<>();
        for (Document d : docs) {
            String src = d.getMetadata().getOrDefault("source", "unknown").toLowerCase();
            String ext = "(no_ext)"; int dot = src.lastIndexOf('.'); if (dot >= 0) ext = src.substring(dot);
            extC.merge(ext, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("- **Files**: ").append(docs.size()).append(", **Chunks**: ").append(chunks.size()).append("\n");
        sb.append("- **Types**: ");
        extC.forEach((k, v) -> sb.append(k).append(":").append(v).append(" "));
        return sb.toString();
    }

    // ── File detection ──

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

    // ── Context building ──

    private Map<String, List<String>> groupBySource(List<Document> chunks) {
        Map<String, List<String>> g = new LinkedHashMap<>();
        for (Document c : chunks)
            g.computeIfAbsent(c.getMetadata().getOrDefault("source","unknown"),
                    k -> new ArrayList<>()).add(c.getPageContent());
        return g;
    }

    private String buildContext(Map<String, List<String>> grouped, boolean hasFull, List<String> reqFiles) {
        StringBuilder ctx = new StringBuilder();
        int count = 0;
        for (var e : grouped.entrySet()) {
            if (count >= 12) break;
            String merged = String.join("\n\n", e.getValue());
            if (merged.length() > 3200) merged = merged.substring(0, 3200) + "\n... (truncated)";
            ctx.append("### `").append(e.getKey()).append("`\n```\n").append(merged).append("\n```\n\n");
            count++;
        }
        if (hasFull) for (String fp : reqFiles) {
            String content = fullDocuments.get(fp);
            if (content != null) {
                if (content.length() > 40000) content = content.substring(0, 40000);
                String lang = inferLang(fp);
                ctx.append("### `").append(fp).append("` (full file)\n```").append(lang).append("\n")
                  .append(content).append("\n```\n\n");
            }
        }
        return ctx.length() > 0 ? ctx.toString() : "(no context available)";
    }

    private String inferLang(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".java")) return "java";
        if (p.endsWith(".py")) return "python";
        if (p.endsWith(".js")) return "javascript";
        if (p.endsWith(".ts")) return "typescript";
        if (p.endsWith(".jsx")) return "jsx";
        if (p.endsWith(".tsx")) return "tsx";
        if (p.endsWith(".vue")) return "vue";
        if (p.endsWith(".go")) return "go";
        if (p.endsWith(".rs")) return "rust";
        if (p.endsWith(".kt")) return "kotlin";
        if (p.endsWith(".c") || p.endsWith(".h")) return "c";
        if (p.endsWith(".cpp") || p.endsWith(".hpp")) return "cpp";
        if (p.endsWith(".sql")) return "sql";
        if (p.endsWith(".sh")) return "bash";
        if (p.endsWith(".yml") || p.endsWith(".yaml")) return "yaml";
        if (p.endsWith(".json")) return "json";
        if (p.endsWith(".xml")) return "xml";
        if (p.endsWith(".md")) return "markdown";
        if (p.endsWith(".css")) return "css";
        if (p.endsWith(".scss")) return "scss";
        if (p.endsWith(".html")) return "html";
        if (p.endsWith(".properties")) return "properties";
        return "";
    }

    // ── SSE streaming ──

    private String callLLMStream(SseEmitter emitter, List<ChatMessage> messages) {
        StringBuilder fullAnswer = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);

        chatModel.generate(messages, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                fullAnswer.append(token);
                try {
                    sendEvent(emitter, "response", token);
                } catch (Exception ignored) {}
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                System.err.println("[LLM] Error: " + error.getMessage());
                try {
                    sendEvent(emitter, "error", error.getMessage());
                } catch (Exception ignored) {}
                latch.countDown();
            }
        });

        try { latch.await(120, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        return fullAnswer.toString().trim();
    }

    private void sendEvent(SseEmitter emitter, String type, String content) {
        try {
            Map<String, String> event = new LinkedHashMap<>();
            event.put("type", type);
            event.put("content", content);
            String json = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event().data(json));
        } catch (IOException e) { /* connection closed, ignore */ }
    }

    // ── Thinking indicators ──

    private void emitThought(SseEmitter emitter, String q, List<Document> top, Map<String, List<String>> grp) {
        try {
            Set<String> srcs = new LinkedHashSet<>();
            for (Document c : top) srcs.add(c.getMetadata().getOrDefault("source","unknown"));
            sendEvent(emitter, "thought", "Searching: " + qPreview(q));
            Thread.sleep(200);
            sendEvent(emitter, "thought", "Found " + top.size() + " relevant chunks across " + srcs.size() + " files");
            Thread.sleep(200);
            List<String> sl = new ArrayList<>(srcs);
            if (!sl.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(5, sl.size()); i++) {
                    if (i > 0) sb.append(", ");
                    String s = sl.get(i); int slash = s.lastIndexOf('/');
                    sb.append(slash >= 0 ? s.substring(slash + 1) : s);
                }
                if (sl.size() > 5) sb.append(" +").append(sl.size() - 5).append(" more");
                sendEvent(emitter, "thought", sb.toString());
            }
            Thread.sleep(200);
            emitter.send(SseEmitter.event().data("{\"type\":\"thought_done\"}"));
        } catch (Exception e) { /* ignore */ }
    }

    private void emitEvidence(SseEmitter emitter, String baseUrl, Map<String, List<String>> grouped) {
        List<String> srcs = new ArrayList<>(grouped.keySet());
        if (srcs.isEmpty()) return;
        StringBuilder ev = new StringBuilder();
        for (int i = 0; i < Math.min(8, srcs.size()); i++)
            ev.append("- [").append(srcs.get(i)).append("](")
              .append(baseUrl).append(srcs.get(i)).append(")\n");
        try {
            sendEvent(emitter, "evidence", ev.toString());
        } catch (Exception e) { /* ignore */ }
    }

    // ── History (from SQLite, for UI) ──

    public List<Map<String, String>> getChatHistory(String slug) {
        return historyStore.getHistory(slug != null ? slug : repoSlug);
    }

    public Map<String, Object> getStats() { return new LinkedHashMap<>(analysisStats); }

    // ── Utils ──

    private static String qPreview(String q) {
        return q.length() > 60 ? q.substring(0, 60) + "..." : q;
    }
}
