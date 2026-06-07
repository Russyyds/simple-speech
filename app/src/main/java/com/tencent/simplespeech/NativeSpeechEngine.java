package com.tencent.simplespeech;

final class NativeSpeechEngine {
    static {
        System.loadLibrary("simple_speech");
    }

    private NativeSpeechEngine() {
    }

    static native String nativeStatus(String modelRoot);

    static native String translate(
            String modelRoot,
            String nativeLibraryDir,
            String modelName,
            String sourceLanguage,
            String targetLanguage,
            String input,
            boolean useGpu,
            int maxPredictTokens);
}
