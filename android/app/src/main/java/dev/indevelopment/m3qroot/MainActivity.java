package dev.indevelopment.m3qroot;

import android.content.Intent;
import android.content.pm.PackageInfo;
 import android.graphics.Typeface;
 import android.content.SharedPreferences;
 import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Build;
 import android.util.TypedValue;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
   import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.window.OnBackInvokedDispatcher;
 import android.widget.LinearLayout;
 import android.widget.TextView;
 import android.text.method.ScrollingMovementMethod;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
 import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
 import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

public final class MainActivity extends AppCompatActivity {
    private static final int SHIZUKU_PERMISSION_REQUEST = 0x4d33;
    private static final long HOLD_TO_CONFIRM_MILLIS = 1400L;
    private static final String KSU_MANAGER_PACKAGE = "me.weishu.kernelsu";
    private static final int STATUS_SUCCESS = 0xff18753c;
    private static final int STATUS_WORKING = 0xff9a6700;
    private static final int STATUS_WARNING = 0xffb3261e;
    private static final int STATUS_NEUTRAL = 0xff5f6b76;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean shizukuPermissionPending = new AtomicBoolean();
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != SHIZUKU_PERMISSION_REQUEST) return;
                if (!shizukuPermissionPending.compareAndSet(true, false)) return;
                ui.post(() -> {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        append("Shizuku shell permission granted");
                        beginExploit(true);
                    } else {
                        abortPendingRun("Shizuku permission denied; nothing was run.");
                    }
                });
            };

    private M3qRootEngine engine;
    private MaterialCardView statusCard;
    private MaterialCardView diagnosticsCard;
    private TextView status;
    private TextView statusDetail;
    private TextView dashboard;
    private MaterialButton payloadButton;
    private TextView subtitleText;
    private TextView log;
    private MaterialButton run;
    private MaterialButton reapplyModules;
    private MaterialButton restartZygote;
    private MaterialButton statusRefresh;
    private MaterialButton unrootReboot;
    private MaterialButton diagnosticsToggle;
    private boolean diagnosticsVisible;
    private boolean runIsReboot;
    private volatile String activePayloadId = PayloadStore.BUNDLED_PAYLOAD_ID;
    private volatile boolean payloadResolved;
    private volatile java.util.List<PayloadStore.Profile> lastRegistry;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_main);
        applySystemBarInsets(findViewById(R.id.page_scroll));
        bindViews();
        bindActions();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                    if (running.get()) {
                        append("The app cannot be closed until the kernel work finishes.");
                    } else {
                        finish();
                    }
                });

        engine = new M3qRootEngine(this, new M3qRootEngine.Listener() {
            @Override
            public void onStatus(String text, int color) {
                setStatus(text, color);
            }

            @Override
            public void onLog(String line) {
                append(line);
            }
        });

        append("==== device diagnostics ====");
        append("Model: " + Build.MODEL);
        append("Kernel: " + System.getProperty("os.version", "unknown"));
        append("Firmware: " + Build.FINGERPRINT);

        if (!deviceSupported()) {
            setStatus("Checking device", STATUS_WORKING);
            setStatusDetail("Verifying firmware and payload compatibility.");
            run.setEnabled(false);
            append("Bundled payload targets SM-S931B S931BXXUCZZI4 (One UI 9 beta 2); other devices can pick a matching payload.");
        } else {
            setStatus(getString(R.string.status_checking), STATUS_WORKING);
            setStatusDetail(getString(R.string.status_checking_detail));
        }
    }

    private void bindViews() {
        statusCard = findViewById(R.id.status_card);
        diagnosticsCard = findViewById(R.id.diagnostics_card);
        status = findViewById(R.id.status);
        statusDetail = findViewById(R.id.status_detail);
        dashboard = findViewById(R.id.dashboard);
        payloadButton = findViewById(R.id.payload_button);
        subtitleText = findViewById(R.id.app_subtitle);
        subtitleText.setText(deviceMarketingLabel());
        log = findViewById(R.id.log);
        log.setMovementMethod(new ScrollingMovementMethod());
        run = findViewById(R.id.run);
        reapplyModules = findViewById(R.id.reapply_modules);
        restartZygote = findViewById(R.id.restart_zygote);
        statusRefresh = findViewById(R.id.status_refresh);
        unrootReboot = findViewById(R.id.unroot_reboot);
        diagnosticsToggle = findViewById(R.id.diagnostics_toggle);
    }

    private static String deviceMarketingLabel() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String family = deviceFamilyName(model);
        return family.equals(model) ? model : family + " \u00b7 " + model;
    }

    private static String deviceFamilyName(String model) {
        String upper = model.toUpperCase(Locale.US);
        if (upper.startsWith("SM-S931")) return "Galaxy S25";
        if (upper.startsWith("SM-S936")) return "Galaxy S25+";
        if (upper.startsWith("SM-S938")) return "Galaxy S25 Ultra";
        if (upper.startsWith("SM-S937")) return "Galaxy S25 Edge";
        if (upper.startsWith("SM-F966")) return "Galaxy Z Fold 7";
        if (upper.startsWith("SM-F761")) return "Galaxy Z Flip 7";
        return model;
    }

    private static void applySystemBarInsets(View view) {
        int left = view.getPaddingLeft();
        int top = view.getPaddingTop();
        int right = view.getPaddingRight();
        int bottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((target, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            target.setPadding(left + bars.left, top + bars.top,
                    right + bars.right, bottom + bars.bottom);
            return windowInsets;
        });
        view.requestApplyInsets();
    }

    private void bindActions() {
        bindHoldAction(run, "Root", 700L, this::onRunHoldAction);
        bindHoldAction(reapplyModules, "Module reload", HOLD_TO_CONFIRM_MILLIS, this::startModuleReload);
        bindHoldAction(restartZygote, "Soft reboot", HOLD_TO_CONFIRM_MILLIS, this::startSoftBoot);
        bindHoldAction(unrootReboot, "Unroot", HOLD_TO_CONFIRM_MILLIS, this::startUnrootReboot);
        statusRefresh.setOnClickListener(v -> worker.execute(this::refreshRootState));
        diagnosticsToggle.setOnClickListener(v -> toggleDiagnostics());
        findViewById(R.id.export_log).setOnClickListener(v -> exportLastLog());
        payloadButton.setOnClickListener(v -> showPayloadDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engine != null && !running.get()) {
            worker.execute(() -> {
            resolvePayload();
            refreshRootState();
        });
        }
    }

    private void onRunHoldAction() {
        if (runIsReboot) {
            startRebootOnFail();
        } else {
            onRunClicked();
        }
    }

    private volatile PayloadStore.Profile activeProfile;

    /** Bundled-target check, relaxed when a registry payload matches this device. */
    private boolean deviceSupported() {
        if (engine.isSupported()) return true;
        PayloadStore.Profile profile = activeProfile;
        return profile != null
                && profile.models.contains(PayloadStore.deviceModel());
    }

    private void resolvePayload() {
        if (payloadResolved) return;
        payloadResolved = true;
        SharedPreferences prefs = getSharedPreferences("samsu_payload", MODE_PRIVATE);
        String manualId = prefs.getString("manual_payload_id", "");
        List<PayloadStore.Profile> registry;
        try {
            registry = PayloadStore.fetchRegistry();
            lastRegistry = registry;
        } catch (Exception error) {
            append("Payload registry unavailable: " + error.getMessage());
            useBundledPayload(manualId);
            return;
        }

        PayloadStore.Profile match = null;
        boolean kernelMismatch = false;
        if (!manualId.isEmpty()) {
            match = PayloadStore.findById(registry, manualId);
            if (match == null && manualId.equals(PayloadStore.BUNDLED_PAYLOAD_ID)) {
                append("Using bundled payload (manual selection).");
                activePayloadId = PayloadStore.BUNDLED_PAYLOAD_ID;
                activeProfile = null;
                engine.setPayloadOverride(null);
                engine.setKsudOverride(null, -1);
                engine.setKmiOverride(null);
                return;
            }
            if (match != null) {
                kernelMismatch = !PayloadStore.kernelMatches(match);
                if (kernelMismatch) {
                    append("WARNING: manually selected payload " + match.payloadId
                            + " targets a different kernel; trying anyway.");
                }
            }
        }
        if (match == null) {
            match = PayloadStore.matchRemote(registry);
        }
        if (match == null) {
            append("No remote payload matches this device; using bundled "
                    + PayloadStore.BUNDLED_PAYLOAD_ID + ".");
            activePayloadId = PayloadStore.BUNDLED_PAYLOAD_ID;
            activeProfile = null;
            engine.setKsudOverride(null, -1);
            engine.setKmiOverride(null);
            return;
        }
        if (kernelMismatch) {
            append("WARNING: kernel version differs from the payload target; "
                    + "attempting anyway.");
        }
        File cached = PayloadStore.cachedPayload(this, match.payloadId);
        if (cached.isFile()) {
            activePayloadId = match.payloadId;
            activeProfile = match;
            engine.setPayloadOverride(cached);
            applyRegistryKsud(match);
            append("Using cached payload " + match.payloadId + ".");
            return;
        }
        append("Downloading payload " + match.payloadId + " ...");
        try {
            File file = PayloadStore.downloadExploit(this, match);
            activePayloadId = match.payloadId;
            activeProfile = match;
            engine.setPayloadOverride(file);
            applyRegistryKsud(match);
            append("Payload downloaded: " + file.getName()
                    + " (" + file.length() + " bytes)");
        } catch (Exception error) {
            append("Payload download failed: " + error.getMessage());
            append("Using bundled payload " + PayloadStore.BUNDLED_PAYLOAD_ID + ".");
            activePayloadId = PayloadStore.BUNDLED_PAYLOAD_ID;
            activeProfile = null;
            engine.setPayloadOverride(null);
            engine.setKsudOverride(null, -1);
            engine.setKmiOverride(null);
        }
    }

    /** Registry payloads ship their own KSU build; stage it for late-load. */
    private void applyRegistryKsud(PayloadStore.Profile match) {
        if (match.ksudUrl == null || match.ksudUrl.isEmpty()) {
            engine.setKsudOverride(null, -1);
            engine.setKmiOverride(null);
            return;
        }
        try {
            File cachedKsud = PayloadStore.cachedKsud(this, match.payloadId);
            if (!cachedKsud.isFile()) {
                append("Downloading KernelSU daemon " + match.payloadId + " ...");
                cachedKsud = PayloadStore.downloadKsud(this, match);
                append("KernelSU daemon downloaded: " + cachedKsud.getName()
                        + " (" + cachedKsud.length() + " bytes)");
            } else {
                append("Using cached KernelSU daemon " + match.payloadId + ".");
            }
            engine.setKsudOverride(cachedKsud, match.ksudSize);
            engine.setKmiOverride(match.kmi);
        } catch (Exception error) {
            append("KernelSU daemon download failed: " + error.getMessage()
                    + "; using bundled KSU module.");
            engine.setKsudOverride(null, -1);
            engine.setKmiOverride(null);
        }
    }

    private void useBundledPayload(String manualId) {
        File cached = manualId.isEmpty()
                ? null : PayloadStore.cachedPayload(this, manualId);
        if (cached != null && cached.isFile()
                && !manualId.equals(PayloadStore.BUNDLED_PAYLOAD_ID)) {
            activePayloadId = manualId;
            activeProfile = new PayloadStore.Profile(manualId, "",
                    java.util.Arrays.asList(PayloadStore.deviceModel()),
                    new java.util.ArrayList<>(), "", -1);
            engine.setPayloadOverride(cached);
            File cachedKsud = PayloadStore.cachedKsud(this, manualId);
            if (cachedKsud.isFile()) {
                engine.setKsudOverride(cachedKsud, -1);
            }
            append("Using cached payload " + manualId + ".");
            return;
        }
        append("Using bundled payload " + PayloadStore.BUNDLED_PAYLOAD_ID + ".");
        activePayloadId = PayloadStore.BUNDLED_PAYLOAD_ID;
        activeProfile = null;
        engine.setKsudOverride(null, -1);
        engine.setKmiOverride(null);
    }

    private CharSequence buildPayloadButtonLabel() {
        String prefix = "Payload selected : ";
        String value = activePayloadId;

        if (!deviceSupported()) {
            prefix = "Payload : ";
            value = "none for this device";
        }

        SpannableString label = new SpannableString(prefix + value);
        label.setSpan(new android.text.style.AbsoluteSizeSpan(12, true), 0,
                prefix.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new android.text.style.AbsoluteSizeSpan(10, true),
                prefix.length(), label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return label;
    }
    private void showPayloadDialog() {
        List<PayloadStore.Profile> registry = lastRegistry;
        if (registry == null) {
            append("Fetching payload registry for selection ...");
            worker.execute(() -> {
                try {
                    lastRegistry = PayloadStore.fetchRegistry();
                } catch (Exception error) {
                    append("Payload registry unavailable: " + error.getMessage());
                    return;
                }
                ui.post(this::showPayloadDialog);
            });
            return;
        }
        SharedPreferences prefs = getSharedPreferences("samsu_payload", MODE_PRIVATE);
        String manualId = prefs.getString("manual_payload_id", "");
        String model = PayloadStore.deviceModel();
        List<PayloadStore.Profile> options = new ArrayList<>();
        options.add(PayloadStore.bundledProfile());
        List<PayloadStore.Profile> deviceMatches = new ArrayList<>();
        for (PayloadStore.Profile profile : registry) {
            if (!profile.payloadId.equals(PayloadStore.BUNDLED_PAYLOAD_ID)
                    && profile.models.contains(model)) {
                deviceMatches.add(profile);
            }
        }
        deviceMatches.sort((a, b) -> Boolean.compare(
                PayloadStore.kernelMatches(b), PayloadStore.kernelMatches(a)));
        for (PayloadStore.Profile profile : deviceMatches) {
            if (options.size() >= 3) break;
            options.add(profile);
        }
                float density = getResources().getDisplayMetrics().density;
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * density);
        list.setPadding(pad, pad / 2, pad, pad / 2);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Select payload")
                .setView(list);
        androidx.appcompat.app.AlertDialog dialog = builder.show();
        android.view.Window popupWindow = dialog.getWindow();
        if (popupWindow != null) {
            android.view.WindowManager.LayoutParams popupParams = popupWindow.getAttributes();
            popupParams.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            popupParams.y = (int) (150 * getResources().getDisplayMetrics().density);
            popupWindow.setAttributes(popupParams);
        }
        for (int index = 0; index < options.size(); index++) {
            PayloadStore.Profile option = options.get(index);
            boolean selected = option.payloadId.equals(
                    manualId.isEmpty() ? PayloadStore.BUNDLED_PAYLOAD_ID : manualId);
            boolean bundled = option.payloadId.equals(PayloadStore.BUNDLED_PAYLOAD_ID);
            TextView row = new TextView(this);
            row.setText((selected ? "Selected:  " : "") + (bundled ? "Bundled: " : "")
                    + option.payloadId
                    + (option.displayName.isEmpty() || bundled
                            ? "" : "  -  " + option.displayName)
                    + (bundled || PayloadStore.kernelMatches(option)
                            ? "" : "  (kernel mismatch)"));
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            row.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            row.setTextColor(selected ? 0xFFFFFFFF : 0xFFB9B9B9);
            int padV = (int) (11 * density);
            row.setPadding(pad, padV, pad, padV);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                prefs.edit().putString("manual_payload_id",
                        PayloadStore.BUNDLED_PAYLOAD_ID.equals(option.payloadId)
                                ? "" : option.payloadId).apply();
                payloadResolved = false;
                append("Payload selection: " + option.payloadId);
                worker.execute(() -> {
                    resolvePayload();
                    refreshRootState();
                });
            });
            list.addView(row);
        }
        dialog.show();
    }

    private void toggleDiagnostics() {
        diagnosticsVisible = !diagnosticsVisible;
        diagnosticsCard.setVisibility(diagnosticsVisible ? View.VISIBLE : View.GONE);
        diagnosticsToggle.setText(diagnosticsVisible
                ? R.string.hide_diagnostics : R.string.show_diagnostics);
        if (diagnosticsVisible) {
            scrollLogToBottom();
        }
    }

    private void bindHoldAction(MaterialButton button, String label, long holdMillis, Runnable action) {
        final Handler holdHandler = new Handler(Looper.getMainLooper());
        final AtomicBoolean fired = new AtomicBoolean();
        final Runnable fire = () -> {
            fired.set(true);
            button.animate().alpha(1f).setDuration(150).start();
            append(label + " triggered");
            action.run();
        };
        button.setOnTouchListener((view, event) -> {
            if (!button.isEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    fired.set(false);
                    button.animate().alpha(0.35f).setDuration(holdMillis).start();
                    holdHandler.postDelayed(fire, holdMillis);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    holdHandler.removeCallbacks(fire);
                    if (!fired.get()) {
                        button.animate().alpha(1f).setDuration(150).start();
                        append("Hold " + label.toLowerCase() + " for 1.4 seconds to trigger.");
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    private void onRunClicked() {
        if (!running.compareAndSet(false, true)) return;
        worker.execute(() -> {
            M3qRootEngine.RootState current = engine.checkRoot(false);
            resolvePayload();
            if (current.terminationUnconfirmed()) {
                finishUnconfirmedRun();
                return;
            }
            if (current.ready()) {
                finishRun(current);
                return;
            }
            if (current.bootstrap()) {
                append("Bootstrap root detected - finishing KernelSU activation without re-running the exploit.");
                setStatus("Activating KernelSU", STATUS_WORKING);
                setStatusDetail("Finalizes KernelSU setup without repeating kernel writes.");
                ui.post(this::lockUiForRun);
                int code = engine.activateKernelSu();
                append("KernelSU activation exit=" + code);
                if (code == M3qRootEngine.EXIT_TERMINATION_UNCONFIRMED) {
                    finishUnconfirmedRun();
                    return;
                }
                finishRun(engine.checkRoot(true));
                return;
            }
            if (engine.hasAttemptedThisBoot()) {
                running.set(false);
                ui.post(() -> {
                    append("Blocked a retry with the same boot ID.");
                    renderRootState(current);
                });
                return;
            }
            running.set(false);
            ui.post(this::startExploit);
        });
    }

    private void startExploit() {
        if (!running.compareAndSet(false, true)) return;
        lockUiForRun();
        append("==== fresh-root start ====");

        if (ShizukuShell.isRunning()) {
            int uid = ShizukuShell.uid();
            append("Shizuku detected: uid=" + uid + " · tracefs fast path");
            if (!ShizukuShell.isGranted()) {
                setStatus("Shizuku permission required", STATUS_WORKING);
                setStatusDetail("Approve the Shizuku permission prompt shown.");
                try {
                    shizukuPermissionPending.set(true);
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);
                } catch (RuntimeException error) {
                    shizukuPermissionPending.set(false);
                    abortPendingRun("Shizuku permission request error: " + error.getMessage());
                }
                return;
            }
            beginExploit(true);
            return;
        }

        append("Shizuku is not running; using the exact-Image physical-P0 fallback route.");
        beginExploit(false);
    }

    private void beginExploit(boolean useShizuku) {
        long settleMillis = M3qRootEngine.bootSettleRemainingMillis();
        if (settleMillis > 0) {
            long settleSeconds = (settleMillis + 999) / 1000;
            abortPendingRun("Right after boot, wait about " + settleSeconds
                    + " seconds and try again. No attempt was consumed this boot.");
            return;
        }
        if (!engine.markAttemptForThisBoot()) {
            abortPendingRun("Boot state unreadable; refused the kernel run.");
            return;
        }
        setStatus("Activating temporary root", STATUS_WORKING);
        setStatusDetail(useShizuku
                ? "Checking safety conditions."
                : "Verifies device security state, then applies temporary root.");
        worker.execute(() -> {
            int code = engine.runFreshRoot(useShizuku);
            append("fresh-root exit=" + code);
            if (code == M3qRootEngine.EXIT_TERMINATION_UNCONFIRMED) {
                finishUnconfirmedRun();
                return;
            }
            finishRun(engine.checkRoot(true));
        });
    }

    private void startModuleReload() {
        if (!running.compareAndSet(false, true)) return;
        lockUiForRun();
        setStatus("Reloading KernelSU module", STATUS_WORKING);
        setStatusDetail("Re-running the KernelSU module start step.");
        append("==== KernelSU module reapply start ====");
        worker.execute(() -> {
            M3qRootEngine.RootState state = engine.checkRoot(false);
            if (!state.ready()) {
                append("KernelSU temporary root not active; nothing was run.");
                finishMaintenance(126, state, "", "");
                return;
            }
            int code = engine.reapplyKernelSuModules();
            append("module reapply exit=" + code);
            if (code == M3qRootEngine.EXIT_TERMINATION_UNCONFIRMED) {
                finishUnconfirmedRun();
                return;
            }
            finishMaintenance(code, engine.checkRoot(false),
                    "Module reload complete",
                    "KernelSU module reapplied. Now proceed with the soft boot.");
        });
    }

    private void startSoftBoot() {
        if (!running.compareAndSet(false, true)) return;
        lockUiForRun();
        setStatus("Preparing soft reboot", STATUS_WORKING);
        setStatusDetail("Restarts the Android app runtime (Zygote).");
        append("Requesting a Zygote restart. If it succeeds, this app closes too.");
        worker.execute(() -> {
            M3qRootEngine.RootState state = engine.checkRoot(false);
            if (!state.ready()) {
                append("KernelSU temporary root not active; nothing was run.");
                finishMaintenance(126, state, "", "");
                return;
            }
            int code = engine.restartZygote();
            append("zygote restart exit=" + code);
            if (code == M3qRootEngine.EXIT_TERMINATION_UNCONFIRMED) {
                finishUnconfirmedRun();
                return;
            }
            finishMaintenance(code, engine.checkRoot(false),
                    "Soft reboot requested",
                    "Check active status in the LSPosed manager shortly.");
        });
    }

    private void startUnrootReboot() {
        startRebootFlow("Rebooting to unroot",
                "Device is restarting; root will be cleared.");
    }

    private void startRebootOnFail() {
        startRebootFlow("Rebooting",
                "Device is restarting; boot counter will be cleared.");
    }

    private void startRebootFlow(String workingTitle, String successDetail) {
        if (!running.compareAndSet(false, true)) return;
        lockUiForRun();
        setStatus(workingTitle, STATUS_WORKING);
        setStatusDetail("Device is rebooting.");
        worker.execute(() -> {
            boolean rootReady = engine.checkRoot(false).ready();
            int code;
            String route;
            if (rootReady) {
                route = "KernelSU root shell";
                append("Requesting device reboot via KernelSU root shell.");
                code = engine.rebootDevice();
                if (code != 0 && code != 124) {
                    if (ShizukuShell.isRunning() && ShizukuShell.isGranted()) {
                        append("Root reboot failed (code " + code
                                + "); falling back to the Shizuku shell.");
                        route = "Shizuku shell";
                        code = rebootViaShizuku();
                    }
                }
            } else if (ShizukuShell.isRunning() && ShizukuShell.isGranted()) {
                route = "Shizuku shell";
                append("Requesting device reboot via Shizuku shell.");
                code = rebootViaShizuku();
            } else {
                route = "none";
                code = 159;
                append("No root and Shizuku is not connected; cannot reboot.");
            }
            append("reboot exit=" + code + " via " + route);
            running.set(false);
            final int exitCode = code;
            final String usedRoute = route;
            ui.post(() -> {
                statusRefresh.setEnabled(true);
                if (exitCode == 0 || exitCode == 124) {
                    setStatus("Rebooting", STATUS_SUCCESS);
                    setStatusDetail(successDetail);
                } else if (usedRoute.equals("Shizuku shell")) {
                    renderRootState(engine.checkRoot(false));
                    setStatus("Reboot failed", STATUS_WARNING);
                    setStatusDetail("Reboot refused, Shizuku is not running");
                } else if (usedRoute.equals("none")) {
                    renderRootState(engine.checkRoot(false));
                    setStatus("Reboot failed", STATUS_WARNING);
                    setStatusDetail("No root or Shizuku available to reboot.");
                } else {
                    renderRootState(engine.checkRoot(false));
                    setStatus("Reboot failed", STATUS_WARNING);
                    setStatusDetail("Reboot was refused (code " + exitCode + ").");
                }
            });
        });
    }

    private int rebootViaShizuku() {
        Process process = ShizukuShell.exec(new String[]{"reboot"}, null, null);
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 124;
        }
    }
    private void finishMaintenance(int code, M3qRootEngine.RootState state,
                                   String successText, String successDetail) {
        running.set(false);
        ui.post(() -> {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            statusRefresh.setEnabled(true);
            renderRootState(state);
            if (code == 0) {
                setStatus(successText, STATUS_SUCCESS);
                setStatusDetail(successDetail);
                return;
            }
            String reason = switch (code) {
                case 124 -> "Could not confirm job completion.";
                case 125 -> "KernelSU configuration verification failed.";
                case 126 -> "KernelSU root permission required.";
                default -> "Command failed. code=" + code;
            };
            setStatus("Job failed", STATUS_WARNING);
            setStatusDetail(reason + " Check the status again.");
        });
    }

    private void lockUiForRun() {
        run.setEnabled(false);
        reapplyModules.setEnabled(false);
        restartZygote.setEnabled(false);
            unrootReboot.setEnabled(false);
        statusRefresh.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void abortPendingRun(String message) {
        append(message);
        running.set(false);
        ui.post(() -> {
            run.setVisibility(View.VISIBLE);
            run.setEnabled(deviceSupported());
            reapplyModules.setEnabled(false);
            restartZygote.setEnabled(false);
            unrootReboot.setEnabled(false);
            statusRefresh.setEnabled(true);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setStatus("Wait 180 seconds", STATUS_NEUTRAL);
            setStatusDetail("Device was freshly booted, wait for idle.");
        });
    }

    private void refreshRootState() {
        M3qRootEngine.RootState state = engine.checkRoot(false);
        ui.post(() -> renderRootState(state));
    }

    private void renderRootState(M3qRootEngine.RootState state) {
        runIsReboot = false;
        if (state.terminationUnconfirmed()) {
            run.setVisibility(View.VISIBLE);
            setStatus("Job status unknown", STATUS_WARNING);
            setStatusDetail("For safety, reboot the device and check again.");
            run.setText(R.string.run_reboot_check);
            run.setEnabled(false);
        } else if (state.ready()) {
            setStatus("Temporary root active", STATUS_SUCCESS);
            setStatusDetail("ADBsu root bridge loaded");
            run.setVisibility(View.GONE);
        } else if (state.bootstrap()) {
            run.setVisibility(View.VISIBLE);
            setStatus("Root ready", STATUS_WORKING);
            setStatusDetail("Only the KernelSU activation step remains.");
            run.setText(R.string.run_kernel_su_activate);
            run.setEnabled(true);
        } else if (engine.hasAttemptedThisBoot()) {
            run.setVisibility(View.VISIBLE);
            setStatus("Failed", STATUS_WARNING);
            setStatusDetail("Kernel panic prevented, reboot required");
            run.setText(R.string.run_reboot_retry);
            runIsReboot = true;
            run.setEnabled(true);
        } else {
            run.setVisibility(View.VISIBLE);
            setStatus("Temporary root inactive", STATUS_NEUTRAL);
            setStatusDetail(deviceSupported()
                    ? "Device verified - Wait 180s after boot"
                    : "Wait 180s after boot");
            run.setText(R.string.root_activate);
            run.setEnabled(deviceSupported());
        }
        boolean ksuOk = ksuManagerVersionOk();
        boolean maintenanceReady = state.ready() && !running.get() && ksuOk;
        reapplyModules.setEnabled(maintenanceReady);
        restartZygote.setEnabled(maintenanceReady);
        unrootReboot.setEnabled(state.ready() && !running.get());
        statusRefresh.setEnabled(!running.get());
        renderDashboard(state);
    }


    private boolean ksuManagerVersionOk() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(
                    KSU_MANAGER_PACKAGE, PackageManager.PackageInfoFlags.of(0));
            return normalizeKsuVersion(info.versionName).startsWith("3.2.5");
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String ksuManagerLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(
                    KSU_MANAGER_PACKAGE, PackageManager.PackageInfoFlags.of(0));
            String version = normalizeKsuVersion(info.versionName);
            return version.startsWith("3.2.5") ? version + " \u2713" : "<font color=#FFB4AB>" + version + " \u2717 needs 3.2.5</font>";
        } catch (PackageManager.NameNotFoundException e) {
            return "Not installed";
        }
    }

    private static String normalizeKsuVersion(String versionName) {
        if (versionName == null) return "unknown";
        return versionName.replaceFirst("^[vV]", "").trim();
    }

    private void renderDashboard(M3qRootEngine.RootState state) {
        boolean shizukuRunning = ShizukuShell.isRunning();
        boolean shizukuGranted = ShizukuShell.isGranted();
        int shizukuUid = ShizukuShell.uid();
        String shizuku = !shizukuRunning ? "<font color=#FFB4AB>Not connected</font>"
                : !shizukuGranted ? "Permission required"
                : (shizukuUid == 2000 || shizukuUid == 0)
                ? "Connected" : "Permission limited";
        String attempted = engine.hasAttemptedThisBoot()
                ? (state.ready() ? "Rooted" : "Spent")
                : "Clean";
                dashboard.setText(Html.fromHtml(getString(R.string.dashboard_format,
                ksuManagerLabel(), shizuku, attempted), Html.FROM_HTML_MODE_LEGACY));
        payloadButton.setText(buildPayloadButtonLabel());
    }

    private void finishRun(M3qRootEngine.RootState state) {
        if (state.terminationUnconfirmed()) {
            finishUnconfirmedRun();
            return;
        }
        running.set(false);
        ui.post(() -> {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            statusRefresh.setEnabled(true);
            if (state.ready()) {
                setStatus("Temporary root active", STATUS_SUCCESS);
                setStatusDetail("ADBsu root bridge loaded");
                run.setVisibility(View.GONE);
                openPackage(KSU_MANAGER_PACKAGE,
                        "KernelSU Manager is not installed.");
            } else if (state.bootstrap()) {
                run.setVisibility(View.VISIBLE);
                setStatus("Root ready", STATUS_WORKING);
                setStatusDetail("You can retry KernelSU activation.");
                run.setText(R.string.run_kernel_su_reactivate);
                run.setEnabled(true);
            } else {
                run.setVisibility(View.VISIBLE);
                setStatus("Failed", STATUS_WARNING);
                setStatusDetail("Kernel panic prevented, reboot required");
                run.setText(R.string.run_reboot_retry);
                runIsReboot = true;
                run.setEnabled(true);
            }
            reapplyModules.setEnabled(state.ready());
            restartZygote.setEnabled(state.ready());
            renderDashboard(state);
        });
    }

    private void finishUnconfirmedRun() {
        running.set(false);
        append("Process control was lost and exit could not be proven. Do not retry before rebooting.");
        ui.post(() -> {
            run.setVisibility(View.VISIBLE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setStatus("Job status unknown", STATUS_WARNING);
            setStatusDetail("Do not run the same job again before rebooting.");
            run.setText(R.string.run_reboot_check);
            run.setEnabled(false);
            reapplyModules.setEnabled(false);
            restartZygote.setEnabled(false);
            unrootReboot.setEnabled(false);
            statusRefresh.setEnabled(true);
        });
    }

    private void openPackage(String packageName, String missingMessage) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            append(missingMessage);
            setStatus("Cannot open the manager app", STATUS_NEUTRAL);
            setStatusDetail(missingMessage);
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private void exportLastLog() {
        File file = engine.lastRootLog();
        if (!file.isFile()) {
            append("No run log to share yet.");
            setStatus("No diagnostic report", STATUS_NEUTRAL);
            setStatusDetail("Run Hold to root once to generate a report.");
            return;
        }
        try {
            String text = LogRedactor.redact(readLogTail(file));
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
                    .putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(share, getString(R.string.share_chooser)));
        } catch (IOException error) {
            append("Failed to read log: " + error.getMessage());
        }
    }

    private String readLogTail(File file) throws IOException {
        final int limit = 64 * 1024;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long skipped = Math.max(0, input.length() - limit);
            input.seek(skipped);
            byte[] bytes = new byte[(int) Math.min(limit, input.length())];
            input.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (skipped == 0) return text;
            return "[Head and truncated first lines omitted]\n"
                    + LogRedactor.dropPartialFirstLine(text);
        }
    }

    private void setStatus(String text, int semanticColor) {
        ui.post(() -> {
            int color = resolveStatusColor(semanticColor);
            status.setText(text);
            status.setTextColor(color);
            statusCard.setStrokeColor(color);
        });
    }

    private int resolveStatusColor(int semanticColor) {
        if (semanticColor == STATUS_SUCCESS) return getColor(R.color.m3q_success);
        if (semanticColor == STATUS_WORKING) return getColor(R.color.m3q_warning);
        if (semanticColor == STATUS_WARNING) return getColor(R.color.m3q_error);
        return getColor(R.color.m3q_neutral);
    }

    private void setStatusDetail(String text) {
        ui.post(() -> statusDetail.setText(text));
    }

    private void append(String line) {
        ui.post(() -> {
            log.append(line + "\n");
            if (diagnosticsVisible) {
                scrollLogToBottom();
            }
        });
    }

    private void scrollLogToBottom() {
        log.post(() -> {
            if (log.getLayout() == null) return;
            int scroll = log.getLayout().getLineTop(log.getLineCount())
                    - log.getHeight();
            log.scrollTo(0, Math.max(0, scroll));
        });
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        worker.shutdown();
        super.onDestroy();
    }
}
