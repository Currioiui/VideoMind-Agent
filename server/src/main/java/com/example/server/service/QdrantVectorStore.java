package com.example.server.service;

import com.example.server.dto.VideoChunk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class QdrantVectorStore {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String collection;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean collectionReady = new AtomicBoolean();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public QdrantVectorStore(@Value("${vector.qdrant.enabled:true}") boolean enabled,
                             @Value("${vector.qdrant.url:http://localhost:6333}") String baseUrl,
                             @Value("${vector.qdrant.api-key:}") String apiKey,
                             @Value("${vector.qdrant.collection:video_chunks_bge_m3_v1}") String collection,
                             ObjectMapper objectMapper) {
        if (!collection.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Qdrant collection name is invalid");
        }
        this.enabled = enabled;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.collection = collection;
        this.objectMapper = objectMapper;
    }

    public void upsert(Long mediaId, List<VideoChunk> chunks) {
        if (!enabled) return;
        List<VideoChunk> vectorized = chunks.stream().filter(chunk -> !chunk.embedding().isEmpty()).toList();
        if (vectorized.isEmpty()) return;
        try {
            ensureCollection(vectorized.get(0).embedding().size());
            List<Map<String, Object>> points = new ArrayList<>();
            for (VideoChunk chunk : vectorized) {
                Map<String, Object> payload = Map.of(
                        "mediaId", mediaId,
                        "startMs", chunk.startTime(),
                        "endMs", chunk.endTime());
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", pointId(mediaId, chunk));
                point.put("vector", chunk.embedding());
                point.put("payload", payload);
                points.add(point);
            }
            execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points?wait=true")
                    .put(RequestBody.create(json(Map.of("points", points)), JSON_MEDIA_TYPE)));
        } catch (RuntimeException e) {
            collectionReady.set(false);
            throw new IllegalStateException("Qdrant 分段向量写入失败", e);
        }
    }

    public List<VectorHit> search(Long mediaId, List<Double> queryEmbedding, int limit) {
        if (!enabled || queryEmbedding.isEmpty()) return List.of();
        try {
            ensureCollection(queryEmbedding.size());
            Map<String, Object> filter = mediaFilter(mediaId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", queryEmbedding);
            body.put("filter", filter);
            body.put("limit", limit);
            body.put("with_payload", true);
            String response = execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points/query")
                    .post(RequestBody.create(json(body), JSON_MEDIA_TYPE)));

            JsonNode points = read(response).path("result").path("points");
            if (!points.isArray()) return List.of();
            List<VectorHit> hits = new ArrayList<>(points.size());
            for (JsonNode point : points) {
                JsonNode payload = point.path("payload");
                if (!payload.isObject()) continue;
                hits.add(new VectorHit(
                        payload.path("startMs").asLong(),
                        payload.path("endMs").asLong(),
                        point.path("score").asDouble()));
            }
            return hits;
        } catch (RuntimeException e) {
            collectionReady.set(false);
            throw new IllegalStateException("Qdrant 语义检索失败", e);
        }
    }

    public void deleteMedia(Long mediaId) {
        if (!enabled) return;
        try {
            execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points/delete?wait=true")
                    .post(RequestBody.create(
                            json(Map.of("filter", mediaFilter(mediaId))), JSON_MEDIA_TYPE)));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Qdrant 媒体向量清理失败: " + mediaId, e);
        }
    }

    private void ensureCollection(int vectorSize) {
        if (collectionReady.get()) return;
        synchronized (collectionReady) {
            if (collectionReady.get()) return;
            Request.Builder lookup = request(baseUrl + "/collections/" + collection).get();
            try (Response response = client.newCall(lookup.build()).execute()) {
                if (response.isSuccessful()) {
                    String body = response.body() == null ? "" : response.body().string();
                    int storedSize = read(body).path("result").path("config")
                            .path("params").path("vectors").path("size").asInt(-1);
                    if (storedSize != vectorSize) {
                        throw new IllegalStateException(
                                "Qdrant collection vector size mismatch: expected="
                                        + vectorSize + ", actual=" + storedSize);
                    }
                    collectionReady.set(true);
                    return;
                }
                if (response.code() != 404) {
                    throw new IllegalStateException("Qdrant collection lookup failed: " + response.code());
                }
            } catch (Exception e) {
                throw new IllegalStateException("Qdrant collection lookup failed", e);
            }

            execute(request(baseUrl + "/collections/" + collection)
                    .put(RequestBody.create(json(Map.of(
                            "vectors", Map.of("size", vectorSize, "distance", "Cosine"))),
                            JSON_MEDIA_TYPE)));
            collectionReady.set(true);
        }
    }

    private String execute(Request.Builder request) {
        try (Response response = client.newCall(withApiKey(request).build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Qdrant API failed: " + response.code() + " " + body);
            }
            return body;
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant request failed", e);
        }
    }

    private Request.Builder request(String url) {
        return withApiKey(new Request.Builder().url(url));
    }

    private Request.Builder withApiKey(Request.Builder request) {
        if (!apiKey.isBlank()) request.header("api-key", apiKey);
        return request;
    }

    private Map<String, Object> mediaFilter(Long mediaId) {
        return Map.of("must", List.of(Map.of(
                "key", "mediaId",
                "match", Map.of("value", mediaId))));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Qdrant request serialization failed", e);
        }
    }

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Qdrant response parsing failed", e);
        }
    }

    private String pointId(Long mediaId, VideoChunk chunk) {
        String source = mediaId + ":" + chunk.startTime() + ":" + chunk.endTime();
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public record VectorHit(long startMs, long endMs, double score) {
    }
}
