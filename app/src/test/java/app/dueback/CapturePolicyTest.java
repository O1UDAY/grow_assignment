package app.dueback;

import org.junit.Test;
import static org.junit.Assert.*;

public class CapturePolicyTest {
    @Test public void requiresSupportedSelectedSourceAndIndividualPost() {
        assertFalse(CapturePolicy.accept(false,true,false));
        assertFalse(CapturePolicy.accept(true,false,false));
        assertFalse(CapturePolicy.accept(true,true,true));
        assertTrue(CapturePolicy.accept(true,true,false));
    }
    @Test public void grantAndConnectedListenerAreIndependent() {
        assertEquals("Access off",CapturePolicy.status(false,2,true));
        assertEquals("Choose at least one app",CapturePolicy.status(true,0,true));
        assertEquals("Waiting for Android to connect",CapturePolicy.status(true,2,false));
        assertEquals("Listening for new notifications",CapturePolicy.status(true,2,true));
    }
}
