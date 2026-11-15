package dev.horizon.android;

/** Prefer a supported refresh rate without changing the panel's resolution. */
final class RefreshRatePolicy {
    static int choose(int width,int height,int[] widths,int[] heights,float[] rates,int target) {
        int best=-1;
        for(int i=0;i<rates.length;i++) {
            if(widths[i]!=width || heights[i]!=height || rates[i]<=0 || rates[i]>target+0.6f) continue;
            if(best<0 || rates[i]>rates[best]) best=i;
        }
        return best;
    }
}
