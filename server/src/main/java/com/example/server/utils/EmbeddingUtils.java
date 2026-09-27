package com.example.server.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class EmbeddingUtils {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final ObjectMapper objectMapper;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    public EmbeddingUtils(@Value("${ai.deepseek.api-key}") String apiKey,
                          @Value("${ai.deepseek.base-url}") String baseUrl,
                          @Value("${ai.embedding.model:BAAI/bge-m3}") String model,
                          ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.objectMapper = objectMapper;
    }

    public List<Double> embed(String text) {
        if (text == null || text.isBlank()) return List.of();
        try {
            ObjectNode requestJson = objectMapper.createObjectNode();
            requestJson.put("model", model);
            requestJson.put("input", text);

            Request request = new Request.Builder()
                    .url(baseUrl + "/embeddings")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(
                            objectMapper.writeValueAsString(requestJson),
                            MediaType.parse("application/json; charset=utf-8")
                    ))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IllegalStateException("Embedding API failed: " + response.code());
                }
                JsonNode data = objectMapper.readTree(response.body().string()).path("data");
                if (!data.isArray() || data.isEmpty()) throw new IllegalStateException("Embedding data is empty");
                JsonNode values = data.get(0).path("embedding");
                if (!values.isArray() || values.isEmpty()) throw new IllegalStateException("Embedding vector is empty");
                List<Double> embedding = new ArrayList<>(values.size());
                for (JsonNode value : values) {
                    embedding.add(value.asDouble());
                }
                return embedding;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Embedding 生成失败", e);
        }
    }
}
