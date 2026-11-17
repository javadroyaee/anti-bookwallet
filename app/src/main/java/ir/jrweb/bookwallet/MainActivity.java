package ir.jrweb.bookwallet;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String SITE_URL = "https://jrweb.ir";
    private static final String PAY_API = SITE_URL + "/wp-json/bookwallet/v1/pay";
    private static final String VERIFY_API = SITE_URL + "/wp-json/bookwallet/v1/verify";
    private static final String PREFS_NAME = "BookWalletPrefs";

    private LinearLayout layoutActivation, layoutScanner;
    private TextView tvStatusBadge, tvWelcomeUser, tvScanStatus, tvScanLogs;
    private EditText etFullName, etPhone;
    private Button btnPayActivate, btnCheckStatus, btnStartScan;
    private ProgressBar progressScan;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean waitingForPaymentReturn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        layoutActivation = findViewById(R.id.layoutActivation);
        layoutScanner = findViewById(R.id.layoutScanner);
        tvStatusBadge = findViewById(R.id.tvStatusBadge);
        tvWelcomeUser = findViewById(R.id.tvWelcomeUser);
        tvScanStatus = findViewById(R.id.tvScanStatus);
        tvScanLogs = findViewById(R.id.tvScanLogs);
        etFullName = findViewById(R.id.etFullName);
        etPhone = findViewById(R.id.etPhone);
        btnPayActivate = findViewById(R.id.btnPayActivate);
        btnCheckStatus = findViewById(R.id.btnCheckStatus);
        btnStartScan = findViewById(R.id.btnStartScan);
        progressScan = findViewById(R.id.progressScan);

        etFullName.setText(prefs.getString("user_name", ""));
        etPhone.setText(prefs.getString("user_phone", ""));

        btnPayActivate.setOnClickListener(v -> startDirectGatewayFlow());
        btnCheckStatus.setOnClickListener(v -> {
            String phone = etPhone.getText().toString().trim();
            if (phone.length() < 10) {
                Toast.makeText(this, "شماره موبایل نامعتبر است", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString("user_phone", phone).putString("user_name", etFullName.getText().toString().trim()).apply();
            verifyPurchaseWithServer(phone, true);
        });

        btnStartScan.setOnClickListener(v -> runAntivirusScan());
        updateUIState();
        handleDeepLink(getIntent());

        String savedPhone = prefs.getString("user_phone", "");
        if (!savedPhone.isEmpty() && !prefs.getBoolean("is_activated", false)) {
            verifyPurchaseWithServer(savedPhone, false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForPaymentReturn && !prefs.getBoolean("is_activated", false)) {
            waitingForPaymentReturn = false;
            String savedPhone = prefs.getString("user_phone", "");
            if (!savedPhone.isEmpty()) {
                verifyPurchaseWithServer(savedPhone, true);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink(intent);
    }

    private void handleDeepLink(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null && "bookwallet".equals(data.getScheme()) && "activate".equals(data.getHost())) {
                waitingForPaymentReturn = false;
                String statusParam = data.getQueryParameter("status");
                String phoneParam = data.getQueryParameter("phone");
                String phoneToVerify = (phoneParam != null && !phoneParam.isEmpty()) ? phoneParam : prefs.getString("user_phone", "");

                if ("failed".equalsIgnoreCase(statusParam)) {
                    Toast.makeText(this, "پرداخت ناموفق بود یا لغو شد.", Toast.LENGTH_LONG).show();
                    return;
                }

                if (!phoneToVerify.isEmpty()) {
                    prefs.edit().putString("user_phone", phoneToVerify).apply();
                    etPhone.setText(phoneToVerify);
                    verifyPurchaseWithServer(phoneToVerify, true);
                }
            }
        }
    }

    private void startDirectGatewayFlow() {
        String name = etFullName.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();

        if (name.isEmpty() || phone.length() < 10) {
            Toast.makeText(this, "وارد کردن نام و شماره موبایل الزامی است", Toast.LENGTH_SHORT).show();
            return;
        }

        prefs.edit().putString("user_name", name).putString("user_phone", phone).apply();

        try {
            String directPayUrl = PAY_API + "?name=" + URLEncoder.encode(name, "UTF-8") + "&phone=" + URLEncoder.encode(phone, "UTF-8");
            waitingForPaymentReturn = true;
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(directPayUrl));
            startActivity(browserIntent);
        } catch (Exception e) {
            waitingForPaymentReturn = false;
            Toast.makeText(this, "خطا در اتصال به درگاه", Toast.LENGTH_SHORT).show();
        }
    }

    private void verifyPurchaseWithServer(String phone, boolean showFeedback) {
        if (showFeedback) Toast.makeText(this, "در حال بررسی وضعیت...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            boolean isActivated = false;
            try {
                URL url = new URL(VERIFY_API);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                JSONObject payload = new JSONObject();
                payload.put("phone", phone);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.toString().getBytes("UTF-8"));
                }

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    JSONObject response = new JSONObject(sb.toString());
                    isActivated = response.optBoolean("activated", false);
                }
                conn.disconnect();
            } catch (Exception ignored) {}

            final boolean finalStatus = isActivated;
            mainHandler.post(() -> {
                prefs.edit().putBoolean("is_activated", finalStatus).apply();
                updateUIState();
                if (finalStatus) {
                    Toast.makeText(MainActivity.this, "آنتی‌ویروس با موفقیت فعال شد!", Toast.LENGTH_LONG).show();
                } else if (showFeedback) {
                    Toast.makeText(this, "پرداخت تایید نشد.", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void updateUIState() {
        boolean isActivated = prefs.getBoolean("is_activated", false);
        String name = prefs.getString("user_name", "");

        if (isActivated) {
            tvStatusBadge.setText("وضعیت: فعال");
            tvStatusBadge.setTextColor(Color.parseColor("#34D399"));
            layoutActivation.setVisibility(View.GONE);
            layoutScanner.setVisibility(View.VISIBLE);
            tvWelcomeUser.setText("کاربر: " + name);
        } else {
            tvStatusBadge.setText("وضعیت: غیرفعال");
            tvStatusBadge.setTextColor(Color.parseColor("#FF6B6B"));
            layoutActivation.setVisibility(View.VISIBLE);
            layoutScanner.setVisibility(View.GONE);
        }
    }

    private void runAntivirusScan() {
        btnStartScan.setEnabled(false);
        progressScan.setVisibility(View.VISIBLE);
        progressScan.setProgress(0);
        tvScanLogs.setText("شروع اسکن... \n");

        executor.execute(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<String> riskyPermissions = Arrays.asList(
                    "android.permission.READ_SMS", "android.permission.RECEIVE_SMS",
                    "android.permission.RECORD_AUDIO", "android.permission.SYSTEM_ALERT_WINDOW"
            );

            int total = apps.size();
            int threats = 0;

            for (int i = 0; i < total; i++) {
                ApplicationInfo appInfo = apps.get(i);
                if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;

                String appLabel = pm.getApplicationLabel(appInfo).toString();
                String pkgName = appInfo.packageName;
                int riskScore = 0;

                try {
                    String[] requestedPerms = pm.getPackageInfo(pkgName, PackageManager.GET_PERMISSIONS).requestedPermissions;
                    if (requestedPerms != null) {
                        for (String perm : requestedPerms) {
                            if (riskyPermissions.contains(perm)) riskScore++;
                        }
                    }
                } catch (Exception ignored) {}

                final int currentProgress = (int) (((i + 1) / (float) total) * 100);
                final boolean isSuspicious = riskScore >= 3 && !pkgName.equals(getPackageName());
                if (isSuspicious) threats++;

                final String logEntry = isSuspicious ? "[هشدار] " + appLabel + "\n" : "[ایمن] " + appLabel + "\n";

                mainHandler.post(() -> {
                    progressScan.setProgress(currentProgress);
                    tvScanStatus.setText("در حال بررسی: " + appLabel);
                    tvScanLogs.append(logEntry);
                });

                try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            }

            final int finalThreats = threats;
            mainHandler.post(() -> {
                btnStartScan.setEnabled(true);
                progressScan.setProgress(100);
                tvScanStatus.setText("اسکن کامل شد. موارد مشکوک: " + finalThreats);
                tvScanLogs.append("\n--- پایان اسکن ---\n");
            });
        });
    }
}
