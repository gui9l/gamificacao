package br.novelli.notadia;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Anel de progresso do dia (igual ao da Nota do computador). */
public class Anel extends View {
    public float progresso = 0f;   // 0..1+
    public int corAnel = 0xFFC9962F;
    public String grande = "—";
    public String pequeno = "";

    private final Paint trilha = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arco = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Anel(Context c) {
        super(c);
        trilha.setStyle(Paint.Style.STROKE);
        trilha.setColor(0xFFE6DAC0);
        arco.setStyle(Paint.Style.STROKE);
        arco.setStrokeCap(Paint.Cap.ROUND);
        tg.setColor(0xFF2E1A0E);
        tg.setTextAlign(Paint.Align.CENTER);
        tg.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setColor(0xFF8B6F55);
        tp.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onDraw(Canvas g) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight();
        float lado = Math.min(w, h) - 24 * d;
        float esp = 14 * d;
        float cx = w / 2f, cy = h / 2f;
        RectF r = new RectF(cx - lado / 2f + esp / 2f, cy - lado / 2f + esp / 2f, cx + lado / 2f - esp / 2f, cy + lado / 2f - esp / 2f);
        trilha.setStrokeWidth(esp);
        arco.setStrokeWidth(esp);
        arco.setColor(corAnel);
        g.drawArc(r, 0, 360, false, trilha);
        float p = Math.max(0f, Math.min(1f, progresso));
        if (p > 0f) g.drawArc(r, -90, 360f * p, false, arco);
        tg.setTextSize(34 * d);
        tp.setTextSize(13 * d);
        g.drawText(grande, cx, cy + 6 * d, tg);
        g.drawText(pequeno, cx, cy + 28 * d, tp);
    }
}
