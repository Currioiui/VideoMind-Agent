package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.ProcessingTask;
import com.example.server.mapper.ProcessingTaskMapper;
import com.example.server.utils.AnalysisTaskKeys;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProcessingTaskService {

    public static final String ANALYSIS = "ANALYSIS";
    public static final String TRANSCRIPTION = "TRANSCRIPTION";
    public static final String PENDING = "PENDING";
    public static final String DISPATCHING = "DISPATCHING";
    public static final String QUEUED = "QUEUED";
    public static final String PROCESSING = "PROCESSING";
    public static final String RETRYING = "RETRYING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private static final List<String> ACTIVE_STATUSES =
            List.of(PENDING, DISPATCHING, QUEUED, PROCESSING, RETRYING);
    private static final int MAX_DISPATCH_ATTEMPTS = 10;

    private final ProcessingTaskMapper taskMapper;

    public ProcessingTaskService(ProcessingTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    public Reservation reserveAnalysis(AnalysisTaskMsg message) {
        AnalysisMode mode = AnalysisMode.fromNullable(message.getMode());
        String contentHash = AnalysisTaskKeys.normalizeContentHash(
                message.getMediaId(), message.getContentHash());
        String taskKey = analysisKey(message.getAction(), contentHash, message.getUserGoal(), mode);
        return reserve(taskKey, ANALYSIS, message.getMediaId(), message.getAction(),
                contentHash, message.getUserGoal(), mode.name());
    }

    public Reservation reserveTranscription(Long mediaId) {
        return reserve("transcription:" + mediaId, TRANSCRIPTION, mediaId,
                "TRANSCRIBE", null, null, null);
    }

    private Reservation reserve(String taskKey,
                                String taskType,
                                Long mediaId,
                                String action,
                                String contentHash,
                                String goal,
                                String mode) {
        ProcessingTask existing = findByKey(taskKey);
        if (existing == null) {
            ProcessingTask task = new ProcessingTask();
            task.setTaskKey(taskKey);
            task.setTaskType(taskType);
            task.setMediaId(mediaId);
            task.setAction(action);
            task.setContentHash(contentHash);
            task.setUserGoal(goal);
            task.setMode(mode);
            task.setStatus(PENDING);
            task.setAttemptCount(0);
            task.setCreatedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            try {
                taskMapper.insert(task);
                return new Reservation(task.getId(), true);
            } catch (DuplicateKeyException ignored) {
                existing = findByKey(taskKey);
            }
        }
        if (existing == null || ACTIVE_STATUSES.contains(existing.getStatus())) {
            return new Reservation(existing == null ? null : existing.getId(), false);
        }

        int updated = taskMapper.update(null, new UpdateWrapper<ProcessingTask>()
                .eq("id", existing.getId())
                .notIn("status", ACTIVE_STATUSES)
                .set("status", PENDING)
                .set("attempt_count", 0)
                .set("last_error", null)
                .set("next_attempt_at", null)
                .set("updated_at", LocalDateTime.now()));
        return new Reservation(existing.getId(), updated == 1);
    }

    public ProcessingTask findById(Long taskId) {
        return taskId == null ? null : taskMapper.selectById(taskId);
    }

    public ProcessingTask latestTranscription(Long mediaId) {
        return taskMapper.selectOne(new QueryWrapper<ProcessingTask>()
                .eq("task_type", TRANSCRIPTION)
                .eq("media_id", mediaId)
                .orderByDesc("id")
                .last("LIMIT 1"));
    }

    public boolean hasAnalysisTask(String contentHash, String goal, AnalysisMode mode) {
        return analysisCount(contentHash, goal, mode, null) > 0;
    }

    public boolean isAnalysisActive(String contentHash, String goal, AnalysisMode mode) {
        return analysisCount(contentHash, goal, mode, ACTIVE_STATUSES) > 0;
    }

    public ProcessingTask latestAnalysis(String contentHash, String goal, AnalysisMode mode) {
        return taskMapper.selectOne(new QueryWrapper<ProcessingTask>()
                .eq("task_type", ANALYSIS)
                .in("task_key", List.of(
                        analysisKey(AnalysisTaskMsg.START_ANALYSIS, contentHash, goal, mode),
                        analysisKey(AnalysisTaskMsg.REVISE_ANALYSIS, contentHash, goal, mode)))
                .orderByDesc("id")
                .last("LIMIT 1"));
    }

    private Long analysisCount(String contentHash,
                               String goal,
                               AnalysisMode mode,
                               List<String> statuses) {
        QueryWrapper<ProcessingTask> query = new QueryWrapper<ProcessingTask>()
                .eq("task_type", ANALYSIS)
                .in("task_key", List.of(
                        analysisKey(AnalysisTaskMsg.START_ANALYSIS, contentHash, goal, mode),
                        analysisKey(AnalysisTaskMsg.REVISE_ANALYSIS, contentHash, goal, mode)));
        if (statuses != null) query.in("status", statuses);
        return taskMapper.selectCount(query);
    }

    public boolean claimDispatch(Long taskId) {
        return taskId != null && taskMapper.update(null, new UpdateWrapper<ProcessingTask>()
                .eq("id", taskId)
                .eq("status", PENDING)
                .set("status", DISPATCHING)
                .set("updated_at", LocalDateTime.now())) == 1;
    }

    public void markQueued(Long taskId) {
        updateStatus(taskId, QUEUED, null, true);
    }

    public int markProcessing(Long taskId) {
        if (taskId == null) return -1;
        ProcessingTask task = taskMapper.selectById(taskId);
        if (task == null || !ACTIVE_STATUSES.contains(task.getStatus())) return -1;
        int attempts = task.getAttemptCount() == null ? 1 : task.getAttemptCount() + 1;
        int updated = taskMapper.update(null, new UpdateWrapper<ProcessingTask>()
                .eq("id", taskId)
                .in("status", ACTIVE_STATUSES)
                .set("status", PROCESSING)
                .set("attempt_count", attempts)
                .set("last_error", null)
                .set("updated_at", LocalDateTime.now()));
        return updated == 1 ? attempts : -1;
    }

    public void markRetrying(Long taskId, Throwable error) {
        updateStatus(taskId, RETRYING, message(error), false);
    }

    public void markCompleted(Long taskId) {
        updateStatus(taskId, COMPLETED, null, false);
    }

    public void markFailed(Long taskId, Throwable error) {
        updateStatus(taskId, FAILED, message(error), false);
    }

    public void markCancelled(Long taskId, String reason) {
        updateStatus(taskId, CANCELLED, truncate(reason), false);
    }

    public void markDispatchFailed(Long taskId, Throwable error) {
        if (taskId == null) return;
        ProcessingTask task = taskMapper.selectById(taskId);
        if (task == null) return;
        int attempts = task.getAttemptCount() == null ? 1 : task.getAttemptCount() + 1;
        boolean terminal = attempts >= MAX_DISPATCH_ATTEMPTS;
        long delaySeconds = Math.min(60, 1L << Math.min(6, attempts));
        taskMapper.update(null, new UpdateWrapper<ProcessingTask>()
                .eq("id", taskId)
                .set("status", terminal ? FAILED : PENDING)
                .set("attempt_count", attempts)
                .set("last_error", message(error))
                .set("next_attempt_at", terminal ? null : LocalDateTime.now().plusSeconds(delaySeconds))
                .set("updated_at", LocalDateTime.now()));
    }

    public List<ProcessingTask> pending(int limit) {
        return taskMapper.selectList(new QueryWrapper<ProcessingTask>()
                .eq("status", PENDING)
                .and(query -> query.isNull("next_attempt_at")
                        .or().le("next_attempt_at", LocalDateTime.now()))
                .orderByAsc("id")
                .last("LIMIT " + Math.max(1, Math.min(100, limit))));
    }

    public void recoverStaleDispatches(Duration age) {
        taskMapper.update(null, new UpdateWrapper<ProcessingTask>()
                .eq("status", DISPATCHING)
                .lt("updated_at", LocalDateTime.now().minus(age))
                .set("status", PENDING)
                .set("next_attempt_at", LocalDateTime.now())
                .set("updated_at", LocalDateTime.now()));
    }

    public void deleteForMedia(Long mediaId) {
        taskMapper.delete(new QueryWrapper<ProcessingTask>().eq("media_id", mediaId));
    }

    private void updateStatus(Long taskId, String status, String error, boolean resetAttempts) {
        if (taskId == null) return;
        UpdateWrapper<ProcessingTask> update = new UpdateWrapper<ProcessingTask>()
                .eq("id", taskId)
                .set("status", status)
                .set("last_error", error)
                .set("next_attempt_at", null)
                .set("updated_at", LocalDateTime.now());
        if (resetAttempts) update.set("attempt_count", 0);
        taskMapper.update(null, update);
    }

    private ProcessingTask findByKey(String taskKey) {
        return taskMapper.selectOne(new QueryWrapper<ProcessingTask>().eq("task_key", taskKey));
    }

    private String analysisKey(String action, String contentHash, String goal, AnalysisMode mode) {
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("analysis content hash is required");
        }
        return "analysis:" + action + ":" + contentHash + ":"
                + AnalysisTaskKeys.goalDigest(goal, mode);
    }

    private String message(Throwable error) {
        return truncate(error == null ? null : error.getMessage());
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1_000) return value;
        return value.substring(0, 1_000);
    }

    public record Reservation(Long taskId, boolean accepted) {
    }
}
