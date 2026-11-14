package com.autocaller.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * A very small dial pad. Android only lets an app become the default phone app if it can
 * handle "dial this number" requests, and this is the screen that does it.
 */
public class DialerActivity extends Activity {

    private static final int REQ_PERMISSION = 51;

    private EditText number;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.Screen s = Ui.screen(this, "Dial a number", true, true);

        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Phone number", 13, Ui.MUTED, false));
        number = new EditText(this);
        number.setInputType(InputType.TYPE_CLASS_PHONE);
        number.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        number.setSingleLine(true);
        card.addView(number, Ui.below(this, 4));
        Button call = Ui.primary(this, "Call");
        call.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                placeCall();
            }
        });
        card.addView(call, Ui.below(this, 12));
        s.body.addView(card);

        s.body.addView(Ui.text(this, "Use this to make an ordinary call while AutoCaller is the"
                + " default phone app. Your usual phone app can still be opened from the home"
                + " screen.", 13, Ui.MUTED, false));

        fill(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        fill(intent);
    }

    private void fill(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data != null && "tel".equals(data.getScheme())) {
            String n = data.getSchemeSpecificPart();
            if (n != null) {
                number.setText(n);
                number.setSelection(number.getText().length());
            }
        }
    }

    private void placeCall() {
        String n = number.getText().toString().trim();
        if (n.length() == 0) return;
        if (!Util.has(this, Util.P_CALL)) {
            requestPermissions(new String[]{Util.P_CALL}, REQ_PERMISSION);
            return;
        }
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            tm.placeCall(Uri.fromParts("tel", n, null), new Bundle());
        } catch (Exception e) {
            Logx.err("manual call", e);
            Ui.toast(this, "Could not place the call: " + e.getMessage());
        }
    }
}
