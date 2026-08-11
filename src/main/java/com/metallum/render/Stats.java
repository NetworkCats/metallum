package com.metallum.render;

import com.metallum.Metallum;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in renderer counters.  The hot path is a single, predictable boolean
 * branch when {@code -Dmetallum.stats=true} is not present.
 */
@Environment(EnvType.CLIENT)
public final class Stats {
    public enum Counter {
        DRAWS,
        ICB_EXECUTIONS,
        ICB_COMMANDS,
        ENCODER_SWITCHES,
        BARRIERS,
        FENCE_UPDATES,
        FENCE_WAITS,
        BINDING_CALLS,
        BINDING_CACHE_HITS,
        UPLOAD_BYTES,
        TRANSIENT_BLOCKS,
        TRANSIENT_HIGH_WATER_BYTES,
        NATIVE_RESOURCES_CREATED,
        PIPELINES_COMPILED,
        PIPELINE_CACHE_HITS,
        SHADERS_COMPILED,
        SHADER_CACHE_HITS,
        NEXT_DRAWABLE_WAIT_NANOS
    }

    public record Snapshot(long frame, long[] values) {
        public long value(final Counter counter) {
            return this.values[counter.ordinal()];
        }

        @Override
        public long[] values() {
            return this.values.clone();
        }
    }

    private static final boolean ENABLED = Boolean.getBoolean("metallum.stats");
    private static final int LOG_INTERVAL = Integer.getInteger("metallum.stats.logInterval", 600);
    private static final LongAdder[] COUNTERS = createCounters();
    private static volatile long frame;

    private Stats() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void increment(final Counter counter) {
        if (ENABLED) {
            COUNTERS[counter.ordinal()].increment();
        }
    }

    public static void add(final Counter counter, final long value) {
        if (ENABLED && value != 0L) {
            COUNTERS[counter.ordinal()].add(value);
        }
    }

    /** Records a maximum without adding synchronization to the disabled path. */
    public static void recordHighWater(final Counter counter, final long value) {
        if (!ENABLED) {
            return;
        }
        LongAdder adder = COUNTERS[counter.ordinal()];
        synchronized (adder) {
            long current = adder.sum();
            if (value > current) {
                adder.add(value - current);
            }
        }
    }

    public static void endFrame() {
        if (!ENABLED) {
            return;
        }
        long currentFrame = ++frame;
        if (LOG_INTERVAL > 0 && currentFrame % LOG_INTERVAL == 0L) {
            Snapshot snapshot = snapshot();
            Metallum.LOGGER.info("[metallum] performance counters at frame {}: {}",
                    currentFrame, Arrays.toString(snapshot.values));
        }
    }

    public static Snapshot snapshot() {
        long[] values = new long[COUNTERS.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = COUNTERS[i].sum();
        }
        return new Snapshot(frame, values);
    }

    public static Snapshot snapshotAndReset() {
        long[] values = new long[COUNTERS.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = COUNTERS[i].sumThenReset();
        }
        return new Snapshot(frame, values);
    }

    private static LongAdder[] createCounters() {
        LongAdder[] counters = new LongAdder[Counter.values().length];
        Arrays.setAll(counters, ignored -> new LongAdder());
        return counters;
    }
}
