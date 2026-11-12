# TODO

- Design how lock-screen widget taps that open an app should behave. Android 12 gives no reliable launch signal to an accessibility service (the generic splash or snapshot window event carries no target), so today's "wait for the launch, then show the PIN" can miss a launch; the app then opens behind the lock screen. Options studied: keep passing the tap through, read the tap's PendingIntent type before sending it (`PendingIntent.isActivity()`, API 31) to hold app-opening taps back or ask for the PIN first, or host the side screen in an activity. Not started.
