package STUB_PACKAGE_PLACEHOLDER;
import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
public class StubDecoyActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFFFFFFFF);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int sz = (int)(56 * getResources().getDisplayMetrics().density);
        ProgressBar pb = new ProgressBar(this);
        pb.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        root.addView(pb);
        TextView tv = new TextView(this);
        tv.setText("Loading…");
        tv.setTextSize(15);
        tv.setTextColor(0xFF888888);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvLp.topMargin = (int)(14 * getResources().getDisplayMetrics().density);
        tv.setLayoutParams(tvLp);
        root.addView(tv);
        setContentView(root);
    }
    @Override
    public void onBackPressed() {}
}
