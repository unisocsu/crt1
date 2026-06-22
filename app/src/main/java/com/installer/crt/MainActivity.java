package com.installer.crt;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static class FileExplorerItem {
        File file;
        String displayName;
        boolean isDirectory;
        boolean isParentPointer;

        FileExplorerItem(File file, String displayName, boolean isDirectory, boolean isParentPointer) {
            this.file = file;
            this.displayName = displayName;
            this.isDirectory = isDirectory;
            this.isParentPointer = isParentPointer;
        }
    }

    // מחלקה פנימית קטנה לצורך שיפור ביצועי הגלילה (ViewHolder Pattern)
    private static class ViewHolder {
        TextView tvItemName;
        CheckBox cbFileCheck;
        ViewHolder(TextView tvItemName, CheckBox cbFileCheck) {
            this.tvItemName = tvItemName;
            this.cbFileCheck = cbFileCheck;
        }
    }

    private TextView tvCurrentPath;
    private ListView lvFileExplorer;
    private RadioButton rbTemporary;
    private CheckBox cbAutoBoot;

    private File currentDir;
    private final List<FileExplorerItem> currentDirItems = new ArrayList<>();
    private final HashSet<String> checkedFilePaths = new HashSet<>();
    private FileExplorerAdapter explorerAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rbTemporary = findViewById(R.id.rbTemporary);
        cbAutoBoot = findViewById(R.id.cbAutoBoot);
        tvCurrentPath = findViewById(R.id.tvCurrentPath);
        lvFileExplorer = findViewById(R.id.lvFileExplorer);
        Button btnInstallCert = findViewById(R.id.btnInstallCert);

        rbTemporary.setOnCheckedChangeListener((buttonView, isChecked) -> {
            cbAutoBoot.setEnabled(isChecked);
            if (!isChecked) cbAutoBoot.setChecked(false);
        });

        currentDir = Environment.getExternalStorageDirectory();
        explorerAdapter = new FileExplorerAdapter();
        lvFileExplorer.setAdapter(explorerAdapter);

        lvFileExplorer.setOnItemClickListener((parent, view, position, id) -> {
            FileExplorerItem selectedItem = currentDirItems.get(position);
            
            if (selectedItem.isParentPointer) {
                File parentFile = currentDir.getParentFile();
                if (parentFile != null) {
                    currentDir = parentFile;
                    loadDirectory(currentDir);
                }
            } else if (selectedItem.isDirectory) {
                currentDir = selectedItem.file;
                loadDirectory(currentDir);
            } else {
                String path = selectedItem.file.getAbsolutePath();
                if (checkedFilePaths.contains(path)) {
                    checkedFilePaths.remove(path);
                } else {
                    checkedFilePaths.add(path);
                }
                explorerAdapter.notifyDataSetChanged();
            }
        });

        btnInstallCert.setOnClickListener(v -> handleUserInstallation());

        if (manageRootCertificateInstallation(null, false, true)) {
            Toast.makeText(this, "תעודות שמורות הוטענו בהצלחה ל-RAM!", Toast.LENGTH_SHORT).show();
        }

        checkPermissionsAndInit();
        rbTemporary.requestFocus();
    }

    private void checkPermissionsAndInit() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 101);
        } else {
            loadDirectory(currentDir);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadDirectory(currentDir);
        } else {
            Toast.makeText(this, "נדרשת הרשאת קבצים עבור הסייר", Toast.LENGTH_LONG).show();
        }
    }

    private void loadDirectory(File dir) {
        currentDirItems.clear();
        tvCurrentPath.setText("נתיב נוכחי: " + dir.getAbsolutePath());

        if (dir.getParentFile() != null && !dir.equals(Environment.getExternalStorageDirectory().getParentFile())) {
            currentDirItems.add(new FileExplorerItem(null, ".. [חזור למעלה]", false, true));
        }

        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory() && !f.getName().startsWith(".")) {
                    currentDirItems.add(new FileExplorerItem(f, "[" + f.getName() + "]", true, false));
                }
            }
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".crt")) {
                    currentDirItems.add(new FileExplorerItem(f, f.getName(), false, false));
                }
            }
        }
        explorerAdapter.notifyDataSetChanged();
        lvFileExplorer.setSelection(0);
    }

    private void handleUserInstallation() {
        List<File> filesToInstall = new ArrayList<>();
        File autoCertsDir = new File(getExternalFilesDir(null), "auto_certs");
        if (!autoCertsDir.exists()) autoCertsDir.mkdirs();

        boolean isPermanent = !rbTemporary.isChecked();
        boolean shouldAutoBoot = cbAutoBoot.isChecked();

        for (String path : checkedFilePaths) {
            File file = new File(path);
            if (file.exists()) {
                filesToInstall.add(file);

                if (!isPermanent && shouldAutoBoot) {
                    File saveFile = new File(autoCertsDir, "auto_" + file.getName());
                    try (FileInputStream in = new FileInputStream(file);
                         FileOutputStream out = new FileOutputStream(saveFile)) {
                        byte[] buf = new byte[1024]; int len;
                        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                    } catch (IOException e) { e.printStackTrace(); }
                }
            }
        }

        if (filesToInstall.isEmpty()) {
            Toast.makeText(this, "אנא סמן לפחות תעודה אחת ב-V לפני ההתקנה", Toast.LENGTH_SHORT).show();
            return;
        }

        if (manageRootCertificateInstallation(filesToInstall, isPermanent, false)) {
            Toast.makeText(this, "ההתקנה של " + filesToInstall.size() + " תעודות בוצעה בהצלחה!", Toast.LENGTH_LONG).show();
            checkedFilePaths.clear();
            explorerAdapter.notifyDataSetChanged();
        } else {
            Toast.makeText(this, "ההתקנה נכשלה.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean manageRootCertificateInstallation(List<File> certFilesList, boolean isPermanent, boolean loadAllFromAutoDir) {
        Process process = null;
        DataOutputStream os = null;
        try {
            File autoCertsDir = new File(getExternalFilesDir(null), "auto_certs");
            if (loadAllFromAutoDir) {
                if (!autoCertsDir.exists() || autoCertsDir.listFiles() == null || autoCertsDir.listFiles().length == 0) {
                    return false;
                }
            }

            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());

            if (!isPermanent || loadAllFromAutoDir) {
                os.writeBytes("mkdir -p /data/local/tmp/cacerts_backup\n");
                os.writeBytes("cp -a /system/etc/security/cacerts/. /data/local/tmp/cacerts_backup/\n");
                os.writeBytes("mount -t tmpfs tmpfs /system/etc/security/cacerts\n");
                os.writeBytes("cp -a /data/local/tmp/cacerts_backup/. /system/etc/security/cacerts/\n");
            }

            if (loadAllFromAutoDir) {
                File[] files = autoCertsDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        String path = file.getAbsolutePath();
                        os.writeBytes("HASH=$(openssl x509 -inform PEM -subject_hash_old -in " + path + " | head -n 1)\n");
                        os.writeBytes("if [ ! -z \"$HASH\" ]; then cp " + path + " /system/etc/security/cacerts/$HASH.0; fi\n");
                    }
                }
            } else if (certFilesList != null && !certFilesList.isEmpty()) {
                if (isPermanent) {
                    os.writeBytes("mount -o remount,rw /system\n");
                    os.writeBytes("mount -o remount,rw /system/system\n");
                    os.writeBytes("mkdir -p /system/system/etc/security/cacerts/\n");
                }

                for (File file : certFilesList) {
                    if (file.exists()) {
                        String path = file.getAbsolutePath();
                        os.writeBytes("HASH=$(openssl x509 -inform PEM -subject_hash_old -in " + path + " | head -n 1)\n");
                        os.writeBytes("if [ ! -z \"$HASH\" ]; then\n");
                        os.writeBytes("  TARGET_NAME=\"$HASH.0\"\n");
                        if (isPermanent) {
                            os.writeBytes("  cp " + path + " /system/etc/security/cacerts/$TARGET_NAME\n");
                            os.writeBytes("  chmod 644 /system/etc/security/cacerts/$TARGET_NAME\n");
                            os.writeBytes("  cp " + path + " /system/system/etc/security/cacerts/$TARGET_NAME\n");
                            os.writeBytes("  chmod 644 /system/system/etc/security/cacerts/$TARGET_NAME\n");
                        } else {
                            os.writeBytes("  cp " + path + " /system/etc/security/cacerts/$TARGET_NAME\n");
                        }
                        os.writeBytes("fi\n");
                    }
                }
            }

            if (!isPermanent || loadAllFromAutoDir) {
                os.writeBytes("chmod 644 /system/etc/security/cacerts/*\n");
                os.writeBytes("rm -rf /data/local/tmp/cacerts_backup\n");
            }

            os.writeBytes("exit 0\n");
            os.flush();
            process.waitFor();
            return process.exitValue() == 0;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (os != null) { try { os.close(); } catch (IOException ignored) {} }
            if (process != null) { process.destroy(); }
        }
    }

    /**
     * ה-Adapter המעודכן שמייצר את ממשק השורות באופן תכנותי ללא קובץ XML נוסף
     */
    private class FileExplorerAdapter extends BaseAdapter {
        @Override
        public int getCount() { return currentDirItems.size(); }
        @Override
        public Object getItem(int position) { return currentDirItems.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout rowLayout;
            TextView tvItemName;
            CheckBox cbFileCheck;

            // המרה של ערכי DP לפיקסלים בצורה דינמית כדי להתאים לכל מסך
            float scale = getResources().getDisplayMetrics().density;
            int padding12 = (int) (12 * scale + 0.5f);
            int margin8 = (int) (8 * scale + 0.5f);

            if (convertView == null) {
                // 1. יצירת קונטיינר אופקי לשורה
                rowLayout = new LinearLayout(MainActivity.this);
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                rowLayout.setPadding(padding12, padding12, padding12, padding12);
                rowLayout.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);

                // 2. יצירת רכיב הטקסט של שם הקובץ
                tvItemName = new TextView(MainActivity.this);
                LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                tvItemName.setLayoutParams(textParams);
                tvItemName.setTextSize(16);
                tvItemName.setGravity(Gravity.RIGHT);

                // 3. יצירת תיבת הסימון (V)
                cbFileCheck = new CheckBox(MainActivity.this);
                LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                checkParams.setMargins(margin8, 0, 0, 0);
                cbFileCheck.setLayoutParams(checkParams);
                
                // הגדרה קריטית למקשים - חסימת פוקוס ישיר על התיבה כדי שהשורה כולה תקבל פוקוס
                cbFileCheck.setFocusable(false);
                cbFileCheck.setClickable(false);

                // הוספת הרכיבים לקונטיינר
                rowLayout.addView(tvItemName);
                rowLayout.addView(cbFileCheck);

                // שמירת הרכיבים בתוך ה-Tag למניעת יצירה מחדש בגלילה
                rowLayout.setTag(new ViewHolder(tvItemName, cbFileCheck));
            } else {
                rowLayout = (LinearLayout) convertView;
                ViewHolder holder = (ViewHolder) rowLayout.getTag();
                tvItemName = holder.tvItemName;
                cbFileCheck = holder.cbFileCheck;
            }

            // הזנת הנתונים לשורה הנוכחית
            FileExplorerItem item = currentDirItems.get(position);
            tvItemName.setText(item.displayName);

            if (item.isDirectory || item.isParentPointer) {
                cbFileCheck.setVisibility(View.GONE);
                cbFileCheck.setChecked(false);
                tvItemName.setTextColor(0xFF0076FF); // כחול לתיקיות וניווט
            } else {
                cbFileCheck.setVisibility(View.VISIBLE);
                cbFileCheck.setChecked(checkedFilePaths.contains(item.file.getAbsolutePath()));
                tvItemName.setTextColor(0xFF333333); // שחור/אפור כהה לקבצים
            }

            return rowLayout;
        }
    }
}