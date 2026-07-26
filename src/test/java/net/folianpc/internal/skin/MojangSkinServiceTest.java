package net.folianpc.internal.skin;

import net.folianpc.api.Skin;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MojangSkinServiceTest {

    @Test
    void parsesUndashedIdIntoUuid() {
        String json = "{\"id\":\"853c80ef3c3749fdaa49938b674adae6\",\"name\":\"jeb_\"}";
        assertEquals(UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6"), MojangSkinService.parseId(json));
    }

    @Test
    void parsesTexturesProperty() {
        String json = "{\"id\":\"x\",\"name\":\"n\",\"properties\":["
                + "{\"name\":\"textures\",\"value\":\"BASE64VALUE\",\"signature\":\"SIG\"}]}";
        Skin skin = MojangSkinService.parseSkin(json);
        assertEquals("BASE64VALUE", skin.value());
        assertEquals("SIG", skin.signature());
    }

    @Test
    void allowsMissingSignature() {
        String json = "{\"properties\":[{\"name\":\"textures\",\"value\":\"V\"}]}";
        Skin skin = MojangSkinService.parseSkin(json);
        assertEquals("V", skin.value());
        assertNull(skin.signature());
    }

    @Test
    void successfulLookupsAreCached() {
        MojangSkinService service = new MojangSkinService();
        var cache = new ConcurrentHashMap<String, MojangSkinService.Cached>();
        AtomicInteger fetches = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            service.lookup(cache, "steve", key -> {
                fetches.incrementAndGet();
                return CompletableFuture.completedFuture(Skin.of("value", "signature"));
            });
        }

        assertEquals(1, fetches.get(), "the skin should be fetched once and reused");
    }

    @Test
    void failedLookupsAreNotCached() {
        MojangSkinService service = new MojangSkinService();
        var cache = new ConcurrentHashMap<String, MojangSkinService.Cached>();
        AtomicInteger fetches = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            service.lookup(cache, "steve", key -> {
                fetches.incrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("mojang is down"));
            });
        }

        assertEquals(3, fetches.get(), "one network blip must not break a skin until restart");
        assertTrue(cache.isEmpty());
    }

    @Test
    void anExpiredEntryIsRefetched() {
        MojangSkinService service = new MojangSkinService();
        service.ttl(Duration.ZERO);
        var cache = new ConcurrentHashMap<String, MojangSkinService.Cached>();
        AtomicInteger fetches = new AtomicInteger();

        service.lookup(cache, "steve", key -> {
            fetches.incrementAndGet();
            return CompletableFuture.completedFuture(Skin.of("value", null));
        });
        service.lookup(cache, "steve", key -> {
            fetches.incrementAndGet();
            return CompletableFuture.completedFuture(Skin.of("value", null));
        });

        assertEquals(2, fetches.get(), "an expired skin is fetched again");
    }

    @Test
    void parsesMineskinResponse() {
        String json = "{\"data\":{\"texture\":{\"value\":\"V\",\"signature\":\"S\"}}}";
        Skin skin = MojangSkinService.parseMineskin(json);
        assertEquals("V", skin.value());
        assertEquals("S", skin.signature());
    }

    @Test
    void throwsWhenNoTextures() {
        String json = "{\"properties\":[{\"name\":\"other\",\"value\":\"V\"}]}";
        assertThrows(IllegalStateException.class, () -> MojangSkinService.parseSkin(json));
    }
}
