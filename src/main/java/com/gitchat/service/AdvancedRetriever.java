package com.gitchat.service;

import com.gitchat.model.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhq.BgeSmallZhQuantizedEmbeddingModel;

import java.util.*;

/**
 * 向量检索器 —— 本地 AllMiniLML6V2 做 embedding + 余弦相似度搜索
 */
public class AdvancedRetriever {

    private final EmbeddingModel embeddingModel;

    private final List<float[]> vectorStore = new ArrayList<>();
    private final List<Document> docList = new ArrayList<>();
    private final List<String> bm25Texts = new ArrayList<>();
    private boolean initialized = false;

    public AdvancedRetriever() {
        this.embeddingModel = new BgeSmallZhQuantizedEmbeddingModel();
    }

    public void buildVectorStore(List<Document> documents) {
        vectorStore.clear();
        docList.clear();
        bm25Texts.clear();
        initialized = false;

        System.out.println("[VectorStore] 开始: " + documents.size() + " 个文档");

        if (documents.isEmpty()) return;

        for (Document doc : documents) {
            if (doc.getPageContent() == null || doc.getPageContent().trim().isEmpty()) continue;
            docList.add(doc);
            bm25Texts.add(doc.getPageContent());
        }

        int maxEmbed = Math.min(docList.size(), 1000);
        try {
            int count = 0;
            for (int i = 0; i < maxEmbed; i++) {
                Document doc = docList.get(i);
                try {
                    String text = doc.getPageContent();
                    if (text.length() > 8000) text = text.substring(0, 8000);
                    float[] vector = embeddingModel.embed(text).content().vector();
                    vectorStore.add(vector);
                    count++;
                    if (count % 50 == 0)
                        System.out.println("[VectorStore] " + count + "/" + maxEmbed);
                } catch (Exception e) {
                    System.err.println("[VectorStore] 第" + count + "个文档失败: " + e.getMessage());
                }
            }
            initialized = !vectorStore.isEmpty();
            System.out.println("[VectorStore] 完成: " + count + " 个向量 (维度="
                    + (initialized ? vectorStore.get(0).length : 0)
                    + "), BM25 覆盖 " + docList.size() + " 个文档");
        } catch (Exception e) {
            System.err.println("[VectorStore] Embedding 失败: " + e.getMessage());
            initialized = false;
        }
    }

    public List<Document> similaritySearch(String query, int topK) {
        if (!initialized || vectorStore.isEmpty()) return bm25OnlySearch(query, topK);

        try {
            String q = query.length() > 8000 ? query.substring(0, 8000) : query;
            float[] queryVec = embeddingModel.embed(q).content().vector();

            double[] similarities = new double[vectorStore.size()];
            for (int i = 0; i < vectorStore.size(); i++)
                similarities[i] = cosineSimilarity(queryVec, vectorStore.get(i));

            Integer[] indices = new Integer[vectorStore.size()];
            for (int i = 0; i < indices.length; i++) indices[i] = i;
            Arrays.sort(indices, (a, b) -> Double.compare(similarities[b], similarities[a]));

            List<Document> results = new ArrayList<>();
            for (int i = 0; i < Math.min(topK, indices.length); i++) {
                int idx = indices[i];
                if (idx >= 0 && idx < docList.size())
                    results.add(docList.get(idx));
            }
            return results;
        } catch (Exception e) {
            System.err.println("[VectorStore] 搜索失败: " + e.getMessage());
            return bm25OnlySearch(query, topK);
        }
    }

    public List<Document> ensembleSearch(String query, int topK) {
        if (!initialized || vectorStore.isEmpty()) return bm25OnlySearch(query, topK);

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

    public boolean isReady() {
        return !docList.isEmpty();
    }

    private List<Document> bm25OnlySearch(String query, int topK) {
        Map<Integer, Double> scores = bm25Search(query, topK);
        List<Document> results = new ArrayList<>();
        for (int idx : scores.keySet()) {
            if (results.size() >= topK) break;
            if (idx >= 0 && idx < docList.size())
                results.add(docList.get(idx));
        }
        if (results.isEmpty()) {
            int n = Math.min(topK, docList.size());
            results.addAll(docList.subList(0, n));
        }
        return results;
    }

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
