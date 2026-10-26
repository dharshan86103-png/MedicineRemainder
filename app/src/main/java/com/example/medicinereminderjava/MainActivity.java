package com.example.medicinereminderjava;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    // Name of the HC-05 as it appears in the phone's Bluetooth settings
    private static final String ARDUINO_NAME = "HC-05";

    private FrameLayout screenContainer;
    private ArrayList<Medicine> medicines;

    private BluetoothHelper btHelper;
    private boolean arduinoConnected = false;
    private int currentTab = R.id.navHome;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final int teal = Color.rgb(0, 150, 136);
    private final int ink = Color.rgb(36, 54, 75);
    private final int page = Color.rgb(244, 247, 250);
    private final int paleTeal = Color.rgb(224, 242, 241);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        View mainView = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(mainView, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
            );
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });

        // Ask for Bluetooth permission on newer phones
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
        }

        btHelper = new BluetoothHelper(new BluetoothHelper.Listener() {
            @Override
            public void onConnected() {
                arduinoConnected = true;
                Toast.makeText(MainActivity.this, "Arduino connected",
                        Toast.LENGTH_SHORT).show();
                sendAllToArduino();
                refreshStatusScreen();
            }

            @Override
            public void onDisconnected() {
                if (arduinoConnected) {
                    arduinoConnected = false;
                    Toast.makeText(MainActivity.this, "Arduino disconnected",
                            Toast.LENGTH_SHORT).show();
                    refreshStatusScreen();
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MainActivity.this, message,
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onMessage(String message) {
                if (message.startsWith("ALERT:")) {
                    String name = message.substring(6).replace("_", " ");
                    Toast.makeText(MainActivity.this, "Time to take " + name,
                            Toast.LENGTH_LONG).show();
                } else if (message.startsWith("TAKEN:")) {
                    String name = message.substring(6).replace("_", " ");
                    Toast.makeText(MainActivity.this, "Dose taken: " + name,
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        screenContainer = findViewById(R.id.screenContainer);

        medicines = new ArrayList<>();
        medicines.add(new Medicine("Paracetamol", "1 tablet • After Food", "09:00 AM"));
        medicines.add(new Medicine("Vitamin D", "1 capsule • After Food", "01:00 PM"));
        medicines.add(new Medicine("Cough Syrup", "10 ml • Before Food", "06:30 PM"));

        findViewById(R.id.navHome).setOnClickListener(v -> showHome());
        findViewById(R.id.navReminders).setOnClickListener(v -> showReminders());
        findViewById(R.id.navAdd).setOnClickListener(v -> showAddMedicineDialog());
        findViewById(R.id.navHistory).setOnClickListener(v -> showHistory());
        findViewById(R.id.navSettings).setOnClickListener(v -> showSettings());

        showHome();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (btHelper != null) {
            btHelper.close();
        }
    }

    // ---------------------------------------------------------------
    // Bluetooth helpers
    // ---------------------------------------------------------------

    private void connectArduino() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            Toast.makeText(this, "Allow Bluetooth permission, then tap Connect again",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Connecting to " + ARDUINO_NAME + "...",
                Toast.LENGTH_SHORT).show();
        btHelper.connect(ARDUINO_NAME);
    }

    // Turns "08:00 AM", "8:00 pm" or "13:00" into "08:00" / "20:00" / "13:00"
    private String to24Hour(String input) {
        String text = input.trim().toUpperCase(Locale.US);
        String[] patterns = {"h:mm a", "hh:mm a", "H:mm"};
        for (String pattern : patterns) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
                parser.setLenient(false);
                Date date = parser.parse(text);
                if (date != null) {
                    return new SimpleDateFormat("HH:mm", Locale.US).format(date);
                }
            } catch (ParseException ignored) {
            }
        }
        return null;
    }

    // Builds a message like: ADD Paracetamol 09:00
    private String buildAddCommand(Medicine medicine) {
        String time24 = to24Hour(medicine.time);
        if (time24 == null) {
            return null;
        }
        return "ADD " + medicine.name.replaceAll("\\s+", "_") + " " + time24;
    }

    private void sendToArduinoIfConnected(Medicine medicine) {
        if (!arduinoConnected) {
            return;
        }
        String command = buildAddCommand(medicine);
        if (command == null) {
            Toast.makeText(this,
                    "Time not understood, so it was not sent to Arduino",
                    Toast.LENGTH_LONG).show();
            return;
        }
        btHelper.send(command);
    }

    // Clears the Arduino's list and sends every medicine, one every half second
    private void sendAllToArduino() {
        ArrayList<String> commands = new ArrayList<>();
        commands.add("CLEAR");
        for (Medicine medicine : medicines) {
            String command = buildAddCommand(medicine);
            if (command != null) {
                commands.add(command);
            }
        }
        for (int i = 0; i < commands.size(); i++) {
            final String command = commands.get(i);
            handler.postDelayed(() -> btHelper.send(command), i * 500L);
        }
    }

    private void refreshStatusScreen() {
        if (currentTab == R.id.navHome) {
            showHome();
        } else if (currentTab == R.id.navSettings) {
            showSettings();
        }
    }

    private void addConnectButton(LinearLayout parent) {
        Button button = makeButton(
                arduinoConnected ? "Disconnect Arduino" : "Connect Arduino"
        );
        button.setOnClickListener(v -> {
            if (arduinoConnected) {
                arduinoConnected = false;
                btHelper.close();
                refreshStatusScreen();
            } else {
                connectArduino();
            }
        });
        parent.addView(button, bottomSpace(12));
    }

    // ---------------------------------------------------------------
    // Screens
    // ---------------------------------------------------------------

    private void showHome() {
        selectTab(R.id.navHome);
        LinearLayout content = startScreen();

        String today = new SimpleDateFormat(
                "EEEE, d MMMM", Locale.getDefault()
        ).format(new Date());

        addText(content, "Good day!", 23, ink, true, 0);
        addText(content, today, 14, Color.GRAY, false, 4);

        addInfoCard(content, "TODAY'S PLAN",
                medicines.size() + " medicines scheduled for today",
                "Keep your doses on time.", paleTeal);

        if (!medicines.isEmpty()) {
            Medicine next = medicines.get(0);
            addInfoCard(content, "NEXT REMINDER",
                    next.name + " • " + next.time,
                    next.dose, Color.WHITE);
        }

        addInfoCard(content, "DEVICE STATUS",
                arduinoConnected ? "Arduino: Connected" : "Arduino: Not connected",
                arduinoConnected
                        ? "Reminders are sent to the device over Bluetooth."
                        : "This app preview works without hardware.",
                Color.WHITE);
        addConnectButton(content);

        addText(content, "Upcoming medicines", 19, ink, true, 18);

        int shown = Math.min(2, medicines.size());
        for (int i = 0; i < shown; i++) {
            addMedicineCard(content, medicines.get(i));
        }

        Button viewAll = makeButton("View all reminders");
        viewAll.setOnClickListener(v -> showReminders());
        content.addView(viewAll, bottomSpace(8));
    }

    private void showReminders() {
        selectTab(R.id.navReminders);
        LinearLayout content = startScreen();

        addText(content, "Your Reminders", 23, ink, true, 0);
        addText(content, "Today's full medicine schedule",
                14, Color.GRAY, false, 4);

        addInfoCard(content, "SCHEDULE SUMMARY",
                medicines.size() + " reminder(s)",
                "Tap Add below to create another reminder.", paleTeal);

        if (medicines.isEmpty()) {
            addText(content, "No reminders yet. Tap Add to create one.",
                    16, ink, false, 12);
        } else {
            for (Medicine medicine : medicines) {
                addMedicineCard(content, medicine);
            }
        }

        Button addButton = makeButton("+  Add Medicine");
        addButton.setOnClickListener(v -> showAddMedicineDialog());
        content.addView(addButton, topSpace(8));
    }

    private void showHistory() {
        selectTab(R.id.navHistory);
        LinearLayout content = startScreen();

        addText(content, "Medicine History", 23, ink, true, 0);
        addText(content, "Example activity for your app presentation",
                14, Color.GRAY, false, 4);

        addInfoCard(content, "TODAY",
                "2 doses marked as taken",
                "This is sample history for the prototype.", paleTeal);

        addHistoryCard(content, "Paracetamol", "09:05 AM", "Taken");
        addHistoryCard(content, "Vitamin D", "01:10 PM", "Taken");
        addHistoryCard(content, "Cough Syrup", "06:30 PM", "Upcoming");

        addText(content, "History tracking can be connected to saved data later.",
                14, Color.GRAY, false, 8);
    }

    private void showSettings() {
        selectTab(R.id.navSettings);
        LinearLayout content = startScreen();

        addText(content, "Settings", 23, ink, true, 0);
        addText(content, "Manage app preferences", 14, Color.GRAY, false, 4);

        addInfoCard(content, "ARDUINO CONNECTION",
                arduinoConnected ? "Connected" : "Not connected",
                arduinoConnected
                        ? "Linked to " + ARDUINO_NAME + " over Bluetooth."
                        : "Pair " + ARDUINO_NAME
                          + " in phone Bluetooth settings, then tap Connect.",
                Color.WHITE);
        addConnectButton(content);

        addSwitchRow(content, "Reminder notifications",
                "Show an alert when a dose is due.", true);
        addSwitchRow(content, "Reminder sound",
                "Play a sound with reminder alerts.", true);
        addSwitchRow(content, "Daily summary",
                "Show a summary of today's medicines.", false);

        addInfoCard(content, "ABOUT",
                "Medicine Reminder • Demo version",
                "A simple app concept to organize medicine times.",
                Color.WHITE);
    }

    private LinearLayout startScreen() {
        screenContainer.removeAllViews();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(22));
        content.setBackgroundColor(page);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(content);
        screenContainer.addView(scrollView);

        return content;
    }

    private void addInfoCard(
            LinearLayout parent, String heading, String title,
            String detail, int backgroundColor
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundColor(backgroundColor);

        card.addView(makeText(heading, 12, teal, true), bottomSpace(5));
        card.addView(makeText(title, 17, ink, true), bottomSpace(3));
        card.addView(makeText(detail, 14, Color.GRAY, false));

        parent.addView(card, bottomSpace(12));
    }

    private void addMedicineCard(LinearLayout parent, Medicine medicine) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundColor(Color.WHITE);

        card.addView(makeText("💊  " + medicine.name, 17, ink, true),
                bottomSpace(5));
        card.addView(makeText(medicine.dose, 14, Color.GRAY, false),
                bottomSpace(5));
        card.addView(makeText("⏰  Scheduled for " + medicine.time,
                14, teal, true));

        parent.addView(card, bottomSpace(10));
    }

    private void addHistoryCard(
            LinearLayout parent, String medicine, String time, String status
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundColor(Color.WHITE);

        card.addView(makeText("💊  " + medicine, 17, ink, true));
        card.addView(makeText(time + " • " + status, 14, teal, false),
                topSpace(5));

        parent.addView(card, bottomSpace(10));
    }

    private void addSwitchRow(
            LinearLayout parent, String title, String detail, boolean checked
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundColor(Color.WHITE);

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(makeText(title, 16, ink, true), bottomSpace(3));
        words.addView(makeText(detail, 13, Color.GRAY, false));

        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((button, isChecked) ->
                Toast.makeText(this,
                        isChecked ? "Turned on for this demo" : "Turned off for this demo",
                        Toast.LENGTH_SHORT).show()
        );

        row.addView(words, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1
        ));
        row.addView(toggle);
        parent.addView(row, bottomSpace(10));
    }

    private void addText(
            LinearLayout parent, String text, int size,
            int color, boolean bold, int topMargin
    ) {
        TextView view = makeText(text, size, color, bold);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(topMargin);
        params.bottomMargin = dp(8);
        parent.addView(view, params);
    }

    private TextView makeText(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        return view;
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(teal));
        return button;
    }

    private void showAddMedicineDialog() {
        selectTab(R.id.navAdd);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), 0, dp(20), 0);

        EditText nameInput = new EditText(this);
        nameInput.setHint("Medicine name");
        nameInput.setSingleLine(true);
        form.addView(nameInput);

        EditText doseInput = new EditText(this);
        doseInput.setHint("Dose (example: 1 tablet)");
        doseInput.setSingleLine(true);
        form.addView(doseInput);

        EditText timeInput = new EditText(this);
        timeInput.setHint("Time (example: 08:00 AM)");
        timeInput.setSingleLine(true);
        form.addView(timeInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Add Medicine")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();

        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String name = nameInput.getText().toString().trim();
                    String dose = doseInput.getText().toString().trim();
                    String time = timeInput.getText().toString().trim();

                    if (name.isEmpty() || dose.isEmpty() || time.isEmpty()) {
                        Toast.makeText(this, "Please fill in all fields",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Medicine added = new Medicine(name, dose, time);
                    medicines.add(added);
                    sendToArduinoIfConnected(added);

                    dialog.dismiss();
                    showReminders();
                    Toast.makeText(this, "Medicine added",
                            Toast.LENGTH_SHORT).show();
                })
        );

        dialog.setOnCancelListener(ignored -> showHome());
        dialog.show();
    }

    private void selectTab(int selectedId) {
        currentTab = selectedId;

        int[] tabIds = {
                R.id.navHome, R.id.navReminders, R.id.navAdd,
                R.id.navHistory, R.id.navSettings
        };

        int[] iconIds = {
                R.id.iconHome, R.id.iconReminders, R.id.iconAdd,
                R.id.iconHistory, R.id.iconSettings
        };

        int[] labelIds = {
                R.id.labelHome, R.id.labelReminders, R.id.labelAdd,
                R.id.labelHistory, R.id.labelSettings
        };

        for (int i = 0; i < tabIds.length; i++) {
            boolean selected = tabIds[i] == selectedId;
            View tab = findViewById(tabIds[i]);
            TextView icon = findViewById(iconIds[i]);
            TextView label = findViewById(labelIds[i]);

            tab.setBackgroundColor(selected ? paleTeal : Color.WHITE);
            icon.setTextColor(selected ? teal : Color.GRAY);
            label.setTextColor(selected ? teal : Color.GRAY);
        }
    }

    private LinearLayout.LayoutParams bottomSpace(int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(marginDp);
        return params;
    }

    private LinearLayout.LayoutParams topSpace(int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(marginDp);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private static class Medicine {
        String name;
        String dose;
        String time;

        Medicine(String name, String dose, String time) {
            this.name = name;
            this.dose = dose;
            this.time = time;
        }
    }
}