package com.snapclean.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EditPlanTest {
    @Test public void validatesTrimBounds() {
        assertEquals(2000,EditPlan.clip(1000,3000,10000).lengthMs());
        try {EditPlan.clip(1000,1100,2000);fail("Expected invalid selection");}
        catch(IllegalArgumentException expected) { /* expected */ }
    }
    @Test public void cropCoordinatesAreEvenAndBounded() {
        EditPlan.Crop c=EditPlan.crop(.1f,.15f,.8f,.7f,1920,1080);
        assertEquals(0,c.x%2);assertEquals(0,c.width%2);
        assertTrue(c.x+c.width<=1920);assertTrue(c.y+c.height<=1080);
    }
    @Test public void ffmpegSeparatesArgumentsAndRetainsAudio() {
        java.util.List<String> cmd=EditPlan.ffmpeg("/ffmpeg","/tmp/a b.mp4","/tmp/out.mp4",
                EditPlan.clip(0,2000,5000),null,false,false);
        assertTrue(cmd.contains("/tmp/a b.mp4"));assertTrue(cmd.contains("0:a:0?"));
        assertTrue(cmd.contains("libx264"));
    }
}
