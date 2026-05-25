# GitChat (Spring Boot + LangChain4j + FAISS)

GitHub仓库AI问答系统 —— Python 版的 Java 实现

Python: FastAPI + LangChain + FAISS
Java:   Spring Boot + LangChain4j + FAISS

## Tech Stack (Python vs Java)

| Function | Java | Python |
|----------|------|--------|
| Web Framework | Spring Boot 3.2 | FastAPI |
| AI Framework | LangChain4j 0.34 | LangChain |
| Chat Model | OpenAiStreamingChatModel | ChatOpenAI(streaming=True) |
| Embedding | OpenAiEmbeddingModel | OpenAIEmbeddings |
| Vector Search | FAISS IndexFlatL2 | FAISS faiss-cpu |
| Keyword Search | BM25 (hand written) | BM25Retriever |
| Doc Splitter | DocumentByParagraphSplitter | RecursiveCharacterTextSplitter |
| Database | SQLite (sqlite-jdbc) | sqlite3 |
| Streaming | SseEmitter (Spring MVC) | SSE async generator |
| Config | dotenv-java | python-dotenv |
| AI Gateway | OpenRouter API (same) | OpenRouter API |

## Project Structure

src/main/java/com/gitchat/
  Application.java           - main entrance  (main.py)
  config/CorsConfig.java     - CORS config
  controller/
    AnalyzeController.java   - /api/analyze
    ChatController.java      - /api/chat (SSE)
  service/
    RagService.java          - RAG core       (rag_service.py)
    AdvancedRetriever.java   - FAISS+BM25     (advanced_retriever.py)
    GitLoaderService.java    - git clone      (github_loader.py)
    DocumentProcessor.java   - split docs     (document_processor.py)
    ChatHistoryStore.java    - SQLite history (chat_history_store.py)
    ErrorCodes.java          - error handler  (error_codes.py)
  model/
    Document.java / AnalyzeRequest.java / ChatRequest.java

## Run
1. JDK 21+, Maven 3.x
2. set OPENROUTER_API_KEY=your-key
3. mvn spring-boot:run
4. http://localhost:8000

## API
POST /api/analyze     { "repoUrl": "user/repo" }
POST /api/chat        { "message": "..." }  (SSE stream)
GET  /api/chat/history?repo_slug=xxx
GET  /api/metrics
