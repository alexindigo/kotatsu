package com.gezimos.katapult.ui

/** The paging gesture follows the navigation's presentation, not a separate preference. */
internal fun allAppsSwipeAxis(hideArrowButtons: Boolean): SwipeAxis =
    if (hideArrowButtons) SwipeAxis.VERTICAL else SwipeAxis.HORIZONTAL
