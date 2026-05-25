package com.gitchat.service;

/**
 * 错误分类器 —— 相当于 Python 的 error_codes.py
 * 把电脑的"天书错误"翻译成"人话"
 */
public class ErrorCodes {

    /** 把错误信息分类成标准错误码 */
    public static String classifyError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("timed out") || msg.contains("timeout")) {
            return "timeout";
        }
        if (msg.contains("401")) {
            return "permission";
        }
        if (msg.contains("not found") || msg.contains("404")) {
            return "not_found";
        }
        return "unknown";
    }

    /** 把错误码翻译成人话 */
    public static String humanMessage(String code) {
        return switch (code) {
            case "timeout" -> "操作超时，请检查网络后重试";
            case "permission" -> "权限不足，请检查API密钥";
            case "not_found" -> "仓库或资源不存在";
            default -> "发生未知错误，请稍后重试";
        };
    }
}
