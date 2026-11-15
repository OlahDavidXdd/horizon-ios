package dev.horizon.android;

public final class GlassDragStateTest {
    static void near(float actual,float expected) {
        if(Math.abs(actual-expected)>0.01f) throw new AssertionError(actual+" != "+expected);
    }
    public static void main(String[] args) {
        GlassDragState drag=new GlassDragState(3); drag.layout(310,5);
        drag.select(1); drag.begin(155); drag.move(192); near(drag.position,192);
        if(drag.selected!=1) throw new AssertionError("Committed before release");
        drag.move(240); if(drag.end(true)!=2) throw new AssertionError("Wrong destination");
        drag.begin(255); drag.move(55); if(drag.end(false)!=2) throw new AssertionError("Cancel changed tab");
        drag.begin(55); drag.move(100); drag.begin(155); drag.move(173); near(drag.position,173);
        drag.move(-1000); near(drag.position,55); drag.move(2000); near(drag.position,255);
        drag.end(true); drag.layout(610,5); near(drag.position,505);
        if(drag.pressed) throw new AssertionError("Resize retained stale drag");
        drag.select(0); near(drag.position,105);
        System.out.println("Glass selector: finger tracking, commit, cancellation, stale gesture and resize passed");
    }
}
