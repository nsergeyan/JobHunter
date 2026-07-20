package com.jobscout;

import java.io.IOException;

/**
 * Keeps macOS from sleeping for as long as this JVM process is alive, so long-running
 * scraper/extraction runs survive the laptop lid closing or going idle.
 *
 * Uses `caffeinate -w <pid>` rather than a fixed timeout: it watches this exact
 * process and stops preventing sleep the moment the JVM exits (normally or via
 * crash) -- no shutdown hook or manual cleanup needed. No-op on non-macOS (caffeinate
 * doesn't exist elsewhere) and never fails the run if it can't start -- sleep
 * prevention is a convenience, not something worth crashing a scrape over.
 *
 * Best-effort, not a hard guarantee: this prevents idle/system sleep including
 * clamshell (lid-close) sleep in the common case, but things like critically low
 * battery can still force sleep regardless. Keep the laptop plugged in for long runs.
 */
final class SleepPrevention {
    private SleepPrevention() {
    }

    static void preventSleepWhileRunning() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            return;
        }
        try {
            long pid = ProcessHandle.current().pid();
            new ProcessBuilder("caffeinate", "-i", "-s", "-w", String.valueOf(pid))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            System.out.println("Sleep prevention active (caffeinate) for this run.");
        } catch (IOException exc) {
            System.out.println("Could not start caffeinate, continuing without sleep prevention: " + exc.getMessage());
        }
    }
}
