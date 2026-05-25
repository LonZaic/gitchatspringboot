package com.gitchat.model;

/**
 * 分析仓库的请求体 —— 用户POST过来的JSON
 * 相当于 Python 里 main.py 的 class RepoRequest(BaseModel): repo_url: str
 */
public class AnalyzeRequest {
    private String repoUrl;    // 用户输入的GitHub仓库地址

    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
}
