package com.gitchat.controller;

import com.gitchat.model.AnalyzeRequest;
import com.gitchat.model.Document;
import com.gitchat.service.ErrorCodes;
import com.gitchat.service.GitLoaderService;
import com.gitchat.service.RagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分析控制器 —— 相当于 Python main.py 里的 /analyze 路由
 * 接收"分析仓库"请求
 */
@RestController
@RequestMapping("/api")
public class AnalyzeController {

    private final GitLoaderService gitLoader = new GitLoaderService();
    private final RagService ragService = new RagService(); // 简化：单实例

    /**
     * POST /api/analyze
     * 用户发来仓库地址 → 下载代码 → 切分 → 建索引 → 返回结果
     */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyzeRepo(@RequestBody AnalyzeRequest request) {
        try {
            String repoUrl = request.getRepoUrl();
            String repoKey = GitLoaderService.normalizeRepoKey(repoUrl);

            System.out.println("[API] Analyze: " + repoKey);

            // 第一步：下载代码（github_loader 第22课）
            GitLoaderService.RepoLoadResult result = gitLoader.loadRepo(repoUrl);
            List<Document> docs = result.documents();

            // 第二步：处理文档（process_documents 第24课）
            ragService.processDocuments(docs, repoKey, repoKey);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("message", "Successfully analyzed " + repoKey);
            response.put("files", docs.size());
            response.put("cache_hit", result.cacheHit());
            response.put("stats", ragService.getStats());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            String code = ErrorCodes.classifyError(e);
            String msg = ErrorCodes.humanMessage(code);

            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "error");
            error.put("code", code);
            error.put("message", msg);
            error.put("detail", e.getMessage() != null ? e.getMessage() : "unknown");

            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * GET /api/metrics —— 获取统计信息
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("stats", ragService.getStats());
        return ResponseEntity.ok(metrics);
    }
}
