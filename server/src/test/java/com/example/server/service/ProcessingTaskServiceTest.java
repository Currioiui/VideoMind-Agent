package com.example.server.service;

import com.example.server.entity.ProcessingTask;
import com.example.server.mapper.ProcessingTaskMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessingTaskServiceTest {

    @Test
    void ignoresRedeliveryAfterTaskReachedTerminalState() {
        ProcessingTaskMapper mapper = mock(ProcessingTaskMapper.class);
        ProcessingTask task = task(ProcessingTaskService.COMPLETED, 2);
        when(mapper.selectById(8L)).thenReturn(task);

        int attempt = new ProcessingTaskService(mapper).markProcessing(8L);

        assertEquals(-1, attempt);
        verify(mapper, never()).update(isNull(), any());
    }

    @Test
    void incrementsAttemptForAnActiveTask() {
        ProcessingTaskMapper mapper = mock(ProcessingTaskMapper.class);
        ProcessingTask task = task(ProcessingTaskService.QUEUED, 1);
        when(mapper.selectById(8L)).thenReturn(task);
        when(mapper.update(isNull(), any())).thenReturn(1);

        int attempt = new ProcessingTaskService(mapper).markProcessing(8L);

        assertEquals(2, attempt);
    }

    private ProcessingTask task(String status, int attempts) {
        ProcessingTask task = new ProcessingTask();
        task.setId(8L);
        task.setStatus(status);
        task.setAttemptCount(attempts);
        return task;
    }
}
