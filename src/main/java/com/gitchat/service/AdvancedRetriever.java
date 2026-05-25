package com.gitchat.service;

import com.gitchat.model.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.time.Duration;
import java.util.*;

/**
 * 向量检索器 —— 用 LangChain4j 调 Embedding API + 纯 Java 余弦相似度搜索
 *
 * 原本依赖 FAISS Java 绑定（io.github.javpower:faiss-java），
 * 但该包未发布到 Maven Central，改用纯 Java 内存向量搜索。
 */
public class AdvancedRetriever {

    private final EmbeddingModel embeddingModel;

    // 内存向量存储：docIndex 的每个 entry 对应一个文档的 embedding 向量
    private final List<float[]> vectorStore = new ArrayList<>();
    private final List<Document> docList = new ArrayList<>();
    private final List<String> bm25Texts = new ArrayList<>();
    private int vectorDimension = 0;
    private boolean initialized = false;

    public AdvancedRetriever() {
        String apiKey = System.getenv().getOrDefault("OPENROUTER_API_KEY", "");
        String baseUrl = System.getenv().getOrDefault(
                "OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1");

        this.embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName("openai/text-embedding-3-small")
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * 建向量库 —— 每个文档调用 Embedding API 转成向量，存入内存列表
     */
    public void buildVectorStore(List<Document> documents) {
        vectorStore.clear();
        docList.clear();
        bm25Texts.clear();

        System.out.println("[VectorStore] 开始: " + documents.size() + " 个文档");

        if (documents.isEmpty()) return;

        // 第1步：取第一个文档确定向量维度
        try {
            String firstText = documents.get(0).getPageContent();
            if (firstText.length() > 8000) firstText = firstText.substring(0, 8000);
            Embedding firstEmb = embeddingModel.embed(firstText).content();
            vectorDimension = firstEmb.vector().length;
            System.out.println("[VectorStore] 向量维度: " + vectorDimension);
        } catch (Exception e) {
            System.err.println("[VectorStore] 无法获取向量维度: " + e.getMessage());
            return;
        }

        // 第2步：逐个文档 embedding，存入内存
        int count = 0;
        for (Document doc : documents) {
            try {
                String text = doc.getPageContent();
                if (text == null || text.trim().isEmpty()) continue;
                if (text.length() > 8000) text = text.substring(0, 8000);

                float[] vector = embeddingModel.embed(text).content().vector();

                vectorStore.add(vector);
                docList.add(doc);
                bm25Texts.add(doc.getPageContent());
                count++;

                if (count % 10 == 0)
                    System.out.println("[VectorStore] " + count + "/" + documents.size());
            } catch (Exception e) {
                System.err.println("[VectorStore] 第" + count + "个文档失败: " + e.getMessage());
            }
        }

        initialized = true;
        System.out.println("[VectorStore] 完成: " + count + " 个向量");
    }

    /**
     * 向量搜索 —— 用余弦相似度在内存中暴力搜索 topK
     */
    public List<Document> similaritySearch(String query, int topK) {
        if (!initialized || vectorStore.isEmpty()) return List.of();

        try {
            String q = query.length() > 8000 ? query.substring(0, 8000) : query;
            float[] queryVec = embeddingModel.embed(q).content().vector();

            // 计算所有向量的余弦相似度
            double[] similarities = new double[vectorStore.size()];
            for (int i = 0; i < vectorStore.size(); i++) {
                similarities[i] = cosineSimilarity(queryVec, vectorStore.get(i));
            }

            // 取 topK 个最大相似度
            Integer[] indices = new Integer[vectorStore.size()];
            for (int i = 0; i < indices.length; i++) indices[i] = i;
            Arrays.sort(indices, (a, b) -> Double.compare(similarities[b], similarities[a]));

            List<Document> results = new ArrayList<>();
            for (int i = 0; i < Math.min(topK, indices.length); i++) {
                int idx = indices[i];
                if (idx >= 0 && idx < docList.size()) {
                    results.add(docList.get(idx));
                }
            }
            return results;
        } catch (Exception e) {
            System.err.println("[VectorStore] 搜索失败: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * 混合搜索：向量(60%) + BM25关键词(40%)
     */
    public List<Document> ensembleSearch(String query, int topK) {
        if (!initialized) return similaritySearch(query, topK);

        List<Document> vecDocs = similaritySearch(query, topK * 2);
        Map<Integer, Double> vecScores = new LinkedHashMap<>();
        for (int i = 0; i < vecDocs.size(); i++) {
            int idx = docList.indexOf(vecDocs.get(i));
            if (idx >= 0) vecScores.put(idx, 1.0 / (i + 1));
        }

        Map<Integer, Double> bm25Scores = bm25Search(query, topK * 2);

        TreeMap<Double, Integer> combined = new TreeMap<>(Collections.reverseOrder());
        Set<Integer> allIdx = new LinkedHashSet<>();
        allIdx.addAll(vecScores.keySet());
        allIdx.addAll(bm25Scores.keySet());

        for (int idx : allIdx) {
            double score = vecScores.getOrDefault(idx, 0.0) * 0.6
                    + bm25Scores.getOrDefault(idx, 0.0) * 0.4;
            combined.put(score, idx);
        }

        List<Document> results = new ArrayList<>();
        for (int idx : combined.values()) {
            if (results.size() >= topK) break;
            if (idx >= 0 && idx < docList.size()) {
                Document d = docList.get(idx);
                if (!results.contains(d)) results.add(d);
            }
        }
        return results;
    }

    /**
     * 余弦相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    /**
     * BM25 简化版关键词搜索
     */
    private Map<Integer, Double> bm25Search(String query, int limit) {
        Map<Integer, Double> scores = new LinkedHashMap<>();
        String[] words = query.toLowerCase().split("[\\s,.!?;:，。！？；：]+");

        for (int i = 0; i < bm25Texts.size(); i++) {
            String text = bm25Texts.get(i).toLowerCase();
            double score = 0;
            for (String w : words) {
                if (w.length() < 2) continue;
                int count = 0, idx = 0;
                while ((idx = text.indexOf(w, idx)) >= 0) { count++; idx += w.length(); }
                if (count > 0) score += count * 2.0 / (1 + text.length() / 500.0);
            }
            if (score > 0) scores.put(i, score);
        }
        return scores;
    }
}
