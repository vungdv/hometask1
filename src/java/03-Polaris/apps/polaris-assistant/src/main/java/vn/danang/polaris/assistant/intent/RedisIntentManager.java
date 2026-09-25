package vn.danang.polaris.assistant.intent;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.config.AssistantIntentRedisProperties;

/**
 * {@link IntentManager} backed by a Redis-stored intent taxonomy, with a two-layer cache: an
 * in-process cache serves reads without a network round trip, refreshed from Redis (the source
 * of truth) once it expires. Any failure to reach or parse Redis - connection error, missing
 * key, malformed JSON - falls back to the classpath-backed {@link DefaultIntentManager}, so
 * intent resolution keeps working even when Redis is unreachable. Marked {@link Primary} so it
 * is the {@link IntentManager} wired wherever the interface is requested; {@link DefaultIntentManager}
 * remains available as a concrete bean for the fallback path.
 */
@Component
@Primary
public class RedisIntentManager implements IntentManager {

    private static final Logger log = LoggerFactory.getLogger(RedisIntentManager.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StringRedisTemplate redisTemplate;
    private final DefaultIntentManager fallback;
    private final AssistantIntentRedisProperties properties;
    private final Clock clock;

    private final AtomicReference<CachedIntents> cache = new AtomicReference<>();

    @Autowired
    public RedisIntentManager(StringRedisTemplate redisTemplate, DefaultIntentManager fallback,
            AssistantIntentRedisProperties properties) {
        this(redisTemplate, fallback, properties, Clock.systemUTC());
    }

    RedisIntentManager(StringRedisTemplate redisTemplate, DefaultIntentManager fallback,
            AssistantIntentRedisProperties properties, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.fallback = fallback;
        this.properties = properties;
        this.clock = clock;
        seedIfMissing();
    }

    @Override
    public List<IntentDefinition> listIntents() {
        return currentIntents();
    }

    @Override
    public Optional<IntentDefinition> getIntent(String intentId) {
        if (intentId == null) {
            return Optional.empty();
        }
        return currentIntents().stream()
                .filter(def -> intentId.equals(def.id()))
                .findFirst();
    }

    private List<IntentDefinition> currentIntents() {
        Instant now = clock.instant();
        CachedIntents cached = cache.get();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.intents();
        }
        return refresh(now);
    }

    /**
     * Reloads from Redis, double-checking the cache after acquiring the lock so concurrent
     * callers racing past the expired fast-path read don't all hit Redis at once.
     */
    private synchronized List<IntentDefinition> refresh(Instant now) {
        CachedIntents cached = cache.get();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.intents();
        }
        try {
            String json = redisTemplate.opsForValue().get(properties.getKey());
            if (json == null || json.isBlank()) {
                throw new IllegalStateException("Redis key [" + properties.getKey() + "] is missing or empty");
            }
            IntentDefinition[] array = OBJECT_MAPPER.readValue(json, IntentDefinition[].class);
            List<IntentDefinition> intents = array != null ? List.of(array) : List.of();
            cache.set(new CachedIntents(intents, now.plus(properties.getLocalCacheTtl())));
            return intents;
        } catch (Exception e) {
            log.warn("Failed to load intents from Redis, falling back to the default taxonomy: {}", e.getMessage());
            List<IntentDefinition> fallbackIntents = fallback.listIntents();
            cache.set(new CachedIntents(fallbackIntents, now.plus(properties.getFallbackRetryInterval())));
            return fallbackIntents;
        }
    }

    /**
     * Seeds Redis with the classpath taxonomy on first startup so the store isn't empty, using
     * SETNX so concurrent instances never race to overwrite each other's data. Any failure here
     * (Redis unreachable) is swallowed - the fallback taxonomy is still served locally, and
     * seeding is simply retried on the next startup.
     */
    private void seedIfMissing() {
        if (!properties.isSeedIfMissing()) {
            return;
        }
        try {
            List<IntentDefinition> defaults = fallback.listIntents();
            if (defaults.isEmpty()) {
                return;
            }
            Boolean created = redisTemplate.opsForValue()
                    .setIfAbsent(properties.getKey(), OBJECT_MAPPER.writeValueAsString(defaults));
            if (Boolean.TRUE.equals(created)) {
                log.info("Seeded Redis key [{}] with {} default intents", properties.getKey(), defaults.size());
            }
        } catch (Exception e) {
            log.warn("Could not seed Redis with default intents, will rely on fallback until Redis is reachable: {}",
                    e.getMessage());
        }
    }

    private record CachedIntents(List<IntentDefinition> intents, Instant expiresAt) {}
}
