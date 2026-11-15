package com.autocaller.player;

/**
 * Listens to 16 kHz mono PCM (40 ms frames) coming from the microphone while the
 * other phone's loudspeaker plays the call. Decides:
 *   - RINGBACK : a steady narrow-band tone was heard (the "tring... tring" of a ringing call)
 *   - TRIGGER  : a human voice started (after a ringback if requireRingback is on)
 * Pure Java, no Android classes, so it can be tested on a PC.
 */
public class Detector {
    public static final int RATE = 16000;
    public static final int FRAME = 640;          // 40 ms
    public static final int FRAME_MS = 40;

    public static final int NONE = 0, RINGBACK = 1, TRIGGER = 2;

    // ---- settings (change before or between frames) ----
    public volatile double marginDb = 12;         // voice must be this far above the noise floor
    public volatile int minVoiceMs = 280;         // how long a voice must last
    public volatile boolean requireRingback = true;
    public volatile int ringbackMaxAgeMs = 90000; // voice only counts within this time after a ringback
    public volatile int ignoreStartMs = 1500;     // ignore the first moments after Start

    private static final double ABS_MIN_DB = -55; // never treat anything quieter than this as voice
    private static final double TONE_RATIO = 0.65;
    private static final int TONE_MIN_FRAMES = 6; // 240 ms of steady tone = ringback
    private static final int GAP_FRAMES = 3;      // a voice may pause 120 ms between syllables

    // Goertzel bins every 25 Hz from 300 to 550 Hz (exact bins for 640 samples at 16 kHz)
    private static final int BIN0 = 12, BINS = 11; // 12*25 = 300 Hz
    private static final double[] COEFF = new double[BINS];
    static {
        for (int i = 0; i < BINS; i++) {
            double k = BIN0 + i;
            COEFF[i] = 2 * Math.cos(2 * Math.PI * k / FRAME);
        }
    }

    // ---- live values for the screen ----
    public volatile double levelDb = -90, floorDb = -60, tonality = 0;
    public volatile boolean tonal, voiced;
    public volatile int voiceMs;

    private long frameNo;
    private long lastRingbackFrame = -1;
    private int toneRun, voiceRun, gap;
    private boolean floorInit;

    public void reset() {
        frameNo = 0; lastRingbackFrame = -1; toneRun = 0; voiceRun = 0; gap = 0; voiceMs = 0;
        floorInit = false;
    }

    /** Forget the ringback and any voice in progress (used between calls). */
    public void rearm() {
        lastRingbackFrame = -1; toneRun = 0; voiceRun = 0; gap = 0; voiceMs = 0;
    }

    public boolean ringbackSeen() { return lastRingbackFrame >= 0; }

    public int process(short[] x, int n) {
        frameNo++;
        if (n < FRAME) return NONE;

        // mean power
        double sum = 0, mean = 0;
        for (int i = 0; i < FRAME; i++) mean += x[i];
        mean /= FRAME;
        double[] s = new double[FRAME];
        for (int i = 0; i < FRAME; i++) {
            s[i] = (x[i] - mean) / 32768.0;
            sum += s[i] * s[i];
        }
        double db = 10 * Math.log10(sum / FRAME + 1e-12);
        levelDb = db;

        // noise floor: falls fast, rises slowly, and not at all during loud sound
        if (!floorInit) { floorDb = db; floorInit = true; }
        else if (db < floorDb) floorDb = floorDb * 0.5 + db * 0.5;
        else if (db < floorDb + 10) floorDb += 0.02 * (db - floorDb);
        else floorDb += 0.0003 * (db - floorDb);

        // tonality: share of the energy sitting in one narrow band around 300-550 Hz
        double[] p = new double[BINS];
        for (int b = 0; b < BINS; b++) {
            double q1 = 0, q2 = 0, c = COEFF[b];
            for (int i = 0; i < FRAME; i++) {
                double q0 = c * q1 - q2 + s[i];
                q2 = q1; q1 = q0;
            }
            p[b] = (q1 * q1 + q2 * q2 - c * q1 * q2) * 2.0 / ((double) FRAME * (sum + 1e-12));
        }
        double best = 0;
        for (int b = 0; b < BINS; b++) {
            double t = p[b] + (b > 0 ? p[b - 1] : 0) + (b < BINS - 1 ? p[b + 1] : 0);
            if (t > best) best = t;
        }
        tonality = best;
        boolean loud = db > ABS_MIN_DB && db > floorDb + 6;
        tonal = loud && best > TONE_RATIO;

        int result = NONE;

        if (tonal) {
            toneRun++;
            if (toneRun >= TONE_MIN_FRAMES) {
                if (lastRingbackFrame < 0) result = RINGBACK;
                lastRingbackFrame = frameNo;
            }
            voiced = false;
            voiceRun = 0; gap = 0; voiceMs = 0;
            return result;
        }
        toneRun = 0;

        voiced = db > ABS_MIN_DB && db > floorDb + marginDb;
        if (voiced) {
            voiceRun++; gap = 0;
        } else if (voiceRun > 0) {
            gap++;
            if (gap > GAP_FRAMES) { voiceRun = 0; gap = 0; }
        }
        voiceMs = voiceRun * FRAME_MS;

        if (voiceRun * FRAME_MS >= minVoiceMs) {
            boolean started = frameNo * FRAME_MS >= ignoreStartMs;
            boolean armed = !requireRingback
                    || (lastRingbackFrame >= 0
                        && (frameNo - lastRingbackFrame) * FRAME_MS <= ringbackMaxAgeMs);
            voiceRun = 0; gap = 0; voiceMs = 0;
            if (started && armed) return TRIGGER;
        }
        return result;
    }
}
