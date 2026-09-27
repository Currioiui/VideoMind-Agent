package com.example.server.consumer;

import com.example.server.dto.TranscriptionTaskMsg;
import com.example.server.service.MediaService;
import com.example.server.service.ProcessingTaskService;
import com.example.server.service.TranscriptionTaskService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "${rocketmq.topic.video-transcription:video-transcription-topic}",
        consumerGroup = "${rocketmq.consumer.transcription-group:video-transcription-consumer}",
        maxReconsumeTimes = 2)
public class VideoTranscriptionConsumer implements RocketMQListener<TranscriptionTaskMsg> {

    private static final Logger log = LoggerFactory.getLogger(VideoTranscriptionConsumer.class);
    private static final int MAX_ATTEMPTS = 3;

    private final TranscriptionTaskService transcriptionTaskService;
    private final ProcessingTaskService processingTaskService;
    private final MediaService mediaService;
    private final RedissonClient redissonClient;

    public VideoTranscriptionConsumer(TranscriptionTaskService transcriptionTaskService,
                                      ProcessingTaskService processingTaskService,
                                      MediaService mediaService,
                                      RedissonClient redissonClient) {
        this.transcriptionTaskService = transcriptionTaskService;
        this.processingTaskService = processingTaskService;
        this.mediaService = mediaService;
        this.redissonClient = redissonClient;
    }

    @Override
    public void onMessage(TranscriptionTaskMsg message) {
        if (message == null || message.mediaId() == null) {
            if (message != null) {
                processingTaskService.markFailed(
                        message.taskId(), new IllegalArgumentException("transcription mediaId is required"));
            }
            return;
        }

        RLock lock = redissonClient.getLock("lock:transcription:" + message.mediaId());
        if (!lock.tryLock()) return;
        try {
            if (!mediaService.exists(message.mediaId())) {
                processingTaskService.markCancelled(message.taskId(), "媒体已删除");
                return;
            }
            int attempt = processingTaskService.markProcessing(message.taskId());
            if (attempt < 0) {
                log.info("transcription_duplicate_ignored taskId={} mediaId={}",
                        message.taskId(), message.mediaId());
                return;
            }
            try {
                transcriptionTaskService.process(message.mediaId());
                processingTaskService.markCompleted(message.taskId());
            } catch (RuntimeException e) {
                if (attempt > 0 && attempt < MAX_ATTEMPTS) {
                    processingTaskService.markRetrying(message.taskId(), e);
                    transcriptionTaskService.retrying(message.mediaId());
                    throw new IllegalStateException("文字提取失败，交由 RocketMQ 重试", e);
                }
                processingTaskService.markFailed(message.taskId(), e);
                transcriptionTaskService.failed(message.mediaId());
                log.error("transcription_dead_lettered mediaId={} attempts={}",
                        message.mediaId(), attempt, e);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
