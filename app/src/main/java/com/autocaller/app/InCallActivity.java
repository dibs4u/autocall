package com.autocaller.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.PhoneAccountHandle;
import android.telecom.VideoProfile;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The call screen for ordinary calls (incoming, or dialled by hand). It is needed because
 * the default phone app has to provide the screen for every call. Calls made by a calling
 * run do not use it; they are shown on the home screen instead.
 */
public class InCallActivity extends Activity implements CallService.Listener {

    private static final int DARK = 0xFF0E1F19;
    private static final int GLASS = 0x33FFFFFF;

    private TextView stateView;
    private TextView nameView;
    private TextView numberView;
    private TextView timerView;
    private LinearLayout ringRow;
    private LinearLayout controls;
    private LinearLayout keypad;
    private Button endBtn;
    private Button muteBtn;
    private Button speakerBtn;
    private Button holdBtn;
    private Button keypadBtn;
    private boolean simDialogShown;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(DARK);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Ui.dp(this, 24);
        root.setPadding(p, Ui.dp(this, 56), p, Ui.dp(this, 32));

        stateView = Ui.text(this, "", 15, 0xFF9FD9C3, true);
        stateView.setGravity(Gravity.CENTER);
        root.addView(stateView, Ui.lp(Ui.MATCH, Ui.WRAP));

        nameView = Ui.text(this, "", 30, Ui.WHITE, true);
        nameView.setGravity(Gravity.CENTER);
        root.addView(nameView, Ui.below(this, 14));

        numberView = Ui.text(this, "", 17, 0xFFB8C7C1, false);
        numberView.setGravity(Gravity.CENTER);
        root.addView(numberView, Ui.below(this, 6));

        timerView = Ui.text(this, "", 20, Ui.WHITE, false);
        timerView.setGravity(Gravity.CENTER);
        root.addView(timerView, Ui.below(this, 18));

