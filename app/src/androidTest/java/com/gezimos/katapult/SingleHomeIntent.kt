package com.gezimos.katapult

import androidx.test.core.app.ActivityScenario

/** HOME navigation is independent of the service's physical-key gesture. */
internal fun ActivityScenario<MainActivity>.sendSingleHomeIntent() {
    onActivity {
        it.viewModel.onHomeIntent()
    }
}
