package com.ayuu.instapong;
import android.os.SystemClock;
import java.util.function.Consumer;
public final class BotController {
 private BotController(){} private static Consumer<String> uiCallback; private static long lastMoveMs=0; private static float lastTargetX=-1;
 public static synchronized void setUiCallback(Consumer<String> cb){uiCallback=cb;}
 public static synchronized void status(String text){if(uiCallback!=null)uiCallback.accept(text);}
 public static synchronized boolean shouldMove(float targetX,float width){long now=SystemClock.uptimeMillis();float minDelta=Math.max(18f,width*.035f);if(lastTargetX<0||Math.abs(targetX-lastTargetX)>minDelta||now-lastMoveMs>180){lastTargetX=targetX;lastMoveMs=now;return true;}return false;}
}
