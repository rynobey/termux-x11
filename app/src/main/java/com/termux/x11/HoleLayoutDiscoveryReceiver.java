package com.termux.x11;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * No-op BroadcastReceiver declared in AndroidManifest only so that compatible
 * IMEs (e.g. the Unexpected Keyboard fork) can discover this app under
 * Android 11+ package-visibility rules.
 *
 * The actual hole-layout handling is done by a *runtime*-registered receiver
 * in {@link MainActivity#onCreate} — that one only listens while the activity
 * is alive, which is the correct scope for resizing the X canvas. This
 * manifest receiver exists purely so the IME's
 * {@code <queries><intent action="com.rynobey.uxk.HOLE_LAYOUT_CHANGED"/></queries>}
 * makes our package visible to it.
 *
 * Broadcasts that arrive here (because the IME used {@code setPackage} and
 * we're not foregrounded) are intentionally dropped.
 */
public class HoleLayoutDiscoveryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // no-op — discovery handle only
    }
}
