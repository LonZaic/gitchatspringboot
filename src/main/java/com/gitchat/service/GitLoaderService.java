package com.gitchat.service;

import com.gitchat.model.Document;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

/**
 * GitHub仓库下载器 —— 相当于 Python 的 github_loader.py
 * 
 * 干的事：git clone → 读文件 → 删临时文件夹
 * 就像外卖员：去餐厅取餐 → 带回厨房 → 扔掉包装盒
 */
public class GitLoaderService {

    private static final Set<String> EXCLUDE_DIRS = Set.of(
        ".git", "node_modules", "__pycache__", "venv", ".venv",
        ".idea", ".vscode", "dist", "build", ".next", "target"
    );

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
        ".py", ".js", ".ts", ".jsx", ".tsx", ".vue", ".java",
        ".kt", ".go", ".rs", ".c", ".cpp", ".h", ".hpp",
        ".md", ".txt", ".yml", ".yaml", ".toml", ".json",
        ".xml", ".gradle", ".properties", ".env", ".cfg", ".ini",
        ".sql", ".sh", ".bat", ".dockerfile", ".css", ".scss", ".html"
    );

    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024; // 2MB

    private final Map<String, List<Document>> docCache = new ConcurrentHashMap<>();

    /**
     * 加载GitHub仓库
     * @param repoUrl 仓库URL，如 https://github.com/langchain-ai/langchain
     * @return 文档列表 + 是否命中缓存
     */
    public RepoLoadResult loadRepo(String repoUrl) throws Exception {
        String repoKey = normalizeRepoKey(repoUrl);
        System.out.println("[GitLoader] 处理仓库: " + repoKey);

        // 检查缓存（第22课学过的）
        if (docCache.containsKey(repoKey)) {
            System.out.println("[GitLoader] 命中缓存!");
            return new RepoLoadResult(docCache.get(repoKey), true);
        }

        // 创建临时文件夹
        String tempDir = Files.createTempDirectory("gitchat_").toString();
        System.out.println("[GitLoader] 临时目录: " + tempDir);

        try {
            // git clone --depth 1（第22课）
            String cloneUrl = "https://github.com/" + repoKey + ".git";
            cloneRepo(cloneUrl, tempDir);

            // 扫描文件夹，读所有代码文件（第22.1课 os.walk）
            List<Document> documents = scanFiles(tempDir);

            // 删掉临时文件夹（第22课：读完就删）
            deleteDirectory(new File(tempDir));

            // 存到缓存
            docCache.put(repoKey, documents);
            System.out.println("[GitLoader] 加载完成: " + documents.size() + " 个文件");
            return new RepoLoadResult(documents, false);

        } catch (Exception e) {
            // 出错也要删掉临时文件夹
            deleteDirectory(new File(tempDir));
            throw e;
        }
    }

    /** 执行 git clone（第22课 subprocess.run） */
    private void cloneRepo(String url, String targetDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "git", "clone", "--depth", "1", url, targetDir
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 最多等180秒
        boolean finished = process.waitFor(180, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Git clone 超时（超过180秒）");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String output = new String(process.getInputStream().readAllBytes());
            throw new RuntimeException("Git clone 失败: " + output);
        }
    }

    /** 扫描文件夹读文件 —— 相当于 Python 的 os.walk（第22.1课）*/
    private List<Document> scanFiles(String rootDir) throws IOException {
        List<Document> documents = new ArrayList<>();
        Path root = Path.of(rootDir);

        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString().toLowerCase();
                // 黑名单文件夹跳过（第22.1课的 exclude_dirs）
                if (EXCLUDE_DIRS.contains(dirName)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // 检查文件大小
                if (attrs.size() > MAX_FILE_SIZE) return FileVisitResult.CONTINUE;
                if (attrs.size() == 0) return FileVisitResult.CONTINUE;

                // 检查文件后缀
                String fileName = file.getFileName().toString().toLowerCase();
                String ext = "";
                int dotIdx = fileName.lastIndexOf('.');
                if (dotIdx >= 0) ext = fileName.substring(dotIdx);

                if (!ALLOWED_EXTENSIONS.contains(ext)) return FileVisitResult.CONTINUE;

                // 读文件内容（第22课 open(file_path, "r")）
                String content;
                try {
                    content = Files.readString(file);
                } catch (IOException e) {
                    return FileVisitResult.CONTINUE;  // 读不了就跳过
                }

                // 包装成 Document 对象（贴标签+装袋）
                String relativePath = root.relativize(file).toString().replace("\\", "/");
                Map<String, String> metadata = new HashMap<>();
                metadata.put("source", relativePath);
                documents.add(new Document(content, metadata));

                return FileVisitResult.CONTINUE;
            }
        });

        return documents;
    }

    /** 把仓库地址整理成统一格式 owner/repo（第22课 normalize_repo_key） */
    public static String normalizeRepoKey(String url) {
        String key = url.trim();
        // 去掉 https://github.com/
        if (key.startsWith("https://github.com/")) {
            key = key.substring(19);
        }
        if (key.startsWith("http://github.com/")) {
            key = key.substring(18);
        }
        // 去掉末尾的 .git
        if (key.endsWith(".git")) {
            key = key.substring(0, key.length() - 4);
        }
        // 去掉末尾的 /
        if (key.endsWith("/")) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }

    /** 递归删文件夹（第22课 shutil.rmtree） */
    private void deleteDirectory(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirectory(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    /** 加载结果（文档列表 + 是否命中缓存） */
    public record RepoLoadResult(List<Document> documents, boolean cacheHit) {}
}
