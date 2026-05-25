package com.gitchat.model;

/**
 * 聊天请求体
 * 相当于 Python 里 chat.py 的 class ChatRequest(BaseModel): message: str
 */
public class ChatRequest {
    private String message;  // 用户输入的问题

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
