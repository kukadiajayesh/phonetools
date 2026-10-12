package dev.local.phonetools;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;

/**
 * Reads the small /proc and /sys files that are polled every second into one reused buffer. Going through
 * BufferedReader and FileReader allocated about 24 KB of buffers per file, some 70 files a second, which kept the
 * garbage collector busier than the app itself.
 */
final class SysFiles {
    // Holds the start of /proc/stat (its first line is all that is read) and all of /proc/meminfo.
    private static final byte[] buffer = new byte[8192];
    private static int length;

    private SysFiles() {
    }

    /** Returns the number at the start of the file, or {@code fallback} if it cannot be read. */
    static synchronized long readLong(String path, long fallback) {
        if (!load(path)) return fallback;
        return number(0, fallback);
    }

    /**
     * Fills {@code values} with the numbers after each of {@code keys} (such as "MemTotal:") at the start of a line,
     * or {@code fallback} for a key the file lacks. Returns false if the file cannot be read.
     */
    static synchronized boolean readFields(String path, String[] keys, long[] values, long fallback) {
        if (!load(path)) return false;
        for (int k = 0; k < keys.length; k++) {
            values[k] = fallback;
            for (int line = 0; line < length; line = nextLine(line)) {
                if (startsWith(line, keys[k])) {
                    values[k] = number(line + keys[k].length(), fallback);
                    break;
                }
            }
        }
        return true;
    }

    /**
     * Fills {@code values} with the numbers on the file's first line after its label (as in /proc/stat's "cpu  1 2 3")
     * and returns how many there were, or -1 if the file cannot be read.
     */
    static synchronized int readFirstLine(String path, long[] values) {
        if (!load(path)) return -1;
        int i = 0, count = 0;
        while (i < length && buffer[i] != ' ' && buffer[i] != '\n') i++;
        while (count < values.length) {
            while (i < length && buffer[i] == ' ') i++;
            if (i >= length || buffer[i] < '0' || buffer[i] > '9') break;
            long value = 0;
            while (i < length && buffer[i] >= '0' && buffer[i] <= '9') value = value * 10 + buffer[i++] - '0';
            values[count++] = value;
        }
        return count;
    }

    private static boolean load(String path) {
        length = 0;
        FileDescriptor fd;
        try {
            fd = Os.open(path, OsConstants.O_RDONLY, 0);
        } catch (ErrnoException e) {
            return false;
        }
        try {
            int read;
            while (length < buffer.length && (read = Os.read(fd, buffer, length, buffer.length - length)) > 0) {
                length += read;
            }
            return true;
        } catch (ErrnoException | java.io.InterruptedIOException e) {
            return false;
        } finally {
            try {
                Os.close(fd);
            } catch (ErrnoException ignored) {
            }
        }
    }

    /** Parses the (possibly negative) whole number at {@code i}, after any spaces or tabs. */
    private static long number(int i, long fallback) {
        while (i < length && (buffer[i] == ' ' || buffer[i] == '\t')) i++;
        boolean negative = i < length && buffer[i] == '-';
        if (negative) i++;
        if (i >= length || buffer[i] < '0' || buffer[i] > '9') return fallback;
        long value = 0;
        while (i < length && buffer[i] >= '0' && buffer[i] <= '9') value = value * 10 + buffer[i++] - '0';
        return negative ? -value : value;
    }

    private static boolean startsWith(int i, String key) {
        if (i + key.length() > length) return false;
        for (int k = 0; k < key.length(); k++) if (buffer[i + k] != key.charAt(k)) return false;
        return true;
    }

    private static int nextLine(int i) {
        while (i < length && buffer[i] != '\n') i++;
        return i + 1;
    }
}
