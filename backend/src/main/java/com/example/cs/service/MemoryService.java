package com.example.cs.service;

import com.example.cs.config.AiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * ★ 长期记忆服务：
 * 1. 前端每次对话更新时把完整对话 POST 到 /api/memory/sync
 * 2. 本服务对比快照哈希：
 *    - 纯追加（新对话）→ 增量提炼（旧事实 + 新消息 → 新事实表，省 token）
 *    - 检测到删除/改动 → 全量重提炼（保证"删除消息对记忆生效"）
 *    - 无变化 → 跳过
 * 3. 提炼结果（事实列表，带防抖）落盘 memory.json
 * 4. ChatService.buildSystemPrompt 读取事实注入 system prompt
 *
 * ★ 向量记忆升级：embedding 模型配置后（chat 组 embeddingModel），
 *    - 每条事实存 embedding 向量，注入 prompt 前按与用户消息的语义相似度取 top-K
 *    - 条数上限从 30 放宽到 150（检索式注入不怕多），不再"新记忆挤掉旧记忆"
 *    - 向量不可用/失败时自动回退全量注入（旧行为），保证功能永不劣化
 *
 * ★ 视觉记忆：主进程定期截屏 → VL 生成描述 → addVisualFact 存档（带时间戳），
 *    与对话事实共用向量检索池，AI 能"记得"用户最近在屏幕上做什么
 */
