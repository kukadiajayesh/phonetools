package dev.local.phonetools;

import android.system.Os;
import android.system.OsConstants;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;

/**
 * Measures the average CPU use, over all cores, between calls to {@link #sample}.
 *
 * <p>Reads /proc/stat where an app may (this Moto G, with SELinux permissive). Stock Android 8+ blocks it, and every
 * other system-wide counter, but not each core's cpuidle statistics: there the use is the time a core spent outside
 * its idle states. Those counters are only brought up to date when a core leaves an idle state, so a core that
 * entered none during the interval was either asleep or busy throughout; it is taken as busy when its clock is in
 * the upper part of its range (sleeping cores are left at their low speeds). On a OnePlus 9 Pro this came within a
 * few percent of /proc/stat, idle and under load.
 */
final class CpuMeter {
    /** Returned by {@link #sample} when this phone allows no way of measuring. */
    static final int UNAVAILABLE = -2;
    /** Returned by {@link #sample} for the first reading, or after a pause: there is nothing to compare with yet. */
    static final int PENDING = -1;

    private static final String CPU = "/sys/devices/system/cpu/";
    // Above this share of its speed range, a core that never went idle is counted as busy rather than asleep.
    private static final float BUSY_SPEED = 0.4f;

    private final int cores = cpuCount();
    private long lastTime;
    private long lastBusy = -1;
    // Per core: total idle time (µs) and idle entries; -1 for a core that is offline or has no cpuidle statistics.
    private long[] lastIdle, lastEntries;
    private String[][] idleStates;
    private long[] minSpeed, maxSpeed;

    /**
     * Returns the CPU use in percent since the previous call, {@link #PENDING} if that was longer than {@code maxGapMs}
     * ago (or this is the first call), or {@link #UNAVAILABLE}.
     */
    int sample(long now, long maxGapMs) {
        long gap = now - lastTime;
        boolean fresh = lastTime != 0 && gap > 0 && gap <= maxGapMs;
        lastTime = now;

        long busy = readProcStat();
        if (busy >= 0) {
            int result = PENDING;
            if (fresh && lastBusy >= 0 && busy >= lastBusy) {
                // Idle counters jump when cores go offline, so compare busy time to wall time on all cores.
                double capacity = gap / 1000.0 * Os.sysconf(OsConstants._SC_CLK_TCK) * cores;
                result = clamp((busy - lastBusy) * 100.0 / capacity);
            }
            lastBusy = busy;
            return result;
        }

        long[] idle = new long[cores], entries = new long[cores];
        if (!readIdle(idle, entries)) return UNAVAILABLE;
        int result = PENDING;
        if (fresh && lastIdle != null) {
            double wall = gap * 1000.0, busyCores = 0;
            for (int i = 0; i < cores; i++) {
                // Offline cores do no work, as with /proc/stat.
                if (idle[i] < 0 || lastIdle[i] < 0 || idle[i] < lastIdle[i]) continue;
                if (entries[i] > lastEntries[i]) {
                    busyCores += Math.max(0, Math.min(1, 1 - (idle[i] - lastIdle[i]) / wall));
                } else if (speedShare(i) > BUSY_SPEED) {
                    busyCores += 1;
                }
            }
            result = clamp(busyCores * 100 / cores);
        }
        lastIdle = idle;
        lastEntries = entries;
        return result;
    }

    /** Fills in each core's idle time and idle entries; returns false if no core has readable statistics. */
    private boolean readIdle(long[] idle, long[] entries) {
        if (idleStates == null) {
            idleStates = new String[cores][];
            for (int i = 0; i < cores; i++) {
                File[] states = new File(CPU + "cpu" + i + "/cpuidle").listFiles(new FilenameFilter() {
                    @Override
                    public boolean accept(File dir, String name) {
                        return name.startsWith("state");
                    }
                });
                idleStates[i] = new String[states == null ? 0 : states.length];
                for (int s = 0; s < idleStates[i].length; s++) idleStates[i][s] = states[s].getPath();
            }
        }
        boolean any = false;
        for (int i = 0; i < cores; i++) {
            idle[i] = -1;
            // cpu0 often has no "online" file: it cannot be taken offline.
            if (idleStates[i].length == 0 || readLong(CPU + "cpu" + i + "/online", 1) == 0) continue;
            long time = 0, count = 0;
            for (String state : idleStates[i]) {
                long t = readLong(state + "/time", -1), u = readLong(state + "/usage", -1);
                if (t < 0 || u < 0) {
                    time = -1;
                    break;
                }
                time += t;
                count += u;
            }
            if (time < 0) continue;
            idle[i] = time;
            entries[i] = count;
            any = true;
        }
        return any;
    }

    /** Where core {@code i}'s current clock lies in its range, from 0 (slowest) to 1 (fastest); 0 if unknown. */
    private float speedShare(int i) {
        if (minSpeed == null) {
            minSpeed = new long[cores];
            maxSpeed = new long[cores];
            for (int c = 0; c < cores; c++) {
                minSpeed[c] = readLong(CPU + "cpu" + c + "/cpufreq/cpuinfo_min_freq", 0);
                maxSpeed[c] = readLong(CPU + "cpu" + c + "/cpufreq/cpuinfo_max_freq", 0);
            }
        }
        long now = readLong(CPU + "cpu" + i + "/cpufreq/scaling_cur_freq", 0);
        if (now <= 0 || maxSpeed[i] <= minSpeed[i]) return 0;
        return (now - minSpeed[i]) / (float) (maxSpeed[i] - minSpeed[i]);
    }

    private static int clamp(double percent) {
        return (int) Math.max(0, Math.min(100, Math.round(percent)));
    }

    /** Returns busy jiffies (user, nice, system, irq, softirq, steal) from the first line of /proc/stat, or -1. */
    private static long readProcStat() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String[] parts = reader.readLine().trim().split("\\s+");
            long busy = 0;
            for (int i : new int[] {1, 2, 3, 6, 7, 8}) if (i < parts.length) busy += Long.parseLong(parts[i]);
            return busy;
        } catch (Exception ignored) {
            return -1;
        }
    }

    /** Counts possible cores (online or not) from a range list like "0-3". */
    private static int cpuCount() {
        try (BufferedReader reader = new BufferedReader(new FileReader(CPU + "possible"))) {
            int count = 0;
            for (String range : reader.readLine().trim().split(",")) {
                String[] ends = range.split("-");
                count += ends.length == 2 ? Integer.parseInt(ends[1]) - Integer.parseInt(ends[0]) + 1 : 1;
            }
            return count;
        } catch (Exception ignored) {
            return Runtime.getRuntime().availableProcessors();
        }
    }

    private static long readLong(String path, long fallback) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return Long.parseLong(reader.readLine().trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
