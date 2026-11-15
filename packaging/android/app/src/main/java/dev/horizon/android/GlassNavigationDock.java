package dev.horizon.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.FrameLayout;
import java.util.function.IntConsumer;

/** Native glass bar and finger-tracking pill; no WebView or animation-frame bridge. */
final class GlassNavigationDock extends FrameLayout {
    private final GlassDragState drag=new GlassDragState(3);
    private final WeaveGlassPainter glass;
    private final IntConsumer selected;
    private ValueAnimator settle;
    private float center;
    GlassNavigationDock(Context context,IntConsumer selected) {
        super(context); this.selected=selected; setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        glass=new WeaveGlassPainter(context);
    }
    void backdrop(Bitmap bitmap) { glass.backdrop(bitmap); invalidate(); }
    void reduced(boolean reduced) { glass.reduced(reduced); invalidate(); }
    String backend() { return glass.backend(); }
    private float dp(float value) { return value*getResources().getDisplayMetrics().density; }
    @Override protected void onSizeChanged(int w,int h,int ow,int oh) {
        super.onSizeChanged(w,h,ow,oh); stop(); drag.layout(w,dp(5)); center=drag.position;
    }
    void select(int index) {
        drag.select(index);
        if(!drag.pressed) animateTo(drag.center(drag.selected));
    }
    void cancelGesture() { if(drag.pressed) { drag.end(false); animateTo(drag.center(drag.selected)); } }
    private void stop() { if(settle != null) { settle.cancel(); settle=null; } }
    private void animateTo(float target) {
        stop();
        if(getWidth()==0 || Math.abs(center-target)<0.5f) { center=target; invalidate(); return; }
        settle=ValueAnimator.ofFloat(center,target); settle.setDuration(160);
        settle.addUpdateListener(value -> { center=(float)value.getAnimatedValue(); invalidate(); }); settle.start();
    }
    @Override public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
        return event.getActionMasked()==MotionEvent.ACTION_DOWN && event.getY()>=dp(12) && event.getY()<=getHeight()-dp(10);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stop(); getParent().requestDisallowInterceptTouchEvent(true); drag.begin(event.getX()); center=drag.position; invalidate(); return true;
            case MotionEvent.ACTION_MOVE:
                drag.move(event.getX()); center=drag.position; invalidate(); return true;
            case MotionEvent.ACTION_UP:
                if(drag.pressed) {
                    drag.move(event.getX()); center=drag.position; int index=drag.end(true);
                    animateTo(drag.center(index)); selected.accept(index); performClick();
                }
                getParent().requestDisallowInterceptTouchEvent(false); return true;
            case MotionEvent.ACTION_CANCEL:
                cancelGesture(); getParent().requestDisallowInterceptTouchEvent(false); return true;
            case MotionEvent.ACTION_POINTER_UP:
                if(event.getActionIndex()==0) cancelGesture(); return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if(!focus) cancelGesture(); }
    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if(drag.slot<=1) return;
        float cy=getHeight()/2f;
        glass.draw(canvas,new RectF(dp(5),cy-dp(35),getWidth()-dp(5),cy+dp(35)),dp(1),false,false);
        float width=drag.slot-dp(drag.pressed?0:8), height=dp(drag.pressed?58:50);
        glass.draw(canvas,new RectF(center-width/2,cy-height/2,center+width/2,cy+height/2),dp(1),true,drag.pressed);
        // Children (native icons/labels/accessibility targets) are drawn above the refracted backdrop.
    }
}
