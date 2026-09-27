package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("processing_tasks")
public class ProcessingTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskKey;
    private String taskType;
    private Long mediaId;
    private String action;
    private String contentHash;
    private String userGoal;
    private String mode;
    private String status;
    private Integer attemptCount;
    private String lastError;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
