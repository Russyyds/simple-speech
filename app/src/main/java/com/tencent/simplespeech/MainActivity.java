package com.tencent.simplespeech;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final int MAX_TOKENS = 4096;
    private static final String STATS_MARKER = "\n\n<|simple_speech_stats|>";

    private static final String[] TRANSLATION_MODELS = {
            "Hy-MT1.5-1.8B-1.25bit",
            "Hy-MT1.5-1.8B-4bit",
            "Hy-MT1.5-1.8B-8bit",
            "Hy-MT1.5-1.8B-2bit"
    };
    private static final String[] ASR_MODELS = {"Fun-ASR-Nano-0.8B"};
    private static final String[] TTS_MODELS = {"Fun-CosyVoice3-0.5B"};
    private static final String[] LANGUAGE_PROMPTS = {
            "Chinese",
            "English",
            "Japanese",
            "Korean",
            "French",
            "German",
            "Spanish"
    };
    private static final String[] LANGUAGE_LABELS = {
            "中文",
            "英语",
            "日语",
            "韩语",
            "法语",
            "德语",
            "西班牙语"
    };

    private final int accent = Color.rgb(16, 163, 127);
    private final int bg = Color.rgb(247, 247, 245);
    private final int panel = Color.WHITE;
    private final int text = Color.rgb(31, 35, 40);
    private final int muted = Color.rgb(104, 112, 118);

    private LinearLayout root;
    private FrameLayout content;
    private Button translateTab;
    private Button asrTab;
    private Button ttsTab;
    private String modelRoot;
    private String internalModelRoot;
    private String externalModelRoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        internalModelRoot = new File(getFilesDir(), "models").getAbsolutePath();
        externalModelRoot = new File(getExternalFilesDir(null), "models").getAbsolutePath();
        modelRoot = internalModelRoot;
        buildShell();
        showTranslate();
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(14), dp(12), dp(14), 0);

        TextView title = new TextView(this);
        title.setText("Simple Speech");
        title.setTextColor(text);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(36)));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(0, dp(6), 0, dp(6));
        nav.setBackgroundColor(panel);

        translateTab = navButton("翻译");
        asrTab = navButton("ASR");
        ttsTab = navButton("TTS");
        nav.addView(translateTab, new LinearLayout.LayoutParams(0, dp(46), 1f));
        nav.addView(asrTab, new LinearLayout.LayoutParams(0, dp(46), 1f));
        nav.addView(ttsTab, new LinearLayout.LayoutParams(0, dp(46), 1f));

        translateTab.setOnClickListener(v -> showTranslate());
        asrTab.setOnClickListener(v -> showAsr());
        ttsTab.setOnClickListener(v -> showTts());

        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(58)));
        setContentView(root);
    }

    private Button navButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(muted);
        b.setBackgroundColor(panel);
        return b;
    }

    private void activate(Button selected) {
        Button[] tabs = {translateTab, asrTab, ttsTab};
        for (Button tab : tabs) {
            boolean active = tab == selected;
            tab.setTextColor(active ? accent : muted);
            tab.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void showTranslate() {
        activate(translateTab);
        LinearLayout page = page();

        Spinner model = spinner(TRANSLATION_MODELS);
        model.setSelection(0);
        page.addView(label("翻译模型"));
        page.addView(model, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout langRow = new LinearLayout(this);
        langRow.setOrientation(LinearLayout.HORIZONTAL);
        langRow.setGravity(Gravity.CENTER_VERTICAL);
        Spinner source = spinner(LANGUAGE_LABELS);
        Spinner target = spinner(LANGUAGE_LABELS);
        source.setSelection(1);
        target.setSelection(0);

        Button swap = secondaryButton("⇄");
        swap.setOnClickListener(v -> {
            int sourceIndex = source.getSelectedItemPosition();
            source.setSelection(target.getSelectedItemPosition());
            target.setSelection(sourceIndex);
        });

        langRow.addView(labelAndControl("源语言", source, 24, 42), new LinearLayout.LayoutParams(0, dp(66), 1f));
        langRow.addView(swap, new LinearLayout.LayoutParams(dp(48), dp(42)));
        langRow.addView(labelAndControl("目标语言", target, 24, 42), new LinearLayout.LayoutParams(0, dp(66), 1f));
        page.addView(langRow);

        LinearLayout settingsRow = new LinearLayout(this);
        settingsRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText maxTokens = edit(String.valueOf(DEFAULT_MAX_TOKENS), false);
        maxTokens.setSingleLine(true);
        maxTokens.setText(String.valueOf(DEFAULT_MAX_TOKENS));
        maxTokens.setInputType(InputType.TYPE_CLASS_NUMBER);
        settingsRow.addView(labelAndControl("最大生成 token（上限 4096）", maxTokens, 22, 40),
                new LinearLayout.LayoutParams(0, dp(62), 1.3f));

        CheckBox useGpu = new CheckBox(this);
        useGpu.setText("使用 GPU");
        useGpu.setTextColor(text);
        useGpu.setTextSize(15);
        useGpu.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
        useGpu.setPadding(dp(2), 0, dp(2), 0);
        useGpu.setChecked(false);
        settingsRow.addView(labelAndControl("推理后端", useGpu, 22, 40),
                new LinearLayout.LayoutParams(0, dp(62), 1f));
        page.addView(settingsRow);

        EditText input = edit("输入要翻译的文本", true);
        page.addView(textPanel("输入文本", input, () -> copyText("输入文本", input.getText().toString())),
                new LinearLayout.LayoutParams(-1, 0, 1.8f));

        Button run = primaryButton("翻译");
        page.addView(run, new LinearLayout.LayoutParams(-1, dp(46)));

        TextView result = resultBox("翻译结果会显示在这里");
        page.addView(textPanel("翻译结果", result, () -> copyText("翻译结果", result.getText().toString())),
                new LinearLayout.LayoutParams(-1, 0, 1.65f));

        TextView speed = footnote("速度：prefill -- tok/s，decode -- tok/s");
        page.addView(speed, new LinearLayout.LayoutParams(-1, dp(30)));

        run.setOnClickListener(v -> {
            hideKeyboard(input);
            String textIn = input.getText().toString().trim();
            if (textIn.isEmpty()) {
                Toast.makeText(this, "请输入文本", Toast.LENGTH_SHORT).show();
                return;
            }
            int maxPredictTokens = parseMaxTokens(maxTokens.getText().toString());
            maxTokens.setText(String.valueOf(maxPredictTokens));
            boolean useGpuBackend = useGpu.isChecked();
            result.setText("正在翻译...");
            speed.setText("速度：prefill -- tok/s，decode -- tok/s");
            new Thread(() -> {
                String selectedModel = model.getSelectedItem().toString();
                String out = NativeSpeechEngine.translate(
                        effectiveModelRoot(selectedModel),
                        getApplicationInfo().nativeLibraryDir,
                        selectedModel,
                        LANGUAGE_PROMPTS[source.getSelectedItemPosition()],
                        LANGUAGE_PROMPTS[target.getSelectedItemPosition()],
                        textIn,
                        useGpuBackend,
                        maxPredictTokens);
                TranslationOutput parsed = parseTranslationOutput(out);
                runOnUiThread(() -> {
                    result.setText(parsed.text);
                    speed.setText(parsed.speed);
                });
            }).start();
        });

        setPage(page);
    }

    private void showAsr() {
        activate(asrTab);
        LinearLayout page = page();
        page.addView(label("ASR 模型"));
        page.addView(spinner(ASR_MODELS), new LinearLayout.LayoutParams(-1, dp(42)));

        TextView result = resultBox("识别结果会显示在这里");
        page.addView(textPanel("识别结果", result, () -> copyText("识别结果", result.getText().toString())),
                new LinearLayout.LayoutParams(-1, 0, 1f));

        Button mic = primaryButton("按住说话");
        page.addView(mic, new LinearLayout.LayoutParams(-1, dp(50)));
        page.addView(footnote("Fun-ASR-Nano-0.8B 模型暂未放入设备。请将 GGUF 模型放到 " + modelRoot + "/Fun-ASR-Nano-0.8B"));

        mic.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
                return;
            }
            result.setText("ASR 原生推理接口已预留，当前缺少 Fun-ASR-Nano-0.8B GGUF 模型。");
        });
        setPage(page);
    }

    private void showTts() {
        activate(ttsTab);
        LinearLayout page = page();
        page.addView(label("TTS 模型"));
        page.addView(spinner(TTS_MODELS), new LinearLayout.LayoutParams(-1, dp(42)));

        TextView player = resultBox("生成的语音会显示在这里并提供播放入口");
        page.addView(textPanel("生成语音", player, () -> copyText("生成内容", player.getText().toString())),
                new LinearLayout.LayoutParams(-1, 0, 1f));

        EditText input = edit("输入要合成的文本", false);
        page.addView(textPanel("输入文本", input, () -> copyText("输入文本", input.getText().toString())),
                new LinearLayout.LayoutParams(-1, dp(112)));

        Button synth = primaryButton("生成语音");
        page.addView(synth, new LinearLayout.LayoutParams(-1, dp(50)));
        page.addView(footnote("Fun-CosyVoice3-0.5B 模型暂未放入设备。请将 GGUF 模型放到 " + modelRoot + "/Fun-CosyVoice3-0.5B"));

        synth.setOnClickListener(v -> player.setText("TTS 原生推理接口已预留，当前缺少 Fun-CosyVoice3-0.5B GGUF 模型。"));
        setPage(page);
    }

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(6), 0, dp(6));
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        return page;
    }

    private void setPage(View page) {
        content.removeAllViews();
        content.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackgroundColor(panel);
        return spinner;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(muted);
        t.setGravity(Gravity.BOTTOM | Gravity.START);
        t.setPadding(dp(2), dp(4), dp(2), dp(2));
        return t;
    }

    private LinearLayout labelAndControl(String s, View control, int labelHeight, int controlHeight) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label(s), new LinearLayout.LayoutParams(-1, dp(labelHeight)));
        box.addView(control, new LinearLayout.LayoutParams(-1, dp(controlHeight)));
        return box;
    }

    private LinearLayout textPanel(String title, View body, Runnable copyAction) {
        LinearLayout panelBox = new LinearLayout(this);
        panelBox.setOrientation(LinearLayout.VERTICAL);
        panelBox.setPadding(0, dp(4), 0, dp(4));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = label(title);
        header.addView(label, new LinearLayout.LayoutParams(0, dp(28), 1f));
        Button copy = secondaryButton("⧉");
        copy.setOnClickListener(v -> copyAction.run());
        header.addView(copy, new LinearLayout.LayoutParams(dp(44), dp(34)));

        panelBox.addView(header, new LinearLayout.LayoutParams(-1, dp(36)));
        panelBox.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        return panelBox;
    }

    private EditText edit(String hint, boolean large) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(text);
        e.setHintTextColor(muted);
        e.setTextSize(16);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setMinLines(large ? 8 : 2);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setBackgroundColor(panel);
        return e;
    }

    private TextView resultBox(String hint) {
        TextView t = new TextView(this);
        t.setText(hint);
        t.setTextColor(text);
        t.setTextSize(16);
        t.setGravity(Gravity.TOP | Gravity.START);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setBackgroundColor(panel);
        t.setTextIsSelectable(true);
        return t;
    }

    private Button primaryButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackgroundColor(accent);
        return b;
    }

    private Button secondaryButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(accent);
        b.setTextSize(s.length() <= 1 ? 20 : 14);
        b.setBackgroundColor(panel);
        return b;
    }

    private TextView footnote(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(muted);
        t.setPadding(dp(2), dp(4), dp(2), 0);
        return t;
    }

    private String statusText() {
        return "模型目录: " + modelRoot
                + "\nllama.cpp: 已启用"
                + "\nAdreno OpenCL: 已启用（非 1.25bit 模型可用）"
                + "\n内部模型目录: " + internalModelRoot
                + "\n外部备用目录: " + externalModelRoot;
    }

    private String effectiveModelRoot(String modelName) {
        if (hasModelFile(internalModelRoot, modelName)) {
            return internalModelRoot;
        }
        if (hasModelFile(externalModelRoot, modelName)) {
            return externalModelRoot;
        }
        return internalModelRoot;
    }

    private boolean hasModelFile(String root, String modelName) {
        String[] candidates;
        if ("Hy-MT1.5-1.8B-1.25bit".equals(modelName)) {
            candidates = new String[]{"Hy-MT1.5-1.8B-1.25bit", "Hy-MT1.5-1.8B-1.25bit-GGUF"};
        } else if ("Hy-MT1.5-1.8B-4bit".equals(modelName)) {
            candidates = new String[]{"Hy-MT1.5-1.8B-4bit", "Hy-MT1.5-1.8B-1.25bit-GGUF"};
        } else if ("Hy-MT1.5-1.8B-8bit".equals(modelName)) {
            candidates = new String[]{"Hy-MT1.5-1.8B-8bit", "Hy-MT1.5-1.8B-1.25bit-GGUF"};
        } else if ("Hy-MT1.5-1.8B-2bit".equals(modelName)) {
            candidates = new String[]{"Hy-MT1.5-1.8B-2bit", "Hy-MT1.5-1.8B-2bit-GGUF"};
        } else {
            candidates = new String[]{modelName};
        }
        for (String candidate : candidates) {
            File dir = new File(root, candidate);
            File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.US).endsWith(".gguf"));
            if (files != null && files.length > 0) {
                return true;
            }
        }
        return false;
    }

    private int parseMaxTokens(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(1, Math.min(MAX_TOKENS, parsed));
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_TOKENS;
        }
    }

    private TranslationOutput parseTranslationOutput(String raw) {
        int marker = raw.indexOf(STATS_MARKER);
        if (marker < 0) {
            return new TranslationOutput(raw, "速度：prefill -- tok/s，decode -- tok/s");
        }

        String textPart = raw.substring(0, marker);
        String statsPart = raw.substring(marker + STATS_MARKER.length()).trim();
        double prefill = parseStat(statsPart, "prefill=");
        double decode = parseStat(statsPart, "decode=");
        String speed = String.format(Locale.US, "速度：prefill %.2f tok/s，decode %.2f tok/s", prefill, decode);
        return new TranslationOutput(textPart, speed);
    }

    private double parseStat(String stats, String key) {
        int start = stats.indexOf(key);
        if (start < 0) {
            return 0.0;
        }
        start += key.length();
        int end = stats.indexOf(';', start);
        if (end < 0) {
            end = stats.length();
        }
        try {
            return Double.parseDouble(stats.substring(start, end));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private void copyText(String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            Toast.makeText(this, "没有可复制的内容", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class TranslationOutput {
        final String text;
        final String speed;

        TranslationOutput(String text, String speed) {
            this.text = text;
            this.speed = speed;
        }
    }
}
