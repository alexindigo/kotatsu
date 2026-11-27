package com.alexindigo.kotatsu.fakesyncadapter;

import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Commands issued by the fixture APK's standalone, bounded shell entry points. */
final class FixtureShell {
    private FixtureShell() {}

    static void requireCiAvd() throws Exception {
        if (!"ranchu".equals(Build.HARDWARE) || !"userdebug".equals(Build.TYPE) || Build.VERSION.SDK_INT != 31
                || !run("getprop", "ro.kernel.qemu").equals("1")
                || !run("am", "get-current-user").equals("0"))
            throw new IllegalStateException("Fixture requires a disposable AOSP API 31 AVD running as user 0");
    }

    static String run(String... args) throws Exception {
        return run(false, args);
    }

    static String pidOf(String name) throws Exception {
        return run(true, "pidof", name);
    }

    private static String run(boolean allowNoPid, String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (var stream = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            for (int count; (count = stream.read(buffer)) != -1; ) captured.write(buffer, 0, count);
        }
        String output = new String(captured.toByteArray(), StandardCharsets.UTF_8).trim();
        int rc = process.waitFor();
        if (rc != 0 && !(allowNoPid && rc == 1 && output.isEmpty()))
            throw new IOException(args[0] + " exited " + rc + ": " + output);
        return output;
    }
}
