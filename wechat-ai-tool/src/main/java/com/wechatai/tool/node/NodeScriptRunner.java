package com.wechatai.tool.node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 通用 Node 脚本通道 — 白名单脚本 + ProcessBuilder。
 * <p>
 * 后续扩展：在 {@code scripts/} 下新增 {@code xxx.js}，业务工具调用
 * {@link #run(String, List)} 即可，无需改 Agent 编排。
 */
@Component
public class NodeScriptRunner {

    private static final Logger log = LoggerFactory.getLogger(NodeScriptRunner.class);

    /** 仅允许简单文件名，禁止路径穿越 */
    private static final Pattern SAFE_SCRIPT = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]*\\.js$");

    private final boolean enabled;
    private final String nodeExecutable;
    private final Path scriptsDir;
    private final long timeoutMs;

    public NodeScriptRunner(
            @Value("${node.scripts.enabled:true}") boolean enabled,
            @Value("${node.executable:node}") String nodeExecutable,
            @Value("${node.scripts.dir:./scripts}") String scriptsDir,
            @Value("${node.script-timeout-ms:20000}") long timeoutMs) {
        this.enabled = enabled;
        this.nodeExecutable = nodeExecutable;
        this.scriptsDir = resolveScriptsDir(scriptsDir);
        this.timeoutMs = Math.max(3000, timeoutMs);
        log.info("✅ NodeScriptRunner: enabled={}, node={}, scriptsDir={}, timeout={}ms",
                enabled, nodeExecutable, this.scriptsDir.toAbsolutePath(), this.timeoutMs);
    }

    /**
     * 执行白名单脚本。
     *
     * @param scriptName 如 web-search.js（不可含路径）
     * @param args       传给脚本的参数
     * @return stdout 文本；失败/禁用/超时返回 empty
     */
    public Optional<String> run(String scriptName, List<String> args) {
        if (!enabled) {
            return Optional.empty();
        }
        if (scriptName == null || !SAFE_SCRIPT.matcher(scriptName).matches()) {
            log.warn("【NodeScript】拒绝非法脚本名: {}", scriptName);
            return Optional.empty();
        }

        Path script = scriptsDir.resolve(scriptName).normalize();
        if (!script.startsWith(scriptsDir.normalize()) || !Files.isRegularFile(script)) {
            log.warn("【NodeScript】脚本不存在或不在白名单目录: {}", script);
            return Optional.empty();
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(nodeExecutable);
        cmd.add(script.toAbsolutePath().toString());
        if (args != null) {
            cmd.addAll(args);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(scriptsDir.toFile());
        pb.redirectErrorStream(false);

        long start = System.currentTimeMillis();
        try {
            Process process = pb.start();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread outReader = new Thread(() -> readStream(process.getInputStream(), stdout), "node-stdout");
            Thread errReader = new Thread(() -> readStream(process.getErrorStream(), stderr), "node-stderr");
            outReader.setDaemon(true);
            errReader.setDaemon(true);
            outReader.start();
            errReader.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("【NodeScript】{} 超时 ({}ms)", scriptName, timeoutMs);
                return Optional.empty();
            }
            outReader.join(1000);
            errReader.join(1000);

            int code = process.exitValue();
            String out = stdout.toString().strip();
            String err = stderr.toString().strip();
            long elapsed = System.currentTimeMillis() - start;

            if (!err.isBlank()) {
                log.info("【NodeScript】{} stderr: {}", scriptName,
                        err.length() > 300 ? err.substring(0, 300) + "..." : err);
            }

            if (code != 0 || out.isBlank()) {
                log.warn("【NodeScript】{} 失败 exit={} ({}ms) outLen={}",
                        scriptName, code, elapsed, out.length());
                return Optional.empty();
            }

            log.info("【NodeScript】{} 成功 ({}ms) outLen={}", scriptName, elapsed, out.length());
            return Optional.of(out);
        } catch (Exception e) {
            log.warn("【NodeScript】{} 执行异常: {}", scriptName, e.getMessage());
            return Optional.empty();
        }
    }

    private static void readStream(java.io.InputStream in, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sink.length() > 0) sink.append('\n');
                sink.append(line);
            }
        } catch (Exception ignored) {
            // process 结束后流关闭属正常
        }
    }

    private static Path resolveScriptsDir(String configured) {
        Path p = Paths.get(configured).toAbsolutePath().normalize();
        if (Files.isDirectory(p)) {
            return p;
        }
        // 从 user.dir 向上找 scripts/（兼容从 starter 子目录启动）
        Path cur = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i < 4; i++) {
            Path candidate = cur.resolve("scripts");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            Path parent = cur.getParent();
            if (parent == null) break;
            cur = parent;
        }
        return p;
    }
}
