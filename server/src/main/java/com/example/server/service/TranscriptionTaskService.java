package com.example.server.service;

import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import com.example.server.entity.MediaFile;
import com.example.server.entity.ProcessingTask;
import com.example.server.mapper.MediaFileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class TranscriptionTaskService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionTaskService.class);
    private static final Duration ACTIVE_TTL = Duration.ofHours(2);

    private final MediaFileMapper mediaFileMapper;
    private final VideoTranscriptionService videoTranscriptionService;
    private final MediaService mediaService;
    private final StringRedisTemplate redisTemplate;
    private final TaskEventService taskEventService;
    private final ProcessingTaskService processingTaskService;
    private final PendingTaskDispatcher pendingTaskDispatcher;

    public TranscriptionTaskService(MediaFileMapper mediaFileMapper,
                                    VideoTranscriptionService videoTranscriptionService,
                                    MediaService mediaService,
                                    StringRedisTemplate redisTemplate,
                                    TaskEventService taskEventService,
                                    ProcessingTaskService processingTaskService,
                                    PendingTaskDispatcher pendingTaskDispatcher) {
        this.mediaFileMapper = mediaFileMapper;
        this.videoTranscriptionService = videoTranscriptionService;
        this.mediaService = mediaService;
        this.redisTemplate = redisTemplate;
        this.taskEventService = taskEventService;
        this.processingTaskService = processingTaskService;
        this.pendingTaskDispatcher = pendingTaskDispatcher;
    }

    public boolean queue(Long mediaId) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null || hasTranscript(mediaFile)) return false;

        ProcessingTaskService.Reservation reservation =
                processingTaskService.reserveTranscription(mediaId);
        if (!reservation.accepted()) return false;

        cacheActive(mediaId);
        setState(mediaId, TaskStatus.State.QUEUED, ACTIVE_TTL);
        boolean dispatched = pendingTaskDispatcher.dispatchNow(reservation.taskId());
        String message = dispatched ? "文字提取任务已排队" : "任务已保存，正在等待消息队列恢复";
        taskEventService.publishTranscription(mediaId,
                TaskStatus.of(TaskStatus.State.QUEUED, message), TaskStage.QUEUED);
        return true;
    }

    public void process(Long mediaId) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) throw new IllegalArgumentException("媒体不存在: " + mediaId);
        if (hasTranscript(mediaFile)) {
            completed(mediaFile);
            return;
        }

        setState(mediaId, TaskStatus.State.PROCESSING, ACTIVE_TTL);
        taskEventService.publishTranscription(mediaId,
                TaskStatus.of(TaskStatus.State.PROCESSING, "正在识别视频语音"), TaskStage.ASR);
        mediaFile.setTranscriptText(videoTranscriptionService.transcribe(
                mediaService.readableSource(mediaFile.getFilePath())));
        mediaFileMapper.updateById(mediaFile);
        mediaService.invalidateUserList(mediaFile.getUserId());
        completed(mediaFile);
    }

    public void retrying(Long mediaId) {
        setState(mediaId, TaskStatus.State.PROCESSING, ACTIVE_TTL);
        taskEventService.publishTranscription(mediaId,
                TaskStatus.of(TaskStatus.State.PROCESSING, "文字提取失败，正在自动重试"),
                TaskStage.RETRYING);
    }

    public void failed(Long mediaId) {
        setState(mediaId, TaskStatus.State.FAILED, Duration.ofHours(1));
        clearActive(mediaId);
        taskEventService.publishTranscription(mediaId,
                TaskStatus.of(TaskStatus.State.FAILED, "文字提取失败，请稍后重试"),
                TaskStage.FAILED);
    }

    public TaskStatus status(MediaFile mediaFile) {
        if (hasTranscript(mediaFile)) return TaskStatus.completed(mediaFile.getTranscriptText());

        ProcessingTask task = processingTaskService.latestTranscription(mediaFile.getId());
        if (task != null) return persistedStatus(task);

        String stateValue = redisTemplate.opsForValue().get(stateKey(mediaFile.getId()));
        if (stateValue == null) return TaskStatus.of(TaskStatus.State.NOT_STARTED, "尚未提交文字提取任务");
        try {
            return statusFor(TaskStatus.State.valueOf(stateValue));
        } catch (IllegalArgumentException e) {
            log.warn("invalid_transcription_state mediaId={} state={}", mediaFile.getId(), stateValue);
            return TaskStatus.of(TaskStatus.State.NOT_STARTED, "任务状态不可用");
        }
    }

    private TaskStatus persistedStatus(ProcessingTask task) {
        return switch (task.getStatus()) {
            case ProcessingTaskService.PENDING,
                 ProcessingTaskService.DISPATCHING,
                 ProcessingTaskService.QUEUED ->
                    TaskStatus.of(TaskStatus.State.QUEUED, "文字提取任务已排队");
            case ProcessingTaskService.PROCESSING,
                 ProcessingTaskService.RETRYING ->
                    TaskStatus.of(TaskStatus.State.PROCESSING, "正在提取文字");
            case ProcessingTaskService.FAILED,
                 ProcessingTaskService.CANCELLED ->
                    TaskStatus.of(TaskStatus.State.FAILED, "文字提取失败，请稍后重试");
            case ProcessingTaskService.COMPLETED ->
                    TaskStatus.of(TaskStatus.State.FAILED, "文字结果暂不可用，请重新提交");
            default -> TaskStatus.of(TaskStatus.State.NOT_STARTED, "尚未提交文字提取任务");
        };
    }

    private TaskStatus statusFor(TaskStatus.State state) {
        return switch (state) {
            case COMPLETED -> TaskStatus.of(state, "文字提取已完成");
            case FAILED -> TaskStatus.of(state, "文字提取失败，请稍后重试");
            case QUEUED -> TaskStatus.of(state, "文字提取任务已排队");
            case PROCESSING -> TaskStatus.of(state, "正在提取文字");
            case NOT_STARTED -> TaskStatus.of(state, "尚未提交文字提取任务");
        };
    }

    private void completed(MediaFile mediaFile) {
        setState(mediaFile.getId(), TaskStatus.State.COMPLETED, Duration.ofDays(7));
        clearActive(mediaFile.getId());
        taskEventService.publishTranscription(mediaFile.getId(),
                TaskStatus.completed(mediaFile.getTranscriptText()), TaskStage.COMPLETED);
        log.info("transcription_completed mediaId={}", mediaFile.getId());
    }

    private boolean hasTranscript(MediaFile mediaFile) {
        return mediaFile.getTranscriptText() != null && !mediaFile.getTranscriptText().isBlank();
    }

    private void cacheActive(Long mediaId) {
        try {
            redisTemplate.opsForValue().set(activeKey(mediaId), "1", ACTIVE_TTL);
        } catch (RuntimeException e) {
            log.warn("transcription_active_cache_failed mediaId={}", mediaId, e);
        }
    }

    private void setState(Long mediaId, TaskStatus.State state, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(stateKey(mediaId), state.name(), ttl);
        } catch (RuntimeException e) {
            log.warn("transcription_state_write_failed mediaId={} state={}", mediaId, state, e);
        }
    }

    private void clearActive(Long mediaId) {
        try {
            redisTemplate.delete(activeKey(mediaId));
        } catch (RuntimeException e) {
            log.warn("transcription_active_cleanup_failed mediaId={}", mediaId, e);
        }
    }

    private String activeKey(Long mediaId) {
        return "transcription:active:" + mediaId;
    }

    private String stateKey(Long mediaId) {
        return "transcription:state:" + mediaId;
    }
}
