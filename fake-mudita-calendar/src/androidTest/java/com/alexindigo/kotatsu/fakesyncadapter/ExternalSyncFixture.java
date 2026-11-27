package com.alexindigo.kotatsu.fakesyncadapter;

import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Shell entry point shared by Sync instrumentation and the seeded suite verifier. */
public final class ExternalSyncFixture {
    static final String NAME_PREFIX = "kotatsu-external-test-";
    static final String ACCOUNT_TYPE = "at.bitfire.davdroid.address_book";
    static final String REGISTER = "register", PUBLISH = "publish-book", CALENDAR = "seed-calendar", CLEANUP = "cleanup";
    private static final String PACKAGE = "com.alexindigo.kotatsu.fakemuditacalendar.test";

    static boolean validName(String name) {
        return name != null && name.startsWith(NAME_PREFIX) && name.matches("[A-Za-z0-9._-]+");
    }

    public static void main(String[] args) {
        try {
            if (!"ranchu".equals(Build.HARDWARE) || !"userdebug".equals(Build.TYPE) || Build.VERSION.SDK_INT != 31)
                throw new IllegalStateException("External fixture requires a disposable AOSP API 31 AVD");
            if (args.length != 2 || !validName(args[1])) throw new IllegalArgumentException("Fixture name");
            String name = args[1];
            switch (args[0]) {
                case "seed":
                    broadcast(REGISTER, name);
                    try {
                        publishBook(name);
                        broadcast(CALENDAR, name);
                    } catch (Exception error) {
                        try { broadcast(CLEANUP, name); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                        throw error;
                    }
                    break;
                case REGISTER:
                case CLEANUP:
                    broadcast(args[0], name);
                    break;
                case PUBLISH:
                    publishBook(name);
                    break;
                default:
                    throw new IllegalArgumentException("Fixture operation: " + args[0]);
            }
            // The caller uses the same identity for row creation, selection and snapshots.
            System.out.println(ACCOUNT_TYPE);
            System.exit(0);
        } catch (Exception error) {
            error.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void publishBook(String name) throws Exception {
        shell("content", "query", "--uri", "content://com.android.contacts/raw_contacts", "--projection", "_id", "--where", "0");
        ContactsProviderAccounts.await(name + ":" + ACCOUNT_TYPE);
        broadcast(PUBLISH, name);
    }

    private static void broadcast(String operation, String name) throws Exception {
        String output = shell("am", "broadcast", "--receiver-foreground", "--include-stopped-packages", "-n",
                PACKAGE + "/" + FixtureReceiver.class.getName(), "--es", "operation", operation, "--es", "name", name);
        if (!output.contains("result=0, data=\"fixture complete\"")) throw new IOException(output);
    }

    private static String shell(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (var stream = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            for (int count; (count = stream.read(buffer)) != -1; ) captured.write(buffer, 0, count);
        }
        String output = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException(output);
        return output;
    }
}
