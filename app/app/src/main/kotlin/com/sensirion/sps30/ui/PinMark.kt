package com.sensirion.sps30.ui

/**
 * Whether the global pin time mark at [epoch] is drawn on a view spanning
 * [start]..[end].
 *
 * The mark is the one navigation anchor the user places deliberately - the
 * long press on Pin - and it anchors the window-size buttons for as long as
 * the view is pinned, so it has to be visible as a mark: a pin the user can
 * only find again by remembering where they put it is not a mark, and a pin
 * that silently stopped being drawn is indistinguishable from one the app
 * threw away (which is exactly what a window-size tap used to do).
 *
 * It is a mark on the *record*, not on the pinned state, so it is drawn
 * exactly when its instant is inside the view - in every mode, pinned or not.
 * Off-view it simply waits; navigating back across it brings it back. One
 * rule, stated here and consumed by [drawPmChart], so the screen and the
 * exported image cannot disagree about it.
 */
internal fun pinMarkVisible(epoch: Double?, start: Double, end: Double): Boolean =
    epoch != null && epoch >= start && epoch <= end
