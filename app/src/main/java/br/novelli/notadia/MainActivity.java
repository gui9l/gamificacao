package br.novelli.notadia;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private Tela tela;

    private void erro(Throwable e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        TextView t = new TextView(this);
        t.setText("Erro ao abrir (mande um print):\n" + sw);
        t.setTextSize(11);
        t.setPadding(30, 100, 30, 30);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        setContentView(sv);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            tela = new Tela(this);
            tela.montar();
        } catch (Throwable e) {
            tela = null;
            erro(e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try { if (tela != null) tela.atualizarStatus(); } catch (Throwable e) { }
    }
}
