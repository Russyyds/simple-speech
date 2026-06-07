package com.tencent.simplespeech;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String[] TRANSLATION_MODELS = {
            "Hy-MT1.5-1.8B-1.25bit",
            "Hy-MT1.5-1.8B-4bit",
            "Hy-MT1.5-1.8B-8bit",
            "Hy-MT1.5-1.8B-2bit"
    };
    private static final String[] ASR_MODELS = {"Fun-ASR-Nano-0.8B"};
    private static final String[] TTS_MODELS = {"Fun-CosyVoice3-0.5B"};
    private static final String[] PROMPT_LANGUAGES = {
            "Chinese",
            "English",
            "Japanese",
            "Korean",
            "French",
            "German",
            "Spanish"
    };
    private static final String[] LANGUAGES = {"中文", "English", "日本語", "한국어", "Français", "Deutsch", "Español"};

    private final int accent = Color.rgb(16, 163, 127);
    private final int bg = Color.rgb(247, 247, 245);
    private final int panel = Color.WHITE;
    private final int text = Color.rgb(31, 35, 40);
    private final int muted = Color.rgb(104, 112, 118);
    private final int border = Color.rgb(218, 221, 225);

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
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(38)));

        content = new FrameLayout(this);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        root.addView(content, contentLp);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(0, dp(8), 0, dp(8));
        nav.setBackgroundColor(panel);

        translateTab = navButton("翻译");
        asrTab = navButton("ASR");
        ttsTab = navButton("TTS");
        nav.addView(translateTab, new LinearLayout.LayoutParams(0, dp(48), 1f));
        nav.addView(asrTab, new LinearLayout.LayoutParams(0, dp(48), 1f));
        nav.addView(ttsTab, new LinearLayout.LayoutParams(0, dp(48), 1f));

        translateTab.setOnClickListener(v -> showTranslate());
        asrTab.setOnClickListener(v -> showAsr());
        ttsTab.setOnClickListener(v -> showTts());

        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(64)));
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
        page.addView(model, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout langRow = new LinearLayout(this);
        langRow.setOrientation(LinearLayout.HORIZONTAL);
        Spinner source = spinner(PROMPT_LANGUAGES);
        Spinner target = spinner(PROMPT_LANGUAGES);
        source.setSelection(1);
        target.setSelection(0);
        langRow.addView(wrapped(labelAndControl("源语言", source)), new LinearLayout.LayoutParams(0, dp(78), 1f));
        langRow.addView(space(dp(10), 1), new LinearLayout.LayoutParams(dp(10), 1));
        langRow.addView(wrapped(labelAndControl("目标语言", target)), new LinearLayout.LayoutParams(0, dp(78), 1f));
        page.addView(langRow);

        EditText maxTokens = edit("64", false);
        maxTokens.setSingleLine(true);
        maxTokens.setText("64");
        maxTokens.setInputType(InputType.TYPE_CLASS_NUMBER);
        page.addView(labelAndControl("Max tokens", maxTokens), new LinearLayout.LayoutParams(-1, dp(78)));

        CheckBox useGpu = new CheckBox(this);
        useGpu.setText("Use GPU");
        useGpu.setTextColor(text);
        useGpu.setTextSize(15);
        useGpu.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
        useGpu.setPadding(dp(2), 0, dp(2), 0);
        useGpu.setChecked(false);
        page.addView(labelAndControl("Backend", useGpu), new LinearLayout.LayoutParams(-1, dp(78)));

        EditText input = edit("输入要翻译的文本", true);
        page.addView(input, new LinearLayout.LayoutParams(-1, 0, 1.15f));

        Button run = primaryButton("翻译");
        page.addView(run, new LinearLayout.LayoutParams(-1, dp(52)));

        TextView result = resultBox("翻译结果会显示在这里");
        page.addView(result, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView status = footnote(statusText());
        page.addView(status);

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
            new Thread(() -> {
                String selectedModel = model.getSelectedItem().toString();
                String out = NativeSpeechEngine.translate(
                        effectiveModelRoot(selectedModel),
                        getApplicationInfo().nativeLibraryDir,
                        selectedModel,
                        source.getSelectedItem().toString(),
                        target.getSelectedItem().toString(),
                        textIn,
                        useGpuBackend,
                        maxPredictTokens);
                runOnUiThread(() -> result.setText(out));
            }).start();
        });

        setPage(page);
    }

    private void showAsr() {
        activate(asrTab);
        LinearLayout page = page();
        page.addView(label("ASR 模型"));
        page.addView(spinner(ASR_MODELS), new LinearLayout.LayoutParams(-1, dp(48)));

        TextView result = resultBox("识别结果会显示在这里");
        page.addView(result, new LinearLayout.LayoutParams(-1, 0, 1f));

        Button mic = primaryButton("按住说话");
        page.addView(mic, new LinearLayout.LayoutParams(-1, dp(56)));
        page.addView(footnote("Fun-ASR-Nano-0.8B 模型暂未放入设备。请把 GGUF 模型放到 " + modelRoot + "/Fun-ASR-Nano-0.8B"));

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
        page.addView(spinner(TTS_MODELS), new LinearLayout.LayoutParams(-1, dp(48)));

        TextView player = resultBox("生成的语音会显示在这里并提供播放入口");
        page.addView(player, new LinearLayout.LayoutParams(-1, 0, 1f));

        EditText input = edit("输入要合成的文本", false);
        page.addView(input, new LinearLayout.LayoutParams(-1, dp(92)));

        Button synth = primaryButton("生成语音");
        page.addView(synth, new LinearLayout.LayoutParams(-1, dp(52)));
        page.addView(footnote("Fun-CosyVoice3-0.5B 模型暂未放入设备。请把 GGUF 模型放到 " + modelRoot + "/Fun-CosyVoice3-0.5B"));

        synth.setOnClickListener(v -> player.setText("TTS 原生推理接口已预留，当前缺少 Fun-CosyVoice3-0.5B GGUF 模型。"));
        setPage(page);
    }

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(8), 0, dp(8));
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
        t.setPadding(dp(2), dp(8), dp(2), dp(4));
        return t;
    }

    private LinearLayout labelAndControl(String s, View control) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label(s), new LinearLayout.LayoutParams(-1, dp(30)));
        box.addView(control, new LinearLayout.LayoutParams(-1, dp(48)));
        return box;
    }

    private View wrapped(View view) {
        return view;
    }

    private View space(int w, int h) {
        View v = new View(this);
        v.setMinimumWidth(w);
        v.setMinimumHeight(h);
        return v;
    }

    private EditText edit(String hint, boolean large) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(text);
        e.setHintTextColor(muted);
        e.setTextSize(16);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setMinLines(large ? 6 : 2);
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

    private TextView footnote(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(muted);
        t.setPadding(dp(2), dp(8), dp(2), 0);
        return t;
    }

    private String statusText() {
        return NativeSpeechEngine.nativeStatus(modelRoot)
                + "\nInternal: " + internalModelRoot
                + "\nExternal fallback: " + externalModelRoot;
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
            return Math.max(1, Math.min(512, parsed));
        } catch (NumberFormatException e) {
            return 64;
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
}
