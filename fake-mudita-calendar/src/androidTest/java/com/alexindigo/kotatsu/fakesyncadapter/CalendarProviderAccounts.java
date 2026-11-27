package com.alexindigo.kotatsu.fakesyncadapter;

/** Discard old CalendarProvider account snapshots before writing calendars on a disposable CI AVD. */
public final class CalendarProviderAccounts {
    private static final String PROVIDER = "com.android.providers.calendar";
    private static final String SYNC_TEST_TYPE = "com.alexindigo.kotatsu.sync.test";

    public static void main(String[] args) {
        try {
            FixtureShell.requireCiAvd();
            if (args.length != 1) throw new IllegalArgumentException("Expected calendar fixture account name");
            acknowledge(args[0], SYNC_TEST_TYPE);
            System.out.println("calendar provider ready");
            System.exit(0);
        } catch (Exception error) {
            error.printStackTrace(System.out);
            System.exit(1);
        }
    }

    static void acknowledge(String name, String type) throws Exception {
        if (!name.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Invalid calendar fixture account name");
        String account = "Account {name=" + name + ", type=" + type + "}";
        if (!FixtureShell.run("dumpsys", "account").contains(account))
            throw new IllegalStateException("Calendar fixture account is not registered: " + account);

        // CalendarProvider starts independent workers with captured account arrays.
        // A newer worker finishing cannot fence an older one. Start a fresh
        // process after registration; none of its snapshots can predate the account.
        String previous = FixtureShell.pidOf(PROVIDER);
        FixtureShell.run("am", "force-stop", "--user", "0", PROVIDER);
        String read = FixtureShell.run("content", "query", "--uri", "content://com.android.calendar/calendars",
                "--projection", "_id", "--where", "0");
        String current = FixtureShell.pidOf(PROVIDER);
        if (!read.equals("No result found.") || !current.matches("[0-9]+") || current.equals(previous))
            throw new IllegalStateException("CalendarProvider did not acknowledge fresh process: " + previous + " -> " + current + " (" + read + ")");
    }
}
