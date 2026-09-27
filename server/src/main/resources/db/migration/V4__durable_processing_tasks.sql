CREATE TABLE IF NOT EXISTS processing_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_key VARCHAR(220) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    media_id BIGINT NOT NULL,
    action VARCHAR(32) NULL,
    content_hash VARCHAR(128) NULL,
    user_goal VARCHAR(500) NULL,
    mode VARCHAR(32) NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    next_attempt_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_processing_task_key (task_key),
    KEY idx_processing_dispatch (status, next_attempt_at),
    KEY idx_processing_media (media_id, task_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @upload_id_column_exists = (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'media_files' AND column_name = 'upload_id'
);
SET @upload_id_column_sql = IF(
    @upload_id_column_exists = 0,
    'ALTER TABLE media_files ADD COLUMN upload_id VARCHAR(36) NULL, ALGORITHM=INSTANT, LOCK=NONE',
    'SELECT 1'
);
PREPARE upload_id_column_statement FROM @upload_id_column_sql;
EXECUTE upload_id_column_statement;
DEALLOCATE PREPARE upload_id_column_statement;

SET @upload_id_index_exists = (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'media_files' AND index_name = 'uk_media_upload_id'
);
SET @upload_id_index_sql = IF(
    @upload_id_index_exists = 0,
    'ALTER TABLE media_files ADD UNIQUE INDEX uk_media_upload_id(upload_id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1'
);
PREPARE upload_id_index_statement FROM @upload_id_index_sql;
EXECUTE upload_id_index_statement;
DEALLOCATE PREPARE upload_id_index_statement;

SET @ai_summary_column_exists = (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'media_files' AND column_name = 'ai_summary'
);
SET @ai_summary_column_sql = IF(
    @ai_summary_column_exists = 1,
    'ALTER TABLE media_files DROP COLUMN ai_summary',
    'SELECT 1'
);
PREPARE ai_summary_column_statement FROM @ai_summary_column_sql;
EXECUTE ai_summary_column_statement;
DEALLOCATE PREPARE ai_summary_column_statement;
