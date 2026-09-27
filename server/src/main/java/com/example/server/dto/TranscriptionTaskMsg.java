package com.example.server.dto;

import java.io.Serializable;

public record TranscriptionTaskMsg(Long taskId, Long mediaId) implements Serializable {
}
