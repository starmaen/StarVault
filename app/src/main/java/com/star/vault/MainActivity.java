package com.star.vault;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
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

    public static class AppItem {
        public String name;
        public String packageName;
        public Drawable icon;
        public boolean isHidden;
        public boolean isLocked;

        public AppItem(String name, String packageName, Drawable icon, boolean isHidden, boolean isLocked) {
            this.name = name;
            this.packageName = packageName;
            this.icon = icon;
            this.isHidden = isHidden;
            this.isLocked = isLocked;
        }
    }

    private int clickCounter = 0;
    private long lastClickTime = 0;
    private View camouflageView;
    private LinearLayout vaultView;
    private TextView tvStatus, tvPrivilege;
    private ListView appListView;
    private Button tabHidden, tabInstalled;
    private SharedPreferences prefs;

    private boolean hasRoot = false;
    private boolean hasShizuku = false;
    private boolean currentTabIsHidden = false; // البدء بتبويب كل التطبيقات

    private List<AppItem> currentDisplayList = new ArrayList<>();
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        camouflageView = findViewById(R.id.camouflageView);
        vaultView = findViewById(R.id.vaultView);
        tvStatus = findViewById(R.id.tvStatus);
        tvPrivilege = findViewById(R.id.tvPrivilege);
        appListView = findViewById(R.id.appListView);
        tabHidden = findViewById(R.id.tabHidden);
        tabInstalled = findViewById(R.id.tabInstalled);
        TextView starIcon = findViewById(R.id.starIcon);
        TextView clockText = findViewById(R.id.clockText);

        prefs = getSharedPreferences("StarPrefs", Context.MODE_PRIVATE);
        clockText.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));

        // تصحيح أي بيانات قديمة اختلطت فيها التطبيقات
        if (prefs.getBoolean("clean_v2_needed", true)) {
            prefs.edit().remove("hidden_packages_set").putBoolean("clean_v2_needed", false).apply();
        }

        adapter = new AppAdapter();
        appListView.setAdapter(adapter);

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

        tabHidden.setOnClickListener(v -> switchTab(true));
        tabInstalled.setOnClickListener(v -> switchTab(false));
        findViewById(R.id.btnRefresh).setOnClickListener(v -> loadAppsData());

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
                    tvPrivilege.setText("🟢 صلاحية Root نشطة (إخفاء واستعادة تامة)");
                } else if (hasShizuku) {
                    tvPrivilege.setText("🔵 صلاحية Shizuku نشطة (إخفاء واستعادة)");
                } else {
                    tvPrivilege.setText("🟡 قفل برمز سري نشط (بدون Root أو Shizuku)");
                }
            });
        }).start();
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
        switchTab(false); // فتح قائمة كل التطبيقات تلقائياً
    }

    private void switchTab(boolean showHidden) {
        currentTabIsHidden = showHidden;
        if (showHidden) {
            tabHidden.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFEF4444));
            tabInstalled.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF334155));
        } else {
            tabHidden.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF334155));
            tabInstalled.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF10B981));
        }
        loadAppsData();
    }

    private void loadAppsData() {
        tvStatus.setText("جاري تحميل قائمة التطبيقات...");
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Set<String> hiddenSet = new HashSet<>(prefs.getStringSet("vault_hidden_apps", new HashSet<>()));
            Set<String> lockedSet = prefs.getStringSet("locked_packages", new HashSet<>());
            List<AppItem> list = new ArrayList<>();

            // قراءة التطبيقات مع إظهار المعطل والمثبت
            int flags = PackageManager.GET_META_DATA | 0x00002000 | 0x00000200;
            List<ApplicationInfo> packages = pm.getInstalledApplications(flags);

            for (ApplicationInfo info : packages) {
                if (info.packageName.equals(getPackageName())) continue;
                boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean isHidden = hiddenSet.contains(info.packageName);
                boolean isLocked = lockedSet.contains(info.packageName);

                if (currentTabIsHidden) {
                    // في تبويب المخفي: عرض ما تم إخفاؤه بيدك فقط
                    if (isHidden) {
                        String appName = pm.getApplicationLabel(info).toString();
                        Drawable icon = pm.getApplicationIcon(info);
                        list.add(new AppItem(appName, info.packageName, icon, true, isLocked));
                    }
                } else {
                    // في تبويب كل التطبيقات: عرض جميع التطبيقات غير المخفية
                    if (!isHidden && !isSystem) {
                        String appName = pm.getApplicationLabel(info).toString();
                        Drawable icon = pm.getApplicationIcon(info);
                        list.add(new AppItem(appName, info.packageName, icon, false, isLocked));
                    }
                }
            }

            runOnUiThread(() -> {
                currentDisplayList = list;
                adapter.notifyDataSetChanged();
                if (currentTabIsHidden) {
                    tvStatus.setText("التطبيقات المخفية داخل الخزنة: " + list.size());
                } else {
                    tvStatus.setText("التطبيقات المثبتة: " + list.size() + " (اختر إخفاء أو قفل)");
                }
            });
        }).start();
    }

    private void hideApp(String pkg) {
        new Thread(() -> {
            if (hasRoot) {
                runRoot("pm hide " + pkg + "\npm disable-user --user 0 " + pkg);
            } else if (hasShizuku) {
                setComponentEnabledShizuku(pkg, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
            }

            Set<String> set = new HashSet<>(prefs.getStringSet("vault_hidden_apps", new HashSet<>()));
            set.add(pkg);
            prefs.edit().putStringSet("vault_hidden_apps", set).apply();

            runOnUiThread(() -> {
                Toast.makeText(this, "تم إخفاء التطبيق ونقله للخزنة", Toast.LENGTH_SHORT).show();
                loadAppsData();
            });
        }).start();
    }

    private void restoreApp(String pkg) {
        new Thread(() -> {
            if (hasRoot) {
                runRoot("pm unhide " + pkg + "\npm enable " + pkg + "\ncmd package install-existing " + pkg);
            } else if (hasShizuku) {
                setComponentEnabledShizuku(pkg, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            }

            Set<String> set = new HashSet<>(prefs.getStringSet("vault_hidden_apps", new HashSet<>()));
            set.remove(pkg);
            prefs.edit().putStringSet("vault_hidden_apps", set).apply();

            runOnUiThread(() -> {
                Toast.makeText(this, "تمت استعادة التطبيق للشاشة الرئيسية!", Toast.LENGTH_SHORT).show();
                loadAppsData();
            });
        }).start();
    }

    private void toggleLock(String pkg) {
        Set<String> set = new HashSet<>(prefs.getStringSet("locked_packages", new HashSet<>()));
        if (set.contains(pkg)) {
            set.remove(pkg);
            Toast.makeText(this, "تم إلغاء قفل الرمز", Toast.LENGTH_SHORT).show();
        } else {
            set.add(pkg);
            AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName()) != AppOpsManager.MODE_ALLOWED) {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            }
            startService(new Intent(this, AppLockService.class));
            Toast.makeText(this, "تم قفل التطبيق برمز سري", Toast.LENGTH_SHORT).show();
        }
        prefs.edit().putStringSet("locked_packages", set).apply();
        loadAppsData();
    }

    private void runRoot(String commands) {
        try {
            java.lang.Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(commands + "\nexit\n");
            os.flush();
            p.waitFor();
        } catch (Exception ignored) {}
    }

    private void setComponentEnabledShizuku(String pkg, int state) {
        try {
            IBinder originalBinder = SystemServiceHelper.getSystemService("package");
            IBinder wrappedBinder = new ShizukuBinderWrapper(originalBinder);
            Class<?> stubClass = Class.forName("android.content.pm.IPackageManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            Object pmInstance = asInterface.invoke(null, wrappedBinder);

            Method setEnabledSetting = pmInstance.getClass().getMethod(
                    "setApplicationEnabledSetting", String.class, int.class, int.class, int.class, String.class);
            setEnabledSetting.invoke(pmInstance, pkg, state, 0, 0, "shell");
        } catch (Exception ignored) {}
    }

    private void launchApp(String pkg) {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launchIntent != null) {
            startActivity(launchIntent);
        } else {
            new Thread(() -> runRoot("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1")).start();
        }
    }

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return currentDisplayList.size();
        }

        @Override
        public Object getItem(int position) {
            return currentDisplayList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_app, parent, false);
            }

            AppItem item = currentDisplayList.get(position);
            ImageView img = convertView.findViewById(R.id.imgAppIcon);
            TextView txtName = convertView.findViewById(R.id.txtAppName);
            TextView txtPkg = convertView.findViewById(R.id.txtAppPackage);
            Button btnAction = convertView.findViewById(R.id.btnAction);
            Button btnLock = convertView.findViewById(R.id.btnLock);
            Button btnLaunch = convertView.findViewById(R.id.btnLaunch);

            img.setImageDrawable(item.icon);
            txtName.setText(item.name);
            txtPkg.setText(item.packageName);

            // زر القفل يظهر دائماً في كل التطبيقات
            if (item.isLocked) {
                btnLock.setText("مقفول 🔐");
                btnLock.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF475569));
            } else {
                btnLock.setText("قفل 🔒");
                btnLock.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF7C3AED));
            }
            btnLock.setOnClickListener(v -> toggleLock(item.packageName));

            if (item.isHidden) {
                // حالة التطبيق المخفي
                btnLaunch.setVisibility(View.VISIBLE);
                btnLaunch.setOnClickListener(v -> launchApp(item.packageName));

                btnAction.setText("استعادة 🔄");
                btnAction.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF10B981));
                btnAction.setOnClickListener(v -> restoreApp(item.packageName));
            } else {
                // حالة التطبيق العادي
                btnLaunch.setVisibility(View.GONE);

                btnAction.setText("إخفاء 👁️");
                btnAction.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFEF4444));
                btnAction.setOnClickListener(v -> hideApp(item.packageName));
            }

            return convertView;
        }
    }
}
