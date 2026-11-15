import com.autocaller.player.Detector;
import java.util.*;

public class DetectorTest {
    static Random r = new Random(1);
    static double ph;

    // build a signal as a list of segments; returns short[] samples
    static short[] tone(double f, double sec, double amp) {
        int n = (int)(sec*16000); short[] o = new short[n];
        for (int i=0;i<n;i++) o[i]=(short)(amp*Math.sin(2*Math.PI*f*i/16000)+r.nextGaussian()*40);
        return o;
    }
    static short[] noise(double sec, double amp) {
        int n=(int)(sec*16000); short[] o=new short[n];
        for(int i=0;i<n;i++) o[i]=(short)(r.nextGaussian()*amp);
        return o;
    }
    // crude voice: glottal pulse train (F0 jitter) through 3 formant resonators, syllable AM, plus aspiration noise
    static short[] voice(double sec, double f0, double amp, double[][] formants) {
        int n=(int)(sec*16000); double[] x=new double[n];
        double phase=0;
        for(int i=0;i<n;i++){
            double f=f0*(1+0.05*Math.sin(2*Math.PI*3*i/16000.0));
            phase+=f/16000; double e=0;
            if(phase>=1){phase-=1;e=1;}
            x[i]=e*50+r.nextGaussian()*0.3;
        }
        double[] y=new double[n];
        for(double[] fm:formants){
            double fr=fm[0], bw=fm[1];
            double R=Math.exp(-Math.PI*bw/16000), th=2*Math.PI*fr/16000;
            double a1=2*R*Math.cos(th), a2=-R*R; double y1=0,y2=0;
            for(int i=0;i<n;i++){double v=x[i]+a1*y1+a2*y2; y2=y1;y1=v; y[i]+=v*(1-0.25*(fr/3000));}
        }
        double mx=0; for(double v:y) mx=Math.max(mx,Math.abs(v));
        short[] o=new short[n];
        for(int i=0;i<n;i++){
            double env=0.5+0.5*Math.sin(2*Math.PI*4*i/16000.0-1.2); // ~4 syllables/s
            env=Math.max(0.15,env);
            o[i]=(short)(y[i]/mx*amp*env + r.nextGaussian()*30);
        }
        return o;
    }
    static short[] cat(short[]... a){int n=0;for(short[] s:a)n+=s.length;short[] o=new short[n];int p=0;for(short[] s:a){System.arraycopy(s,0,o,p,s.length);p+=s.length;}return o;}

    static String run(String name, short[] sig, boolean needRing) {
        Detector d=new Detector(); d.requireRingback=needRing; d.reset();
        short[] f=new short[Detector.FRAME];
        StringBuilder sb=new StringBuilder();
        for(int i=0;i+Detector.FRAME<=sig.length;i+=Detector.FRAME){
            System.arraycopy(sig,i,f,0,Detector.FRAME);
            int e=d.process(f,Detector.FRAME);
            if(e!=0) sb.append(e==1?"RING@":"TRIGGER@").append((i/16)/1000.0).append("s ");
        }
        return String.format("%-44s ring=%b -> %s", name, needRing, sb.length()==0?"(nothing)":sb);
    }
    public static void main(String[] a){
        double[][] ah={{700,90},{1200,110},{2600,160}};
        double[][] ee={{300,70},{2300,120},{3000,160}};
        double[][] oh={{500,80},{900,100},{2500,150}};
        short[] hello = cat(voice(0.18,115,9000,ee), voice(0.12,118,9000,ah), voice(0.35,112,9000,oh));
        short[] hello_f = cat(voice(0.18,210,9000,ee), voice(0.12,215,9000,ah), voice(0.35,205,9000,oh));
        // Indian-style double ring 0.4 on 0.2 off 0.4 on 2.0 off, 400 Hz
        short[] ring = cat(tone(400,0.4,5000), noise(0.2,40), tone(400,0.4,5000), noise(2.0,40));
        short[] ring425 = cat(tone(425,1.0,5000), noise(4.0,40));
        short[] busy = cat(tone(400,0.75,5000), noise(0.75,40), tone(400,0.75,5000), noise(0.75,40));
        short[] nl = noise(1.5, 40);

        System.out.println(run("ring x3 then 'hello' (male)", cat(nl,ring,ring,ring,noise(0.6,60),hello,noise(3,60)), true));
        System.out.println(run("ring x3 then 'hello' (female)", cat(nl,ring,ring,ring,noise(0.6,60),hello_f,noise(3,60)), true));
        System.out.println(run("425Hz ring x2 then 'hello'", cat(nl,ring425,ring425,noise(0.3,60),hello,noise(3,60)), true));
        System.out.println(run("quiet far 'hello' (amp 1500)", cat(nl,ring,ring,noise(0.5,40),cat(voice(0.6,115,1500,ee),voice(0.4,115,1500,oh)),noise(3,40)), true));
        System.out.println(run("'not reachable' voice, no ringback", cat(nl,voice(3,130,8000,ah),noise(2,60)), true));
        System.out.println(run("same, ringback not required", cat(nl,voice(3,130,8000,ah),noise(2,60)), false));
        System.out.println(run("busy tone only", cat(nl,busy,busy,noise(2,40)), true));
        System.out.println(run("only room noise 20 s", noise(20,120), true));
        System.out.println(run("short cough/click 120 ms after ring", cat(nl,ring,ring,voice(0.12,115,9000,ah),noise(3,40)), true));
        System.out.println(run("ring then voice after 120 s (too late)", cat(nl,ring,noise(100,40),hello,noise(2,40)), true));
        for (double na : new double[]{150, 400, 800}) {
            short[] clean = cat(nl, ring, ring, noise(0.6,40), hello, noise(2,40));
            short[] mix = new short[clean.length];
            for (int i=0;i<mix.length;i++) mix[i]=(short)(clean[i]+r.nextGaussian()*na);
            System.out.println(run("room noise sigma "+(int)na+" + ring + hello", mix, true));
        }
    }
}
