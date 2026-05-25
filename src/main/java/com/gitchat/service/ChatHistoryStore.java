package com.gitchat.service;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天记录持久化 —— 相当于 Python 的 chat_history_store.py
 * 用 SQLite 把聊天记录存到硬盘，关了电脑也不会丢
 */
public class ChatHistoryStore {
    private final String dbPath;

    public ChatHistoryStore() {
        // 数据库文件就在项目根目录，DB Browser 可以直接打开
        this.dbPath = System.getProperty("user.dir")
                + java.io.File.separator + "chat_history.db";
        initDb();
    }

    public ChatHistoryStore(String dbPath) {
        this.dbPath = dbPath;
        initDb();
    }

    /** 建表（如果还没有的话） */
    private void initDb() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    repo_slug TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
            """);
        } catch (SQLException e) {
            System.err.println("[ChatHistory] 初始化数据库失败: " + e.getMessage());
        }
    }

    /** 保存一对问答 */
    public void saveQaPair(String repoSlug, String question, String answer) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String sql = "INSERT INTO chat_messages (repo_slug, role, content, created_at) VALUES (?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repoSlug);
            ps.setString(2, "user");
            ps.setString(3, question);
            ps.setString(4, now);
            ps.executeUpdate();

            ps.setString(2, "assistant");
            ps.setString(3, answer);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ChatHistory] 保存失败: " + e.getMessage());
        }
    }

    /** 获取某个仓库的聊天记录 */
    public List<Map<String, String>> getHistory(String repoSlug) {
        List<Map<String, String>> history = new ArrayList<>();
        String sql = "SELECT role, content FROM chat_messages WHERE repo_slug = ? ORDER BY id ASC";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repoSlug);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, String> msg = new HashMap<>();
                msg.put("role", rs.getString("role"));
                msg.put("content", rs.getString("content"));
                history.add(msg);
            }
        } catch (SQLException e) {
            System.err.println("[ChatHistory] 查询失败: " + e.getMessage());
        }
        return history;
    }

    /** 清空某仓库的聊天记录 */
    public void clearRepo(String repoSlug) {
        String sql = "DELETE FROM chat_messages WHERE repo_slug = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repoSlug);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ChatHistory] 清空失败: " + e.getMessage());
        }
    }
}
