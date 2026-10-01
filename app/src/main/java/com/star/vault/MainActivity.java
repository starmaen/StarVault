package com.star.vault;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

public class MainActivity extends Activity {

    private int clickCounter = 0;
    private long lastClickTime = 0;
    private View camouflageView;
    private ScrollView vaultView;
    private EditText etPackageName;
    private TextView tvStatus, tvPrivilege;
    private SharedPreferences prefs;

    private boolean hasRoot = false;
    private boolean hasShizuku = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        camouflageView = findViewById(R.id.camouflageView);
        vaultView = findViewById(R.id.vaultView);
        etPackageName = findViewById(R.id.etPackageName);
        tvStatus = findViewById(R.id.tvStatus);
        tvPrivilege = findViewById(R.id.tvPrivilege);
        TextView starIcon = findViewById(R.id.starIcon);
        TextView clockText = findViewById(R.id.clockText);

        prefs = getSharedPreferences("StarPrefs", Context.MODE_PRIVATE);
        clockText.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));

        checkSystemPrivileges();

        starIcon.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastClickTime < 1500) {
                clickCounter++;
            } else {
                clickCounter = 1;
            }
            lastClickTime = now;

            if (clickCounter >= 5) {
                clickCounter = 0;
                showPasswordDialog();
            }
        });

        findViewById(R.id.btnAddApp).setOnClickListener(v -> pickApp());
        findViewById(R.id.btnHideApp).setOnClickListener(v -> hideApp());
        findViewById(R.id.btnLockApp).setOnClickListener(v -> lockApp());
        findViewById(R.id.btnUnHideApp).setOnClickListener(v -> unhideApp());

        if (getIntent().getBooleanExtra("TRIGGER_LOCK", false)) {
            showPasswordDialog();
        }
    }

    private void checkSystemPrivileges() {
        new Thread(() -> {
            try {
                java.lang.Process p = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(p.getOutputStream());
                os.writeBytes("id\nexit\n");
                os.flush();
                hasRoot = (p.waitFor() == 0);
            } catch (Exception e) {
                hasRoot = false;
            }

            try {
                if (!hasRoot && Shizuku.pingBinder()) {
                    hasShizuku = (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED);
                    if (!hasShizuku) {
                        Shizuku.requestPermission(101);
                    }
                }
            } catch (Exception ignored) {
                hasShizuku = false;
            }

            runOnUiThread(() -> {
                if (hasRoot) {
                    tvPrivilege.setText("🟢 صلاحية الروت نشطة (إخفاء الأيقونة متاح)");
                } else if (hasShizuku) {
                    tvPrivilege.setText("🔵 صلاحية Shizuku نشطة (إخفاء الأيقونة متاح)");
                } else {
                    tvPrivilege.setText("🟡 قفل التطبيقات برمز سري نشط (بدون روت / شيزوكو)");
                }
            });
        }).start();
    }

    private void executePrivilegedAction(String pkg, boolean hide) {
        if (hasRoot) {
            try {
                java.lang.Process p = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(p.getOutputStream());
                String cmd = hide ? ("pm hide " + pkg + " || pm disable-user --user 0 " + pkg + "\n")
                                  : ("pm unhide " + pkg + " || pm enable " + pkg + "\n");
                os.writeBytes(cmd + "exit\n");
                os.flush();
                p.waitFor();
            } catch (Exception ignored) {}
        } else if (hasShizuku) {
            try {
                // تنفيذ أمر إدارة الحزم عبر Shizuku Binder الرسمي دون الحاجة لدوال private
                IBinder originalBinder = SystemServiceHelper.getSystemService("package");
                IBinder wrappedBinder = new ShizukuBinderWrapper(originalBinder);
                Class<?> stubClass = Class.forName("android.content.pm.IPackageManager$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                Object pmInstance = asInterface.invoke(null, wrappedBinder);

                int newState = hide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                                    : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
                Method setEnabledSetting = pmInstance.getClass().getMethod(
                        "setApplicationEnabledSetting", String.class, int.class, int.class, int.class, String.class);
                setEnabledSetting.invoke(pmInstance, pkg, newState, 0, 0, "shell");
            } catch (Exception ignored) {}
        }
    }

    private void showPasswordDialog() {
        String saved = prefs.getString("pin", null);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(saved == null ? "تعيين رمز الدخول للخزنة" : "أدخل الرمز السري");

        final EditText input = new EditText(this);
        input.setHint("الرمز السري (4 أرقام على الأقل)");
        b.setView(input);

        b.setPositiveButton("تأكيد", (d, w) -> {
            String entered = input.getText().toString().trim();
            if (saved == null) {
                if (entered.length() >= 4) {
                    prefs.edit().putString("pin", entered).apply();
                    openVault();
                } else {
                    Toast.makeText(this, "الرمز يجب أن يكون 4 أرقام أو أكثر", Toast.LENGTH_SHORT).show();
                }
            } else if (saved.equals(entered)) {
                openVault();
            } else {
                Toast.makeText(this, "رمز غير صحيح!", Toast.LENGTH_SHORT).show();
            }
        });
        b.setNegativeButton("إلغاء", null);
        b.show();
    }

    private void openVault() {
        camouflageView.setVisibility(View.GONE);
        vaultView.setVisibility(View.VISIBLE);
        tvStatus.setText("تم تسجيل الدخول بنجاح.");
    }

    private void hideApp() {
        String pkg = etPackageName.getText().toString().trim();
        if (pkg.isEmpty()) return;

        if (hasRoot || hasShizuku) {
            new Thread(() -> {
                executePrivilegedAction(pkg, true);
                runOnUiThread(() -> {
                    tvStatus.setText("تم تطبيق إخفاء التطبيق: " + pkg);
                    Toast.makeText(this, "تم إخفاء الأيقونة بنجاح", Toast.LENGTH_SHORT).show();
                });
            }).start();
        } else {
            Toast.makeText(this, "إخفاء الأيقونة يتطلب روت أو شيزوكو! يمكنك استخدام زر (قفل برمز).", Toast.LENGTH_LONG).show();
        }
    }

    private void lockApp() {
        String pkg = etPackageName.getText().toString().trim();
        if (pkg.isEmpty()) return;

        Set<String> set = new HashSet<>(prefs.getStringSet("locked_packages", new HashSet<>()));
        set.add(pkg);
        prefs.edit().putStringSet("locked_packages", set).apply();

        AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        if (aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName()) != AppOpsManager.MODE_ALLOWED) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }
        startService(new Intent(this, AppLockService.class));

        tvStatus.setText("تم تأمين وقفل التطبيق برمز سري: " + pkg);
        Toast.makeText(this, "تم قفل التطبيق", Toast.LENGTH_SHORT).show();
    }

    private void unhideApp() {
        String pkg = etPackageName.getText().toString().trim();
        if (pkg.isEmpty()) return;

        if (hasRoot || hasShizuku) {
            new Thread(() -> executePrivilegedAction(pkg, false)).start();
        }
        Set<String> set = new HashSet<>(prefs.getStringSet("locked_packages", new HashSet<>()));
        set.remove(pkg);
        prefs.edit().putStringSet("locked_packages", set).apply();

        tvStatus.setText("تم استرجاع وإلغاء قفل التطبيق: " + pkg);
        Toast.makeText(this, "تمت الإعادة بنجاح", Toast.LENGTH_SHORT).show();
    }

    private void pickApp() {
        new Thread(() -> {
            List<String> list = new ArrayList<>();
            for (ApplicationInfo info : getPackageManager().getInstalledApplications(0)) {
                if ((info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    list.add(info.packageName);
                }
            }
            String[] apps = list.toArray(new String[0]);
            runOnUiThread(() -> {
                new AlertDialog.Builder(this)
                        .setTitle("اختر التطبيق")
                        .setItems(apps, (d, w) -> etPackageName.setText(apps[w]))
                        .show();
            });
        }).start();
    }
}
