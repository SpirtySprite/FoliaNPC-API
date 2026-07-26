package net.folianpc.internal.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.folianpc.api.Skin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

// Parsing lives in small static methods so it unit-tests without any network.
public final class MojangSkinService {

    private static final String NAME_TO_ID = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String ID_TO_PROFILE = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    record Cached(CompletableFuture<Skin> skin, long expiresAt) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ConcurrentHashMap<String, Cached> nameCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Cached> idCache = new ConcurrentHashMap<>();

    private volatile long ttlMillis = Duration.ofMinutes(30).toMillis();

    public void ttl(Duration ttl) {
        this.ttlMillis = Math.max(0, ttl.toMillis());
    }

    public CompletableFuture<Skin> byName(String name) {
        return lookup(nameCache, name.toLowerCase(Locale.ROOT),
                key -> get(NAME_TO_ID + key).thenCompose(body -> byId(parseId(body))));
    }

    public CompletableFuture<Skin> byId(UUID id) {
        return lookup(idCache, id,
                key -> get(ID_TO_PROFILE + undash(key) + "?unsigned=false").thenApply(MojangSkinService::parseSkin));
    }

    // A failure is never kept: caching a failed future would mean one network blip breaks that skin
    // until the server restarts.
    <K> CompletableFuture<Skin> lookup(ConcurrentHashMap<K, Cached> cache, K key,
                                       Function<K, CompletableFuture<Skin>> fetch) {
        long now = System.currentTimeMillis();
        Cached existing = cache.get(key);
        if (existing != null && existing.expiresAt() > now && !existing.skin().isCompletedExceptionally()) {
            return existing.skin();
        }
        CompletableFuture<Skin> skin = fetch.apply(key);
        cache.put(key, new Cached(skin, now + ttlMillis));
        skin.whenComplete((result, error) -> {
            if (error != null) {
                cache.remove(key);
            }
        });
        return skin;
    }

    private static final String MINESKIN = "https://api.mineskin.org/generate/url";

    // Not cached: Mineskin is heavily rate-limited.
    public CompletableFuture<Skin> byUrl(String imageUrl) {
        String body = "{\"url\":\"" + imageUrl.replace("\"", "\\\"") + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(MINESKIN))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Mineskin returned " + response.statusCode());
            }
            return parseMineskin(response.body());
        });
    }

    static Skin parseMineskin(String json) {
        JsonObject texture = JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonObject("data").getAsJsonObject("texture");
        return Skin.of(texture.get("value").getAsString(), texture.get("signature").getAsString());
    }

    private CompletableFuture<String> get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            String body = response.body();
            if (response.statusCode() != 200 || body == null || body.isBlank()) {
                throw new IllegalStateException("Mojang API returned " + response.statusCode() + " for " + url);
            }
            return body;
        });
    }

    public void close() {
        http.shutdownNow();
    }

    static UUID parseId(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return dash(root.get("id").getAsString());
    }

    static Skin parseSkin(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray properties = root.getAsJsonArray("properties");
        for (JsonElement element : properties) {
            JsonObject property = element.getAsJsonObject();
            if ("textures".equals(property.get("name").getAsString())) {
                String value = property.get("value").getAsString();
                String signature = property.has("signature") ? property.get("signature").getAsString() : null;
                return Skin.of(value, signature);
            }
        }
        throw new IllegalStateException("Profile has no textures property");
    }

    private static String undash(UUID id) {
        return id.toString().replace("-", "");
    }

    static UUID dash(String undashed) {
        return UUID.fromString(undashed.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                "$1-$2-$3-$4-$5"));
    }
}