@Service
public class MemoryService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryService.class);

    private static final int FACTS_MAX_PLAIN = 30;    // 未启用向量时的条数上限（全量注入 prompt，防膨胀）
    private static final int FACTS_MAX_VECTOR = 150;  // 向量检索模式下的条数上限（按需取 top-K，不怕多）
    private static final int VISUAL_MAX = 100;        // 视觉观察条数上限（超出删最旧）
    private static final int PROMPT_TOPK = 10;        // 向量检索时注入 prompt 的最大条数
    private static final int EXTRACT_MAX_MSGS = 100;  // 全量提炼时最多携带的对话条数
    private static final long DEBOUNCE_MS = 5000;     // 防抖：对话连续更新时只提炼一次

    private volatile ChatClient extractClient;        // 独立的轻量 client（无工具），仅用于提炼（★ 可热替换）
    private final ObjectMapper mapper = new ObjectMapper();

    // 记忆持久化文件（项目运行目录下）
    private final File memoryFile = new File("memory.json");
    private final File visualFile = new File("memory-visual.json");
    private final File snapshotFile = new File("memory-snapshot.json");

    /** 记忆条目：text 为内容，emb 为 embedding 向量（null=未向量化，注入时回退全量） */
    public static class Fact {
        public String text;
        public double[] emb;
        public Fact() {}
        public Fact(String text) { this.text = text; }
    }

    private List<Fact> facts = new ArrayList<>();          // 对话提炼的事实
    private List<Fact> visualFacts = new ArrayList<>();    // 视觉观察（截屏 VL 描述）
    private String lastSnapshotHash = "";
    private String lastSnapshotJson = "[]";

    // ★ 向量记忆配置：复用对话组 url/key，embedding 模型留空=禁用（回退全量注入）
    private volatile String embUrl = "";   // 完整 embeddings 端点
    private volatile String embKey = "";
    private volatile String embModel = "";

    private final ScheduledExecutorService debouncePool = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "memory-extract");
        t.setDaemon(true);
        return t;
    });
    private final Object pendingLock = new Object();

    public MemoryService(@Value("${memory.enabled:true}") boolean enabled,
                         AiConfigService aiConfigService) {
        rebuildClient(aiConfigService.get()); // 与对话同 key 同模型，但无工具
        loadMemory();
        loadVisual();
        loadSnapshot();
        if (!enabled) logger.info("MemoryService disabled by config");
    }

    /** ★ AI 配置变更后重建提炼客户端（用对话组配置，仅无工具）+ 更新 embedding 配置 */
    public void rebuildClient(AiConfig cfg) {
        org.springframework.ai.openai.api.OpenAiApi api = org.springframework.ai.openai.api.OpenAiApi.builder()
                .baseUrl(cfg.getChat().getBaseUrl().trim())
                .apiKey(cfg.getChat().getApiKey() == null ? "" : cfg.getChat().getApiKey().trim())
                .build();
        this.extractClient = ChatClient.builder(
                        org.springframework.ai.openai.OpenAiChatModel.builder()
                                .openAiApi(api)
                                .defaultOptions(
                                        org.springframework.ai.openai.OpenAiChatOptions.builder()
                                                .model(cfg.getChat().getModel().trim()).build())
                                .build())
                .build();
        // ★ embedding 配置：模型留空=禁用向量记忆；url 按 baseUrl 是否已带 /v1 规范化拼接
        String model = cfg.getChat().getEmbeddingModel();
        if (model != null && !model.isBlank()) {
            String base = cfg.getChat().getBaseUrl().trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            // baseUrl 以 /v数字 结尾（如智谱 /api/paas/v4、SiliconFlow /v1）→ 直接拼 /embeddings；否则默认 /v1/embeddings
            this.embUrl = (base.matches("^.*/v\\d+$") ? base + "/embeddings" : base + "/v1/embeddings");
            this.embKey = cfg.getChat().getApiKey() == null ? "" : cfg.getChat().getApiKey().trim();
            this.embModel = model.trim();
            logger.info("向量记忆已启用，embedding 端点: {}，模型: {}", embUrl, embModel);
        } else {
            this.embUrl = "";
            this.embModel = "";
        }
    }

    private boolean embeddingEnabled() {
        String m = embModel;
        return m != null && !m.isBlank();
    }

    /** 向量模式下放宽条数上限：检索式注入不怕多，记忆不再被"挤掉" */
    private int factsMax() { return embeddingEnabled() ? FACTS_MAX_VECTOR : FACTS_MAX_PLAIN; }

    /**
     * 供 buildSystemPrompt 注入的记忆文本（空记忆返回空串）。
     * ★ query 非空且向量可用时按语义相似度取 top-K（对话事实 + 视觉观察合并检索），
     * 否则全量注入（旧行为，向量关闭/失败/条目过少时）
     */
    public synchronized String getMemoryPrompt(String query) {
        if (facts.isEmpty() && visualFacts.isEmpty()) return "";
        List<Fact> pool = new ArrayList<>(facts);
        pool.addAll(visualFacts);
        List<Fact> selected = pool;
        if (embeddingEnabled() && query != null && !query.isBlank() && pool.size() > PROMPT_TOPK) {
            ensureEmbeddings(pool); // 懒补算：旧记忆/新增条目缺失向量时补齐
            boolean ready = pool.stream().allMatch(f -> f.emb != null && f.emb.length > 0);
            if (ready) {
                try {
                    double[] qv = callEmbeddings(List.of(query)).get(0);
                    selected = topK(pool, qv, PROMPT_TOPK);
                } catch (Exception e) {
                    logger.warn("记忆向量检索失败，回退全量注入: {}", e.getMessage());
                }
            }
        }
        return renderFacts(selected);
    }

    /** 兼容旧调用（无 query → 全量注入，提炼 prompt 的 oldFacts 也走这里） */
    public synchronized String getMemoryPrompt() {
        if (facts.isEmpty()) return "";
        return renderFacts(facts);
    }

    private String renderFacts(List<Fact> list) {
        if (list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【长期记忆】以下是你与用户相处中记住的重要事实，可在聊天中自然运用：\n");
        for (int i = 0; i < list.size(); i++) {
            sb.append(i + 1).append(". ").append(list.get(i).text).append('\n');
        }
        return sb.toString();
    }

    /** ★ 视觉记忆：主进程定期截屏 → VL 生成描述 → 存档（带时间戳，向量化后参与检索） */
    public void addVisualFact(String text) {
        if (text == null || text.isBlank()) return;
        String stamped = "【视觉观察 " + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) + "】" + text.trim();
        final String factText = stamped;
        debouncePool.execute(() -> {
            try {
                synchronized (this) {
                    visualFacts.add(new Fact(factText));
                    while (visualFacts.size() > VISUAL_MAX) visualFacts.remove(0);
                    ensureEmbeddings(visualFacts);
                    saveVisual();
                    logger.info("视觉记忆已更新，共 {} 条", visualFacts.size());
                }
            } catch (Exception e) {
                logger.error("视觉记忆保存失败", e);
            }
        });
    }

    // ---------- embedding 调用（OpenAI 兼容 /v1/embeddings） ----------

    /** 批量补算列表中 emb 为空的条目；失败时该批保持 null（检索回退全量注入） */
    private void ensureEmbeddings(List<Fact> list) {
        if (!embeddingEnabled() || list.isEmpty()) return;
        List<String> todo = new ArrayList<>();
        for (Fact f : list) if (f.emb == null && f.text != null && !f.text.isBlank()) todo.add(f.text);
        if (todo.isEmpty()) return;
        try {
            List<double[]> vecs = callEmbeddings(todo);
            int i = 0;
            for (Fact f : list) {
                if (f.emb == null && f.text != null && !f.text.isBlank() && i < vecs.size()) {
                    f.emb = vecs.get(i++);
                }
            }
        } catch (Exception e) {
            logger.warn("embedding 计算失败，记忆将回退全量注入: {}", e.getMessage());
        }
    }

    private List<double[]> callEmbeddings(List<String> inputs) throws Exception {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        String json = org.springframework.web.client.RestClient.builder()
                .requestFactory(factory)
                .build()
                .post()
                .uri(embUrl)
                .header("Authorization", "Bearer " + embKey)
                .header("Content-Type", "application/json")
                .body(Map.of("model", embModel, "input", inputs))
                .retrieve()
                .body(String.class);
        JsonNode data = mapper.readTree(json).path("data");
        List<double[]> out = new ArrayList<>();
        for (JsonNode d : data) {
            JsonNode arr = d.path("embedding");
            double[] v = new double[arr.size()];
            for (int i = 0; i < v.length; i++) v[i] = arr.get(i).asDouble();
            out.add(v);
        }
        return out;
    }

    /** 余弦相似度 top-K */
    private List<Fact> topK(List<Fact> pool, double[] qv, int k) {
        List<Map.Entry<Fact, Double>> scored = new ArrayList<>();
        for (Fact f : pool) scored.add(Map.entry(f, cosine(qv, f.emb)));
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<Fact> out = new ArrayList<>();
        for (int i = 0; i < Math.min(k, scored.size()); i++) out.add(scored.get(i).getKey());
        return out;
    }

    private double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // ---------- 对话同步与提炼（原有逻辑，Fact 化改造） ----------

    /**
     * 对话同步入口（前端 notifyConvUpdate 时调用）。
     * 纯追加 → 增量提炼；检测到删除/改动 → 全量重提炼；无变化 → 跳过。
     */
    public synchronized void syncConversation(List<Map<String, String>> messages) {
        String json = toJson(messages);
        String hash = sha256(json);
        if (hash.equals(lastSnapshotHash)) return; // 无变化，跳过（SSE 每个 segment 都会 sync 一次）

        // 判断是否纯追加：旧快照是新列表的前缀（按条目数与内容）
        boolean pureAppend = isPureAppend(messages);

        lastSnapshotHash = hash;
        lastSnapshotJson = json;
        saveSnapshot(json);

        final List<Map<String, String>> msgs = messages;
        final boolean incremental = pureAppend;
        scheduleExtract(msgs, incremental);
    }

    // ★ 防抖状态：始终提取"最新"的对话快照（旧实现会让后到的更新被合并掉却提取旧数据）
    private List<Map<String, String>> pendingMsgs;
    private boolean pendingIncremental;
    private java.util.concurrent.ScheduledFuture<?> pendingFuture;

    /** 防抖调度：DEBOUNCE_MS 内连续 sync 只提炼最后一次，且防抖窗口随之重置 */
    private void scheduleExtract(List<Map<String, String>> msgs, boolean incremental) {
        synchronized (pendingLock) {
            pendingMsgs = msgs;        // 覆盖为最新数据
            pendingIncremental = incremental;
            if (pendingFuture != null && !pendingFuture.isDone()) {
                pendingFuture.cancel(false); // 重置防抖窗口，合并高频更新
            }
            pendingFuture = debouncePool.schedule(() -> {
                List<Map<String, String>> snapshot;
                boolean inc;
                synchronized (pendingLock) {
                    snapshot = pendingMsgs;
                    inc = pendingIncremental;
                    pendingMsgs = null;
                    pendingFuture = null;
                }
                try {
                    doExtract(snapshot, inc);
                } catch (Exception e) {
                    logger.error("记忆提炼失败", e);
                }
            }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** 调 LLM 提炼事实（在防抖线程池中执行，不阻塞 sync 请求）；完成后向量化新事实 */
    private void doExtract(List<Map<String, String>> messages, boolean incremental) {
        try {
            String prompt = buildExtractPrompt(messages, incremental);
            String reply = extractClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            List<String> extracted = parseFacts(reply);
            if (extracted == null) {
                logger.warn("记忆提炼输出无法解析，保留旧记忆");
                return;
            }
            synchronized (this) {
                facts = new ArrayList<>();
                for (String t : extracted) facts.add(new Fact(t));
                ensureEmbeddings(facts); // 后台线程中向量化，不阻塞对话请求
                saveMemory();
                logger.info("记忆已更新，共 {} 条事实（{}）", facts.size(), incremental ? "增量" : "全量");
            }
        } catch (Exception e) {
            logger.error("记忆提炼异常，保留旧记忆", e);
        }
    }

    /** 构建提炼 prompt：全量 / 增量 两种模式 */
    private String buildExtractPrompt(List<Map<String, String>> messages, boolean incremental) {
        StringBuilder dialog = new StringBuilder();
        int from = Math.max(0, messages.size() - EXTRACT_MAX_MSGS);
        for (int i = from; i < messages.size(); i++) {
            Map<String, String> m = messages.get(i);
            String role = "user".equals(m.get("role")) ? "用户" : "角色";
            dialog.append(role).append(": ").append(m.get("text")).append('\n');
        }
        // 增量模式基于已有对话事实（不含视觉观察，视觉记忆由截屏链路独立维护）
        String oldFacts = incremental ? renderFacts(facts) : "";

        if (incremental) {
            return "你是一个记忆管理模块。下面是已有的长期记忆列表，以及" + from + "条之后的新增对话。" +
                    "请综合两者输出更新后的完整记忆列表。\n" +
                    "要求：\n" +
                    "- 只记录值得长期记住的事实（用户偏好、个人信息、重要承诺、共同经历等）\n" +
                    "- 最多 " + factsMax() + " 条，超出时合并或舍弃不重要的\n" +
                    "- 每条一句话，简明具体，可带时间信息\n" +
                    "- 只输出 JSON 字符串数组，不要任何其他文字\n\n" +
                    (oldFacts.isEmpty() ? "" : oldFacts + "\n") +
                    "【新增对话】\n" + dialog;
        }
        return "你是一个记忆管理模块。请从以下对话中提炼值得长期记住的事实。\n" +
                "要求：\n" +
                "- 只记录用户偏好、个人信息、重要承诺、共同经历等长期有效的事实\n" +
                "- 最多 " + factsMax() + " 条，每条一句话，简明具体\n" +
                "- 只输出 JSON 字符串数组，不要任何其他文字\n\n" +
                "【对话记录】\n" + dialog;
    }

    /** 容错解析 LLM 输出中的 JSON 字符串数组 */
    private List<String> parseFacts(String reply) {
        if (reply == null || reply.isBlank()) return null;
        try {
            int s = reply.indexOf('[');
            int e = reply.lastIndexOf(']');
            if (s == -1 || e == -1 || e <= s) return null;
            JsonNode node = mapper.readTree(reply.substring(s, e + 1));
            if (!node.isArray()) return null;
            List<String> result = new ArrayList<>();
            for (JsonNode item : node) {
                String text = item.asText().trim();
                if (!text.isEmpty()) result.add(text);
            }
            int max = factsMax();
            if (result.size() > max) result = new ArrayList<>(result.subList(0, max));
            return result;
        } catch (Exception ex) {
            return null;
        }
    }

    /** 判断新列表是否是旧快照的纯追加（前缀完全一致） */
    private boolean isPureAppend(List<Map<String, String>> messages) {
        try {
            JsonNode old = mapper.readTree(lastSnapshotJson);
            if (!old.isArray() || messages.size() < old.size()) return false;
            for (int i = 0; i < old.size(); i++) {
                JsonNode o = old.get(i);
                Map<String, String> n = messages.get(i);
                if (!o.path("role").asText().equals(n.get("role"))) return false;
                if (!o.path("text").asText().equals(n.get("text"))) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ★ 彻底清除全部记忆（对话事实 + 向量 + 视觉观察 + 对话快照），恢复"初见"状态。
     * 由托盘"清除全部记忆与聊天记录"调用；同时取消进行中的提炼任务，防止旧数据回写
     */
    public synchronized void clearAll() {
        // 取消防抖中/执行中的提炼，避免清除后旧对话被重新提炼写回
        synchronized (pendingLock) {
            if (pendingFuture != null) {
                pendingFuture.cancel(false);
                pendingFuture = null;
                pendingMsgs = null;
            }
        }
        facts = new ArrayList<>();
        visualFacts = new ArrayList<>();
        lastSnapshotHash = "";
        lastSnapshotJson = "[]";
        deleteQuietly(memoryFile);
        deleteQuietly(visualFile);
        deleteQuietly(snapshotFile);
        logger.info("已彻底清除全部记忆（事实/向量/视觉/快照）");
    }

    private void deleteQuietly(File f) {
        try {
            if (f.exists()) Files.deleteIfExists(f.toPath());
        } catch (Exception e) {
            logger.warn("删除记忆文件失败: {}", f.getName());
        }
    }

    private String toJson(List<Map<String, String>> messages) {
        try {
            return mapper.writeValueAsString(messages);
        } catch (Exception e) {
            return String.valueOf(messages.size());
        }
    }

    private String sha256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return data;
        }
    }

    // ---------- 持久化（memory.json 兼容旧格式 List<String>；视觉记忆独立文件） ----------

    private void loadMemory() {
        try {
            if (memoryFile.exists()) {
                JsonNode node = mapper.readTree(memoryFile);
                List<Fact> list = new ArrayList<>();
                node.path("facts").forEach(f -> {
                    if (f.isTextual()) {
                        list.add(new Fact(f.asText())); // 旧格式：纯字符串
                    } else {
                        Fact fact = new Fact(f.path("text").asText());
                        JsonNode e = f.path("emb");
                        if (e.isArray() && e.size() > 0) {
                            double[] v = new double[e.size()];
                            for (int i = 0; i < v.length; i++) v[i] = e.get(i).asDouble();
                            fact.emb = v;
                        }
                        list.add(fact);
                    }
                });
                facts = list;
            }
        } catch (Exception e) {
            logger.warn("加载记忆文件失败", e);
        }
    }

    private void saveMemory() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Fact f : facts) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("text", f.text);
                if (f.emb != null) m.put("emb", f.emb);
                out.add(m);
            }
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(memoryFile, Map.of("facts", out, "updatedAt", System.currentTimeMillis()));
        } catch (Exception e) {
            logger.warn("保存记忆文件失败", e);
        }
    }

    private void loadVisual() {
        try {
            if (visualFile.exists()) {
                JsonNode node = mapper.readTree(visualFile);
                List<Fact> list = new ArrayList<>();
                node.path("facts").forEach(f -> {
                    Fact fact = new Fact(f.path("text").asText());
                    JsonNode e = f.path("emb");
                    if (e.isArray() && e.size() > 0) {
                        double[] v = new double[e.size()];
                        for (int i = 0; i < v.length; i++) v[i] = e.get(i).asDouble();
                        fact.emb = v;
                    }
                    list.add(fact);
                });
                visualFacts = list;
            }
        } catch (Exception e) {
            logger.warn("加载视觉记忆文件失败", e);
        }
    }

    private void saveVisual() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Fact f : visualFacts) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("text", f.text);
                if (f.emb != null) m.put("emb", f.emb);
                out.add(m);
            }
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(visualFile, Map.of("facts", out, "updatedAt", System.currentTimeMillis()));
        } catch (Exception e) {
            logger.warn("保存视觉记忆文件失败", e);
        }
    }

    private void loadSnapshot() {
        try {
            if (snapshotFile.exists()) {
                lastSnapshotJson = Files.readString(snapshotFile.toPath(), StandardCharsets.UTF_8);
                lastSnapshotHash = sha256(lastSnapshotJson);
            }
        } catch (Exception e) {
            logger.warn("加载对话快照失败", e);
        }
    }

    private void saveSnapshot(String json) {
        try {
            Files.writeString(snapshotFile.toPath(), json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warn("保存对话快照失败", e);
        }
    }
}
