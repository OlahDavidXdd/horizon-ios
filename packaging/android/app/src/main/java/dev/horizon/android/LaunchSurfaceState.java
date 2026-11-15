package dev.horizon.android;

/** GPU startup waits for a valid, settled landscape native surface. */
final class LaunchSurfaceState {
    int width,height; long changedAt;
    void changed(int width,int height,long now) { this.width=width; this.height=height; changedAt=now; }
    void destroyed() { width=height=0; }
    boolean ready(boolean landscape,long now) { return landscape && width>height && height>0 && now-changedAt>=200; }
}
