package cn.weeazn.bilifans;

public class MediaRestPolicyTest {
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);}
    static void rejected(boolean video,long bytes,long duration){try{MediaRestPolicy.validate(video,bytes,duration);throw new AssertionError("must reject");}catch(IllegalArgumentException expected){}}
    public static void main(String[] args){
        check(MediaRestPolicy.interval(359)==360,"minimum display");check(MediaRestPolicy.interval(7201)==7200,"maximum display");
        MediaRestPolicy.validate(false,30L*1024*1024,0);rejected(false,30L*1024*1024+1,0);
        MediaRestPolicy.validate(true,200L*1024*1024,60000);rejected(true,200L*1024*1024+1,60000);rejected(true,100,60001);rejected(true,100,0);
        MediaRestPolicy.Clock clock=new MediaRestPolicy.Clock();clock.running(true,0);
        check(clock.remaining(360,359999)==1,"not early");check(clock.remaining(360,360000)==0,"due");
        clock.reset(0);clock.running(false,100000);check(clock.remaining(360,999999)==260000,"background does not count");
        clock.running(true,1000000);check(clock.remaining(360,1259999)==1,"resume remaining");
        clock.reset(1260000);check(clock.remaining(360,1260000)==360000,"new cycle after playback");
        for(int[] dims:new int[][]{{1920,1080,1080,2400},{1080,1920,2400,1080},{100,100,393,851}}){
            float[] s=MediaRestPolicy.cover(dims[0],dims[1],dims[2],dims[3]);
            check(s[0]>=.999f && s[1]>=.999f,"no black bars");
            check(Math.abs((s[0]*dims[2])/(s[1]*dims[3])-(float)dims[0]/dims[1])<.001,"preserve aspect ratio");
        }
        System.out.println("PASS media limits / foreground-only clock / full-screen crop geometry");
    }
}
