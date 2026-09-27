package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.entity.MediaFile;
import com.example.server.entity.ProcessingTask;
import com.example.server.exception.BusinessException;
import com.example.server.utils.AnalysisTaskKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class AnalysisDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisDispatchService.class);
    private static final int USER_REQUESTS_PER_MINUTE = 5;
    private static final int GLOBAL_REQUESTS_PER_MINUTE = 30;
    private static final Duration ACTIVE_TTL = Duration.ofHours(6);

    private final AiService aiService;
    private final MediaService mediaService;
    private final StringRedisTemplate redisTemplate;
    private final AiRateLimitService rateLimitService;
    private final TaskEventService taskEventService;
    private final ProcessingTaskService processingTaskService;
    private final PendingTaskDispatcher pendingTaskDispatcher;

    public AnalysisDispatchService(AiService aiService,
                                   MediaService mediaService,
                                   StringRedisTemplate redisTemplate,
                                   AiRateLimitService rateLimitService,
                                   TaskEventService taskEventService,
                                   ProcessingTaskService processingTaskService,
                                   PendingTaskDispatcher pendingTaskDispatcher) {
        this.aiService = aiService;
        this.mediaService = mediaService;
        this.redisTemplate = redisTemplate;
        this.rateLimitService = rateLimitService;
        this.taskEventService = taskEventService;
        this.processingTaskService = processingTaskService;
        this.pendingTaskDispatcher = pendingTaskDispatcher;
    }

    /** 兼容旧调用方:未指定模式时按 GENERAL 提交。 */
    public SubmissionResult submit(MediaFile mediaFile, String goal, AgentFeedback revision) {
        return submit(mediaFile, goal, revision, AnalysisMode.GENERAL);
    }

    public SubmissionResult submit(MediaFile mediaFile, String goal, AgentFeedback revision, AnalysisMode mode) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        Long mediaId = mediaFile.getId();
        String action = revision == null
                ? AnalysisTaskMsg.START_ANALYSIS
                : AnalysisTaskMsg.REVISE_ANALYSIS;
        String contentHash = contentHash(mediaId);
        String goalDigest = AnalysisTaskKeys.goalDigest(goal, resolvedMode);
        String activeKey = AnalysisTaskKeys.active(contentHash, goalDigest);
        AnalysisTaskMsg message = new AnalysisTaskMsg(
                mediaId, action, contentHash, goal, resolvedMode.name());
        ProcessingTaskService.Reservation reservation =
                processingTaskService.reserveAnalysis(message);
        if (!reservation.accepted()) return SubmissionResult.DUPLICATE;

        try {
            if (!tryAcquireQuota(mediaFile.getUserId())) {
                processingTaskService.markCancelled(reservation.taskId(), "AI 请求超过限流配额");
                return SubmissionResult.RATE_LIMITED;
            }
            cacheActive(activeKey, mediaId);
            if (revision != null) aiService.stageRevision(revision, resolvedMode);
            boolean dispatched = pendingTaskDispatcher.dispatchNow(reservation.taskId());
            publishQueued(mediaId, goal, resolvedMode, dispatched);
        } catch (RuntimeException e) {
            processingTaskService.markFailed(reservation.taskId(), e);
            deleteActive(activeKey);
            if (revision != null) aiService.cancelStagedRevision(mediaId, goal, resolvedMode);
            log.error("analysis_dispatch_failed mediaId={} userId={}", mediaId, mediaFile.getUserId(), e);
            return SubmissionResult.FAILED;
        }
        return SubmissionResult.ACCEPTED;
    }

    public boolean isActive(Long mediaId, String goal) {
        return isActive(mediaId, goal, AnalysisMode.GENERAL);
    }

    public boolean isActive(Long mediaId, String goal, AnalysisMode mode) {
        String goalDigest = AnalysisTaskKeys.goalDigest(goal, mode);
        String contentHash = contentHash(mediaId);
        if (processingTaskService.hasAnalysisTask(contentHash, goal, mode)) {
            return processingTaskService.isAnalysisActive(contentHash, goal, mode);
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(AnalysisTaskKeys.active(contentHash, goalDigest)))
                || Boolean.TRUE.equals(redisTemplate.hasKey(
                        AnalysisTaskKeys.active("media-" + mediaId, goalDigest)));
    }

    public ProcessingTask currentTask(Long mediaId, String goal, AnalysisMode mode) {
        return processingTaskService.latestAnalysis(contentHash(mediaId), goal, mode);
    }

    /**
     * 追问和证据检索同样会触发模型调用，统一复用分析配额，避免绕过成本护栏。
     */
    public void requireAiQuota(Long userId) {
        try {
            if (!tryAcquireQuota(userId)) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "AI 请求过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("ai_rate_limiter_unavailable userId={}", userId, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "AI 服务限流器暂不可用，请稍后再试");
        }
    }

    private boolean tryAcquireQuota(Long userId) {
        return rateLimitService.tryAcquire(
                "ai", userId, USER_REQUESTS_PER_MINUTE, GLOBAL_REQUESTS_PER_MINUTE);
    }

    private String contentHash(Long mediaId) {
        return AnalysisTaskKeys.normalizeContentHash(
                mediaId, mediaService.contentHash(mediaId));
    }

    private void cacheActive(String activeKey, Long mediaId) {
        try {
            redisTemplate.opsForValue().set(activeKey, String.valueOf(mediaId), ACTIVE_TTL);
        } catch (RuntimeException e) {
            log.warn("analysis_active_cache_write_failed mediaId={}", mediaId, e);
        }
    }

    private void deleteActive(String activeKey) {
        try {
            redisTemplate.delete(activeKey);
        } catch (RuntimeException e) {
            log.warn("analysis_active_cache_delete_failed key={}", activeKey, e);
        }
    }

    private void publishQueued(Long mediaId, String goal, AnalysisMode mode, boolean dispatched) {
        try {
            String message = dispatched
                    ? "任务已进入异步分析队列"
                    : "任务已保存，正在等待消息队列恢复";
            taskEventService.publishAnalysis(mediaId, goal, mode,
                    TaskStatus.of(TaskStatus.State.QUEUED, message), TaskStage.QUEUED);
        } catch (RuntimeException e) {
            log.warn("analysis_queued_event_failed mediaId={}", mediaId, e);
        }
    }

    public enum SubmissionResult {
        ACCEPTED,
        RATE_LIMITED,
        DUPLICATE,
        FAILED
    }
}
