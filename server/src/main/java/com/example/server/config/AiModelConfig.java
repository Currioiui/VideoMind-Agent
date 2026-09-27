package com.example.server.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * Central model configuration. Business services depend on the generic ChatModel
 * contract so the provider can be replaced without rewriting the Agent workflow.
 */
@Configuration
public class AiModelConfig {

    @Bean
    @Primary
    public ChatModel chatModel(@Value("${ai.deepseek.api-key}") String apiKey,
                               @Value("${ai.deepseek.base-url}") String baseUrl,
                               @Value("${ai.deepseek.model:deepseek-ai/DeepSeek-V3.2}") String modelName,
                               @Value("${ai.deepseek.timeout-seconds:300}") long timeoutSeconds) {
        return build(apiKey, baseUrl, modelName, timeoutSeconds);
    }

    /** ReAct uses a shorter transport timeout so cancelled Harness work cannot occupy the pool for minutes. */
    @Bean("reactChatModel")
    public ChatModel reactChatModel(
            @Value("${ai.deepseek.api-key}") String apiKey,
            @Value("${ai.deepseek.base-url}") String baseUrl,
            @Value("${ai.deepseek.model:deepseek-ai/DeepSeek-V3.2}") String modelName,
            @Value("${agent.react.model-timeout-seconds:75}") long timeoutSeconds,
            @Value("${agent.tools.timeout-seconds:90}") long harnessTimeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > harnessTimeoutSeconds) {
            throw new IllegalArgumentException(
                    "ReAct model timeout must be positive and no greater than the Harness timeout");
        }
        return build(apiKey, baseUrl, modelName, timeoutSeconds);
    }

    private ChatModel build(String apiKey, String baseUrl, String modelName, long timeoutSeconds) {
        if (timeoutSeconds < 1) throw new IllegalArgumentException("Model timeout must be positive");
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                // DeepSeekUtils owns retry policy to avoid nested retries.
                .maxRetries(0)
                .build();
    }
}
