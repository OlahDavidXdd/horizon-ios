package dev.horizon.android;

/** Selector geometry independent of WebView layout, pointer capture and animation frames. */
final class GlassDragState {
    final int count;
    int selected;
    boolean pressed;
    float left, slot, position, anchor, start;
    GlassDragState(int count) { this.count=count; }
    void layout(float width,float inset) {
        left=inset; slot=Math.max(1,(width-2*inset)/count); pressed=false; position=center(selected);
    }
    float center(int index) { return left+slot*(index+0.5f); }
    int index(float x) { return Math.max(0,Math.min(count-1,(int)Math.floor((x-left)/slot))); }
    void begin(float x) { pressed=true; start=x; anchor=center(index(x)); position=anchor; }
    void move(float x) { if(pressed) position=Math.max(center(0),Math.min(center(count-1),anchor+x-start)); }
    int end(boolean commit) {
        if(pressed && commit) selected=index(position);
        pressed=false; return selected;
    }
    void select(int index) { selected=Math.max(0,Math.min(count-1,index)); if(!pressed) position=center(selected); }
}
