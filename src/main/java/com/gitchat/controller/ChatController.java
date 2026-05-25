package com.gitchat.controller;

import com.gitchat.model.ChatRequest;
import com.gitchat.service.RagService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天控制器 -- 相当于 Python 的 chat.py
 * 接收聊天消息，通过 SSE 流式返回 AI 回答
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final RagService ragService = new RagService();

    /**
     * POST /api/chat -- SSE 流式聊天
     * 返回格式: text/event-stream (SSE)
     * 每条数据: data: {"type":"response","content":"..."}
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        String question = request.getMessage();
        if (question == null || question.trim().isEmpty()) {
            SseEmitter err = new SseEmitter();
            try {
                err.send(SseEmitter.event().data("{\"type\":\"error\",\"content\":\"empty message\"}"));
                err.complete();
            } catch (Exception ignored) {}
            return err;
        }

        System.out.println("[Chat] Question: " + question.substring(0, Math.min(80, question.length())));

        // 调用核心服务，返回 SseEmitter
        return ragService.streamAnswer(question);
    }

    /**
     * GET /api/chat/history?repo_slug=xxx -- 获取聊天历史
     */
    @GetMapping("/chat/history")
    public ResponseEntity<Map<String, Object>> getChatHistory(
            @RequestParam(value = "repo_slug", defaultValue = "") String repoSlug) {
        Map<String, Object> response = new LinkedHashMap<>();
        List<Map<String, String>> history = ragService.getChatHistory(
                repoSlug.isEmpty() ? null : repoSlug);
        response.put("history", history);
        response.put("count", history.size());
        return ResponseEntity.ok(response);
    }
}
