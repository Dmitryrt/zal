package rt.dmitry.zal;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.TextView;

public class HealthRationaleActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(Color.BLACK);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        text.setPadding(pad, pad * 2, pad, pad);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        text.setText("«Зал» читает из Health Connect шаги, дистанцию, активные калории и тренировки, чтобы заполнить журнал. Цифры остаются в журнале на этом телефоне.");
        setContentView(text);
    }
}
