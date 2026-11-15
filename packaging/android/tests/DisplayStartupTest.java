package dev.horizon.android;

public final class DisplayStartupTest {
    public static void main(String[] args) {
        int[] widths={1080,1080,1080,1440}, heights={2400,2400,2400,3200};
        float[] rates={60,90,120,144};
        if(RefreshRatePolicy.choose(1080,2400,widths,heights,rates,120)!=2) throw new AssertionError("120 Hz unavailable");
        if(RefreshRatePolicy.choose(1080,2400,widths,heights,rates,60)!=0) throw new AssertionError("60 Hz setting ignored");
        if(RefreshRatePolicy.choose(1080,2400,widths,heights,new float[]{60,90,144,120},120)!=1) throw new AssertionError("Unsupported mode or resolution selected");
        LaunchSurfaceState surface=new LaunchSurfaceState();
        if(surface.ready(true,1000)) throw new AssertionError("Missing surface allowed");
        surface.changed(1080,2400,1000);
        if(surface.ready(true,1500)) throw new AssertionError("Portrait surface allowed");
        surface.changed(2400,1080,1500);
        if(surface.ready(false,1800) || surface.ready(true,1600)) throw new AssertionError("Unsettled rotation allowed");
        if(!surface.ready(true,1800)) throw new AssertionError("Settled landscape rejected");
        surface.changed(2200,1080,1800);
        if(surface.ready(true,1900)) throw new AssertionError("Resize didn't reset gate");
        surface.destroyed();
        if(surface.ready(true,3000)) throw new AssertionError("Destroyed surface allowed");
        System.out.println("Display modes and startup surface lifecycle passed");
    }
}
