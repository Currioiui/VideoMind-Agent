package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.MediaFile;
import com.example.server.dto.VideoContext;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final MediaFileMapper mediaFileMapper;
    private final StringRedisTemplate redisTemplate;
    private final MinioUtils minioUtils;
    private final ObjectMapper objectMapper;
    private final AgentCheckpointService checkpointService;
    private final AgentTelemetry telemetry;
    private final QdrantVectorStore vectorStore;
    private final VideoContextService videoContextService;
    private final ProcessingTaskService processingTaskService;

    private static final String MEDIA_MD5_KEY_PREFIX = "media:md5:";
    private static final Set<String> VIDEO_SUFFIXES = Set.of(
            ".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v");

    public MediaService(MediaFileMapper mediaFileMapper,
                        StringRedisTemplate redisTemplate,
                        MinioUtils minioUtils,
                        ObjectMapper objectMapper,
                        AgentCheckpointService checkpointService,
                        AgentTelemetry telemetry,
                        QdrantVectorStore vectorStore,
                        VideoContextService videoContextService,
                        ProcessingTaskService processingTaskService) {
        this.mediaFileMapper = mediaFileMapper;
        this.redisTemplate = redisTemplate;
        this.minioUtils = minioUtils;
        this.objectMapper = objectMapper;
        this.checkpointService = checkpointService;
        this.telemetry = telemetry;
        this.vectorStore = vectorStore;
        this.videoContextService = videoContextService;
        this.processingTaskService = processingTaskService;
    }

    public String calculateMd5(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            return calculateMd5(inputStream);
        }
    }

    public String calculateMd5(File file) throws IOException {
        try (InputStream inputStream = Files.newInputStream(file.toPath())) {
            return calculateMd5(inputStream);
        }
    }

    public void rememberContentHash(Long mediaId, String md5) {
        if (mediaId == null || md5 == null || md5.isBlank()) return;
        try {
            redisTemplate.opsForValue().set(MEDIA_MD5_KEY_PREFIX + mediaId, md5);
        } catch (RuntimeException e) {
            log.warn("media_hash_cache_write_failed mediaId={}", mediaId, e);
        }
    }

    public MediaFile saveUploadedMedia(String filename, String fileUrl, Long userId, String md5) {
        return saveUploadedMedia(filename, fileUrl, userId, md5, null);
    }

    public MediaFile saveUploadedMedia(String filename,
                                       String fileUrl,
                                       Long userId,
                                       String md5,
                                       String uploadId) {
        MediaFile existing = findByUploadId(uploadId);
        if (existing != null) {
            removeUploadedObjectQuietly(fileUrl);
            return existing;
        }
        MediaFile mediaFile = new MediaFile();
        mediaFile.setFilename(normalizeVideoFilename(filename));
        mediaFile.setFilePath(fileUrl);
        mediaFile.setStatus("COMPLETED");
        mediaFile.setUploadTime(LocalDateTime.now());
        mediaFile.setUserId(userId);
        mediaFile.setContentHash(md5);
        mediaFile.setUploadId(uploadId);
        try {
            mediaFileMapper.insert(mediaFile);
            rememberContentHash(mediaFile.getId(), md5);
            invalidateUserList(userId);
            return mediaFile;
        } catch (DuplicateKeyException e) {
            removeUploadedObject(fileUrl, e);
            MediaFile completed = findByUploadId(uploadId);
            if (completed != null) return completed;
            throw e;
        } catch (RuntimeException e) {
            removeUploadedObject(fileUrl, e);
            throw e;
        }
    }

    public MediaFile findByUploadId(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) return null;
        return mediaFileMapper.selectOne(
                new QueryWrapper<MediaFile>().eq("upload_id", uploadId));
    }

    public List<MediaFile> listByUser(Long userId) {
        String cacheKey = userListKey(userId);
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<MediaFile>>() { });
            }
        } catch (Exception e) {
            log.warn("media_list_cache_read_failed userId={}", userId, e);
        }

        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        List<MediaFile> mediaFiles = mediaFileMapper.selectList(
                query.select("id", "filename", "status", "cover_url", "upload_time")
                        .eq("user_id", userId)
                        .ne("status", "DELETING")
                        .orderByDesc("id"));
        try {
            redisTemplate.opsForValue().set(
                    cacheKey, objectMapper.writeValueAsString(mediaFiles), 30, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("media_list_cache_write_failed userId={}", userId, e);
        }
        return mediaFiles;
    }

    public String contentHash(Long mediaId) {
        try {
            String cached = redisTemplate.opsForValue().get(MEDIA_MD5_KEY_PREFIX + mediaId);
            if (cached != null && !cached.isBlank()) return cached;
        } catch (RuntimeException e) {
            log.warn("media_hash_cache_read_failed mediaId={}", mediaId, e);
        }

        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        String persisted = mediaFile == null ? null : mediaFile.getContentHash();
        rememberContentHash(mediaId, persisted);
        return persisted;
    }

    public void deleteOwnedMedia(Long mediaId, Long userId) {
        MediaFile mediaFile = requireOwnedMedia(mediaId, userId);
        mediaFile.setStatus("DELETING");
        mediaFileMapper.updateById(mediaFile);
        completeDeletion(mediaFile);
        invalidateUserList(userId);
    }

    @Scheduled(fixedDelayString = "${media.cleanup.retry-delay-ms:60000}")
    public void retryPendingDeletions() {
        mediaFileMapper.selectList(new QueryWrapper<MediaFile>()
                        .eq("status", "DELETING")
                        .orderByAsc("id")
                        .last("LIMIT 20"))
                .forEach(this::completeDeletion);
    }

    private void completeDeletion(MediaFile mediaFile) {
        Long mediaId = mediaFile.getId();
        boolean objectDeleted = true;
        if (mediaFile.getFilePath() != null && mediaFile.getFilePath().startsWith("http")) {
            try {
                minioUtils.removeFile(mediaFile.getFilePath());
            } catch (RuntimeException e) {
                objectDeleted = false;
                log.warn("media_object_cleanup_failed mediaId={} path={}",
                        mediaId, mediaFile.getFilePath(), e);
            }
        }
        boolean runtimeDeleted = purgeRuntimeArtifacts(mediaId);
        if (objectDeleted && runtimeDeleted) {
            mediaFileMapper.deleteById(mediaId);
            invalidateUserList(mediaFile.getUserId());
        } else {
            log.warn("media_cleanup_deferred mediaId={} objectDeleted={} runtimeDeleted={}",
                    mediaId, objectDeleted, runtimeDeleted);
        }
    }

    public boolean exists(Long mediaId) {
        if (mediaId == null) return false;
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        return mediaFile != null && !"DELETING".equals(mediaFile.getStatus());
    }

    public boolean purgeRuntimeArtifacts(Long mediaId) {
        boolean success = true;
        VideoContext context = null;
        boolean evidenceReady = true;
        try {
            context = checkpointService.loadContext(mediaId);
        } catch (RuntimeException e) {
            success = false;
            evidenceReady = false;
            log.warn("media_evidence_manifest_read_failed mediaId={}", mediaId, e);
        }
        if (evidenceReady) {
            try {
                videoContextService.deleteEvidenceFramesStrict(context);
            } catch (RuntimeException e) {
                success = false;
                evidenceReady = false;
                log.warn("media_evidence_cleanup_failed mediaId={}", mediaId, e);
            }
        }
        if (!cleanup(mediaId, "redis", () ->
            redisTemplate.delete(List.of(
                    MEDIA_MD5_KEY_PREFIX + mediaId,
                    "transcription:active:" + mediaId,
                    "transcription:state:" + mediaId)))) success = false;
        if (evidenceReady
                && !cleanup(mediaId, "checkpoint", () -> checkpointService.deleteMedia(mediaId))) {
            success = false;
        }
        if (!cleanup(mediaId, "telemetry", () -> telemetry.deleteTask(mediaId))) success = false;
        if (!cleanup(mediaId, "vector", () -> vectorStore.deleteMedia(mediaId))) success = false;
        if (!cleanup(mediaId, "processing-task", () -> processingTaskService.deleteForMedia(mediaId))) {
            success = false;
        }
        return success;
    }

    public void invalidateUserList(Long userId) {
        if (userId == null) return;
        try {
            redisTemplate.delete(userListKey(userId));
        } catch (RuntimeException e) {
            log.warn("media_list_cache_invalidation_failed userId={}", userId, e);
        }
    }

    public String readableSource(String source) {
        return minioUtils.readableSource(source);
    }

    public String normalizeVideoFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("视频文件名不能为空");
        }
        String normalized = filename.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (normalized.isBlank() || normalized.length() > 255) {
            throw new IllegalArgumentException("视频文件名无效或过长");
        }
        String suffix = fileSuffix(normalized).toLowerCase(java.util.Locale.ROOT);
        if (!VIDEO_SUFFIXES.contains(suffix)) {
            throw new IllegalArgumentException("仅支持 MP4、MOV、MKV、AVI、WEBM 和 M4V 视频");
        }
        return normalized;
    }

    public MediaFile requireOwnedMedia(Long mediaId, Long userId) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) throw new NoSuchElementException("文件不存在");
        if (!Objects.equals(mediaFile.getUserId(), userId)) {
            throw new SecurityException("无权访问该文件");
        }
        return mediaFile;
    }

    private String calculateMd5(InputStream inputStream) throws IOException {
        MessageDigest digest = md5Digest();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }

    private String userListKey(Long userId) {
        return "media:list:v2:user:" + userId;
    }

    private String fileSuffix(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private void removeUploadedObject(String fileUrl, RuntimeException originalError) {
        try {
            minioUtils.removeFile(fileUrl);
        } catch (RuntimeException cleanupError) {
            originalError.addSuppressed(cleanupError);
            log.warn("uploaded_object_rollback_failed path={}", fileUrl, cleanupError);
        }
    }

    private void removeUploadedObjectQuietly(String fileUrl) {
        try {
            minioUtils.removeFile(fileUrl);
        } catch (RuntimeException cleanupError) {
            log.warn("duplicate_upload_object_cleanup_failed path={}", fileUrl, cleanupError);
        }
    }

    private boolean cleanup(Long mediaId, String resource, Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException e) {
            log.warn("media_runtime_cleanup_failed mediaId={} resource={}", mediaId, resource, e);
            return false;
        }
    }
}
