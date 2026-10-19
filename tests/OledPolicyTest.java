package cn.weeazn.bilifans;

public final class OledPolicyTest {
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        check(OledPolicy.active(true,true,true,false), "foreground dashboard active");
        check(!OledPolicy.active(false,true,true,false), "disabled");
        check(!OledPolicy.active(true,false,true,false), "background");
        check(!OledPolicy.active(true,true,false,false), "focus lost");
        check(!OledPolicy.active(true,true,true,true), "settings pause");
        check(Math.abs(OledPolicy.brightness(25,.15f,true,true)-.2125f)<.00001, "dim from baseline");
        check(OledPolicy.brightness(25,.15f,false,true)==.25f, "restore baseline");
        check(OledPolicy.brightness(25,.15f,true,false)==-1f, "release override in background");
        check(OledPolicy.brightness(1,.6f,true,true)==.01f, "never unusably dark");
        check(OledPolicy.dim(Double.NaN)==.15f && OledPolicy.dim(100)==.6f, "validate bridge values");
        check(OledPolicy.interval(0)==10 && OledPolicy.interval(Integer.MAX_VALUE)==3600, "bound scheduling");
        for (int[] p : OledPolicy.POSITIONS) for (int v : p)
            check(Math.abs(v)<=OledPolicy.SHIFT_DP, "within reserved safe margin");
        System.out.println("PASS native OLED brightness restoration / lifecycle policy / safe bounds");
    }
}
