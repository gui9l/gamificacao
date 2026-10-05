package br.novelli.notadia;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setText("Teste 1: o app abre.");
        t.setTextSize(20);
        t.setPadding(40, 120, 40, 40);
        setContentView(t);
    }
}
