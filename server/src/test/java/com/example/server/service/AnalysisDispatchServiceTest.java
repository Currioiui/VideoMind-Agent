package com.example.server.service;

import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisDispatchServiceTest {

    private static final String CONTENT_HASH = "0123456789abcdef0123456789abcdef";

    @Test
    void initialAndRevisionTasksUseTheSamePersistedContentHash() {
        MediaService mediaService = mock(MediaService.class);
        AiRateLimitService rateLimitService = mock(AiRateLimitService.class);
        ProcessingTaskService taskService = mock(ProcessingTaskService.class);
        when(mediaService.contentHash(7L)).thenReturn(CONTENT_HASH);
        when(rateLimitService.tryAcquire(anyString(), anyLong(), anyInt(), anyInt()))
                .thenReturn(false);
        when(taskService.reserveAnalysis(any()))
                .thenReturn(new ProcessingTaskService.Reservation(1L, true))
                .thenReturn(new ProcessingTaskService.Reservation(2L, true));

        AnalysisDispatchService service = new AnalysisDispatchService(
                mock(AiService.class),
                mediaService,
                mock(StringRedisTemplate.class),
                rateLimitService,
                mock(TaskEventService.class),
                taskService,
                mock(PendingTaskDispatcher.class));
        MediaFile media = new MediaFile();
        media.setId(7L);
        media.setUserId(9L);
        AgentFeedback revision = new AgentFeedback(
                7L, "goal", AnalysisMode.GENERAL.name(), 2,
                "missing-evidence", "retry", "goal", List.of(),
                null, null, Instant.now());

        service.submit(media, "goal", null, AnalysisMode.GENERAL);
        service.submit(media, "goal", revision, AnalysisMode.GENERAL);

        ArgumentCaptor<AnalysisTaskMsg> messages = ArgumentCaptor.forClass(AnalysisTaskMsg.class);
        verify(taskService, org.mockito.Mockito.times(2)).reserveAnalysis(messages.capture());
        assertEquals(AnalysisTaskMsg.START_ANALYSIS, messages.getAllValues().get(0).getAction());
        assertEquals(AnalysisTaskMsg.REVISE_ANALYSIS, messages.getAllValues().get(1).getAction());
        assertEquals(CONTENT_HASH, messages.getAllValues().get(0).getContentHash());
        assertEquals(CONTENT_HASH, messages.getAllValues().get(1).getContentHash());
    }
}
