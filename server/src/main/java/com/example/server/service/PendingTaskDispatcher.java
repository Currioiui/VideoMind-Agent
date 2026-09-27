package com.example.server.service;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.TranscriptionTaskMsg;
import com.example.server.entity.ProcessingTask;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class PendingTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PendingTaskDispatcher.class);

    private final ProcessingTaskService taskService;
    private final RocketMQTemplate rocketMQTemplate;
    private final String analysisTopic;
    private final String transcriptionTopic;

    public PendingTaskDispatcher(ProcessingTaskService taskService,
                                 RocketMQTemplate rocketMQTemplate,
                                 @Value("${rocketmq.topic.video-analysis:video-analysis-topic}")
                                 String analysisTopic,
                                 @Value("${rocketmq.topic.video-transcription:video-transcription-topic}")
                                 String transcriptionTopic) {
        this.taskService = taskService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.analysisTopic = analysisTopic;
        this.transcriptionTopic = transcriptionTopic;
    }

    public boolean dispatchNow(Long taskId) {
        ProcessingTask task = taskService.findById(taskId);
        if (task == null || !taskService.claimDispatch(taskId)) return false;
        try {
            if (ProcessingTaskService.ANALYSIS.equals(task.getTaskType())) {
                rocketMQTemplate.convertAndSend(analysisTopic, new AnalysisTaskMsg(
                        task.getId(), task.getMediaId(), task.getAction(), task.getContentHash(),
                        task.getUserGoal(), task.getMode()));
            } else if (ProcessingTaskService.TRANSCRIPTION.equals(task.getTaskType())) {
                rocketMQTemplate.convertAndSend(transcriptionTopic,
                        new TranscriptionTaskMsg(task.getId(), task.getMediaId()));
            } else {
                throw new IllegalArgumentException("unsupported task type: " + task.getTaskType());
            }
            taskService.markQueued(taskId);
            return true;
        } catch (RuntimeException e) {
            taskService.markDispatchFailed(taskId, e);
            log.warn("durable_task_dispatch_failed taskId={} type={}", taskId, task.getTaskType(), e);
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${task.dispatch.retry-delay-ms:5000}")
    public void dispatchPending() {
        taskService.recoverStaleDispatches(Duration.ofMinutes(1));
        for (ProcessingTask task : taskService.pending(20)) {
            dispatchNow(task.getId());
        }
    }
}
