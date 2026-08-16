package com.wechatai.ai.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 用户音色偏好持久化管理。
 * <p>
 * 将每个用户的持久音色偏好存储为 JSON 文件，进程重启后仍然有效。
 * 读写使用 {@link ReentrantReadWriteLock} 保证线程安全。
 * <p>
 * 存储路径通过 {@code tts.voice-config-path} 配置，默认为 {@code config/voice-preferences.json}。
 */
@Component
public class VoiceConfigManager {

    private static final Logger log = LoggerFactory.getLogger(VoiceConfigManager.class);

    /** CosyVoice v3-plus 全部有效系统音色（来源：阿里云官方文档 + SDK 源码） */
    public static final Set<String> VALID_VOICES = Set.of(
            "longanyang",       // 龙安洋 — 阳光大男孩
            "longanhuan",       // 龙安欢 — 欢脱元气女（默认）
            "longhuhu_v3",      // 龙呼呼 — 天真烂漫女童
            "longyingmu_v3"     // 龙应沐 — 优雅知性女声
    );

    private final ObjectMapper objectMapper;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Path configPath;

    /** 内存缓存，key=userId，value=voiceName */
    private volatile Map<String, String> cache;

    public VoiceConfigManager(@Value("${tts.voice-config-path:config/voice-preferences.json}") String configPathStr) {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.configPath = Paths.get(configPathStr);
        this.cache = new ConcurrentHashMap<>();
        load();
    }

    /**
     * 获取用户的持久音色偏好。
     * <p>
     * 如果存储的音色不在当前有效列表中（如模型更新导致音色下线），返回 null 以回退到默认音色。
     *
     * @param userId 微信用户 ID
     * @return 音色名称，未设置或无效时返回 null
     */
    public String getVoice(String userId) {
        lock.readLock().lock();
        try {
            String voice = cache.get(userId);
            if (voice != null && !VALID_VOICES.contains(voice)) {
                log.warn("【音色持久化】用户 {} 存储的音色 {} 已失效，回退默认", userId, voice);
                return null;
            }
            return voice;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 设置用户的持久音色偏好（立即写入磁盘）。
     * <p>
     * 仅接受 {@link #VALID_VOICES} 中的音色名，非法音色会被拒绝并记录警告。
     *
     * @param userId    微信用户 ID
     * @param voiceName 音色名称
     */
    public void setVoice(String userId, String voiceName) {
        if (voiceName == null || !VALID_VOICES.contains(voiceName)) {
            log.warn("【音色持久化】拒绝非法音色: userId={}, voice={}, 有效音色={}",
                    userId, voiceName, VALID_VOICES);
            return;
        }
        lock.writeLock().lock();
        try {
            cache.put(userId, voiceName);
            persist();
            log.info("【音色持久化】用户 {} 默认音色 → {}", userId, voiceName);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 清除用户的持久音色偏好（立即写入磁盘）。
     *
     * @param userId 微信用户 ID
     */
    public void clearVoice(String userId) {
        lock.writeLock().lock();
        try {
            String removed = cache.remove(userId);
            if (removed != null) {
                persist();
                log.info("【音色持久化】用户 {} 音色偏好已清除（之前为 {}）", userId, removed);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 从 JSON 文件加载音色偏好到内存缓存。
     */
    private void load() {
        if (!Files.exists(configPath)) {
            log.info("【音色持久化】配置文件不存在，使用空缓存: {}", configPath.toAbsolutePath());
            return;
        }
        try {
            String json = Files.readString(configPath);
            Map<String, String> loaded = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
            cache = new ConcurrentHashMap<>(loaded);
            log.info("【音色持久化】已加载 {} 条偏好: {}", cache.size(), configPath.toAbsolutePath());
        } catch (IOException e) {
            log.warn("【音色持久化】加载失败，使用空缓存: {}", e.getMessage());
            cache = new ConcurrentHashMap<>();
        }
    }

    /**
     * 将内存缓存写入 JSON 文件。
     */
    private void persist() {
        try {
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = objectMapper.writeValueAsString(cache);
            Files.writeString(configPath, json);
        } catch (IOException e) {
            log.error("【音色持久化】写入失败: {}", e.getMessage());
        }
    }
}
