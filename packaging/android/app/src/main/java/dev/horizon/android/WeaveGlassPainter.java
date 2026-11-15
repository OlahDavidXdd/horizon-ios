package dev.horizon.android;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import java.io.*;

/** Native View adapter for WeaveMask's GPL-3.0-or-later lens shader; see assets/weave-glass. */
final class WeaveGlassPainter {
    private final Paint base=new Paint(Paint.ANTI_ALIAS_FLAG),rim=new Paint(Paint.ANTI_ALIAS_FLAG);
    private NativeLens lens;
    private boolean reduced;
    WeaveGlassPainter(Context context) {
        rim.setStyle(Paint.Style.STROKE);
        if(Build.VERSION.SDK_INT>=33) {
            try(InputStream in=context.getAssets().open("weave-glass/lens.agsl")) {
                lens=new NativeLens(new String(HorizonActivity.readBytes(in),java.nio.charset.StandardCharsets.UTF_8));
            } catch(IOException | RuntimeException unavailable) { lens=null; }
        }
    }
    void backdrop(Bitmap bitmap) { if(lens!=null) lens.backdrop(bitmap); }
    void reduced(boolean reduced) { this.reduced=reduced; }
    String backend() { return !reduced && lens!=null ? "WeaveMask native AGSL" : "native glass highlight"; }
    void draw(Canvas canvas,RectF bounds,float density,boolean selected,boolean held) {
        float radius=bounds.height()/2;
        base.setShader(null);base.setColor(reduced ? 0xff263b48 : selected ? 0x304bc9b2 : 0x60323e4e);
        canvas.drawRoundRect(bounds,radius,radius,base);
        if(!reduced && lens!=null && canvas.isHardwareAccelerated()) {
            try { lens.draw(canvas,bounds,density,selected); }
            catch(RuntimeException unavailable) { lens=null; }
        }
        base.setShader(new LinearGradient(bounds.left,bounds.top,bounds.right,bounds.bottom,
            selected ? new int[]{0x7078e8d2,0x2258aeb7,0x3a9bf5df} : new int[]{0x305ea8b3,0x0c95a3ba,0x282e6b7e},null,Shader.TileMode.CLAMP));
        base.setColor(Color.WHITE);canvas.drawRoundRect(bounds,radius,radius,base);
        rim.setStrokeWidth(density*(held?1.5f:1));
        rim.setShader(new LinearGradient(bounds.left,bounds.top,bounds.right,bounds.bottom,
            new int[]{selected?0xd0e7fff9:0x8acfe6ed,0x1871bbc0,selected?0x908fe7ce:0x6580b3c4},null,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bounds,radius,radius,rim);
    }
    @android.annotation.TargetApi(33)
    private static final class NativeLens {
        private final RuntimeShader bar,lens,content;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean ready;
        private static final String CONTENT="""
            uniform shader backdrop;
            uniform float blur;
            half4 main(float2 p) {
                half4 c=backdrop.eval(p)*0.2;
                c+=(backdrop.eval(p+float2(blur,0))+backdrop.eval(p-float2(blur,0))
                   +backdrop.eval(p+float2(0,blur))+backdrop.eval(p-float2(0,blur)))*0.12;
                c+=(backdrop.eval(p+float2(blur,blur))+backdrop.eval(p-float2(blur,blur))
                   +backdrop.eval(p+float2(blur,-blur))+backdrop.eval(p-float2(blur,-blur)))*0.08;
                half l=dot(c.rgb,half3(0.2126,0.7152,0.0722));
                c.rgb=clamp(mix(half3(l),c.rgb,1.5)*1.08+half3(0.025),0.0,1.0);
                return c;
            }
            """;
        NativeLens(String shader) { bar=new RuntimeShader(shader);lens=new RuntimeShader(shader);content=new RuntimeShader(CONTENT); }
        void backdrop(Bitmap bitmap) {
            content.setInputShader("backdrop",new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));
            bar.setInputShader("content",content);lens.setInputShader("content",content);ready=true;
        }
        void draw(Canvas canvas,RectF bounds,float density,boolean selected) {
            if(!ready) return;
            content.setFloatUniform("blur",density*(selected?2:5));
            RuntimeShader shader=selected?lens:bar;
            // Rebind after a child shader's uniforms change so both draw snapshots are current.
            shader.setInputShader("content",content);
            shader.setFloatUniform("size",bounds.width(),bounds.height());
            shader.setFloatUniform("offset",-bounds.left,-bounds.top);
            float radius=bounds.height()/2;
            shader.setFloatUniform("cornerRadii",radius,radius,radius,radius);
            shader.setFloatUniform("refractionHeight",Math.min(radius,density*14));
            shader.setFloatUniform("refractionAmount",-density*(selected?10:5));
            shader.setFloatUniform("depthEffect",0f);
            paint.setShader(shader);canvas.drawRoundRect(bounds,radius,radius,paint);
        }
    }
}