        root.addView(new View(this), new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        keypad = buildKeypad();
        keypad.setVisibility(View.GONE);
        root.addView(keypad, Ui.below(this, 8));

        controls = Ui.row(this);
        muteBtn = Ui.button(this, "Mute", GLASS, Ui.WHITE);
        keypadBtn = Ui.button(this, "Keypad", GLASS, Ui.WHITE);
        speakerBtn = Ui.button(this, "Speaker", GLASS, Ui.WHITE);
        holdBtn = Ui.button(this, "Hold", GLASS, Ui.WHITE);
        controls.addView(muteBtn, Ui.share(this, 0));
        controls.addView(keypadBtn, Ui.share(this, 8));
        controls.addView(speakerBtn, Ui.share(this, 8));
        controls.addView(holdBtn, Ui.share(this, 8));
        root.addView(controls, Ui.below(this, 16));

        endBtn = Ui.danger(this, "End call");
        tall(endBtn);
        root.addView(endBtn, Ui.below(this, 16));

        ringRow = Ui.row(this);
        Button decline = Ui.danger(this, "Decline");
        Button answer = Ui.button(this, "Answer", 0xFF1B9E5A, Ui.WHITE);
        tall(decline);
        tall(answer);
        ringRow.addView(decline, Ui.share(this, 0));
        ringRow.addView(answer, Ui.share(this, 12));
        root.addView(ringRow, Ui.below(this, 16));

        setContentView(root);

        muteBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CallService s = CallService.instance;
                if (s == null) return;
                try {
                    CallAudioState st = s.getCallAudioState();
                    s.setMuted(st == null || !st.isMuted());
                } catch (Exception e) {
                    Logx.err("mute", e);
                }
            }
        });
        speakerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CallService s = CallService.instance;
                if (s == null) return;
                try {
                    CallAudioState st = s.getCallAudioState();
                    boolean on = st != null && st.getRoute() == CallAudioState.ROUTE_SPEAKER;
                    s.setAudioRoute(on ? CallAudioState.ROUTE_WIRED_OR_EARPIECE
                            : CallAudioState.ROUTE_SPEAKER);
                } catch (Exception e) {
                    Logx.err("speaker", e);
                }
            }
        });
        holdBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Call c = CallService.primary();
                if (c == null) return;
                try {
                    if (CallService.stateOf(c) == Call.STATE_HOLDING) c.unhold();
                    else c.hold();
                } catch (Exception e) {
                    Logx.err("hold", e);
                }
            }
        });
        keypadBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                keypad.setVisibility(keypad.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                refresh();
            }
        });
        endBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Call c = CallService.primary();
                try {
                    if (c != null) c.disconnect();
                } catch (Exception e) {
                    Logx.err("end call", e);
                }
            }
        });
        answer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Call c = CallService.primary();
                try {
                    if (c != null) c.answer(VideoProfile.STATE_AUDIO_ONLY);
                } catch (Exception e) {
                    Logx.err("answer", e);
                }
            }
        });
        decline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Call c = CallService.primary();
                try {
                    if (c != null) c.reject(false, null);
                } catch (Exception e) {
                    Logx.err("decline", e);
                }
            }
        });
    }

    private void tall(Button b) {
        b.setMinHeight(Ui.dp(this, 56));
        b.setMinimumHeight(Ui.dp(this, 56));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
    }

    private LinearLayout buildKeypad() {
        LinearLayout grid = Ui.col(this);
        String keys = "123456789*0#";
        for (int r = 0; r < 4; r++) {
            LinearLayout row = Ui.row(this);
            for (int k = 0; k < 3; k++) {
                final char key = keys.charAt(r * 3 + k);
                Button b = Ui.button(this, String.valueOf(key), GLASS, Ui.WHITE);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sendTone(key);
                    }
                });
                row.addView(b, Ui.share(this, k == 0 ? 0 : 8));
            }
            grid.addView(row, Ui.below(this, r == 0 ? 0 : 8));
        }
        return grid;
    }

    private void sendTone(char key) {
        final Call c = CallService.primary();
        if (c == null) return;
        try {
            c.playDtmfTone(key);
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        c.stopDtmfTone();
                    } catch (Exception ignored) {
                    }
                }
            }, 200);
        } catch (Exception e) {
            Logx.err("dtmf", e);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        CallService.listener = this;
        h.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (CallService.listener == this) CallService.listener = null;
        h.removeCallbacks(tick);
    }

    @Override
    public void onCallsChanged() {
        refresh();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    private void toggle(Button b, boolean on) {
        Object prev = b.getTag();
        if (prev instanceof Boolean && ((Boolean) prev) == on) return;
        b.setTag(on);
        b.setBackground(Ui.pressable(this, on ? Ui.WHITE : GLASS, 10));
        b.setTextColor(on ? DARK : Ui.WHITE);
    }

    private void refresh() {
        Call c = CallService.primary();
        if (c == null) {
            if (!isFinishing()) finishAndRemoveTask();
            return;
        }
        int st = CallService.stateOf(c);
        boolean ringing = st == Call.STATE_RINGING;

        String name = CallService.displayName(c);
        String number = CallService.numberOf(c);
        nameView.setText(name);
        numberView.setText(number.equals(name) ? "" : number);

        String state;
        switch (st) {
            case Call.STATE_RINGING:
                state = "Incoming call";
                break;
            case Call.STATE_DIALING:
            case Call.STATE_CONNECTING:
                state = "Calling…";
                break;
            case Call.STATE_ACTIVE:
                state = "On call";
                break;
            case Call.STATE_HOLDING:
                state = "On hold";
                break;
            case Call.STATE_DISCONNECTED:
            case Call.STATE_DISCONNECTING:
                state = "Call ended";
                break;
            case Call.STATE_SELECT_PHONE_ACCOUNT:
                state = "Choose a SIM";
                break;
            default:
                state = "";
                break;
        }
        stateView.setText(state);

        long connected = 0;
        try {
            connected = c.getDetails().getConnectTimeMillis();
        } catch (Exception ignored) {
        }
        boolean talking = st == Call.STATE_ACTIVE || st == Call.STATE_HOLDING;
        timerView.setText(talking && connected > 0
                ? Util.dur(System.currentTimeMillis() - connected) : "");

        ringRow.setVisibility(ringing ? View.VISIBLE : View.GONE);
        controls.setVisibility(ringing ? View.GONE : View.VISIBLE);
        endBtn.setVisibility(ringing ? View.GONE : View.VISIBLE);
        if (ringing) keypad.setVisibility(View.GONE);

        CallAudioState audio = null;
        try {
            CallService s = CallService.instance;
            audio = s == null ? null : s.getCallAudioState();
        } catch (Exception ignored) {
        }
        toggle(muteBtn, audio != null && audio.isMuted());
        toggle(speakerBtn, audio != null && audio.getRoute() == CallAudioState.ROUTE_SPEAKER);
        toggle(holdBtn, st == Call.STATE_HOLDING);
        toggle(keypadBtn, keypad.getVisibility() == View.VISIBLE);
        holdBtn.setText(st == Call.STATE_HOLDING ? "Resume" : "Hold");

        if (st == Call.STATE_SELECT_PHONE_ACCOUNT && !simDialogShown) chooseSim(c);
    }

    /** The phone is set to "ask every time": let the user pick the SIM for this call. */
    private void chooseSim(final Call c) {
        simDialogShown = true;
        final List<PhoneAccountHandle> sims = Util.sims(this);
        try {
            if (sims.isEmpty()) {
                Ui.toast(this, "Allow the Phone permission for AutoCaller, then call again.");
                c.disconnect();
                return;
            }
            if (sims.size() == 1) {
                c.phoneAccountSelected(sims.get(0), false);
                return;
            }
            String[] labels = new String[sims.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = Util.simLabel(this, sims.get(i), i);
            new AlertDialog.Builder(this)
                    .setTitle("Call with")
                    .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int which) {
                            try {
                                c.phoneAccountSelected(sims.get(which), false);
                            } catch (Exception e) {
                                Logx.err("choose sim", e);
                            }
                        }
                    })
                    .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(android.content.DialogInterface d) {
                            try {
                                c.disconnect();
                            } catch (Exception ignored) {
                            }
                        }
                    })
                    .show();
        } catch (Exception e) {
            Logx.err("chooseSim", e);
        }
    }
}
