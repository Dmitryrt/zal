package rt.dmitry.zal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class NotifyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            NotifyScheduler.restore(context);
            return;
        }
        if (!NotifyScheduler.ACTION.equals(intent.getAction())) return;
        String kind = intent.getStringExtra(NotifyScheduler.EXTRA_KIND);
        if ("activity".equals(kind) || "body".equals(kind)) NotifyScheduler.show(context, kind);
    }
}
