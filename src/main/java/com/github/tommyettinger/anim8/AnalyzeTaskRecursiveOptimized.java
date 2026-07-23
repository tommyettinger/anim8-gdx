package com.github.tommyettinger.anim8;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;

import java.util.concurrent.RecursiveTask;

/**
 * Оптимізована версія задачі для паралельного аналізу кадрів.
 * Використовує ForkJoinPool для розподілу навантаження між ядрами процесора.
 */
public class AnalyzeTaskRecursiveOptimized extends RecursiveTask<Array<AnalyzedPixmap>> {
    private final int left;
    private final int right;
    private final Array<Pixmap> frames;
    private final PaletteReducer palette;
    private final Dithered.DitherAlgorithm ditherAlgorithm;
    private final float ditherStrength;
    private final boolean fastAnalysis;
    private final boolean flipY;
    
    // Поріг для послідовної обробки. 
    private static final int THRESHOLD = 4;

    public AnalyzeTaskRecursiveOptimized(int left, int right, Array<Pixmap> frames, PaletteReducer palette, Dithered.DitherAlgorithm ditherAlgorithm, float ditherStrength, boolean fastAnalysis, boolean flipY) {
        this.left = left;
        this.right = right;
        this.frames = frames;
        this.palette = palette;
        this.ditherAlgorithm = ditherAlgorithm;
        this.ditherStrength = ditherStrength;
        this.fastAnalysis = fastAnalysis;
        this.flipY = flipY;
    }

    @Override
    protected Array<AnalyzedPixmap> compute() {
        try {
            int size = right - left;
            
            if (size <= THRESHOLD) {
                // Послідовна обробка для невеликої кількості кадрів
                Array<AnalyzedPixmap> results = new Array<>(size);
                for (int i = left; i < right; i++) {
                    results.add(new AnalyzePixels(i, frames.get(i), palette, ditherAlgorithm, ditherStrength, fastAnalysis, flipY).analyzePixels());
                }
                return results;
            } else {
                int middle = left + (size / 2);

                AnalyzeTaskRecursiveOptimized taskLeft = new AnalyzeTaskRecursiveOptimized(left, middle, frames, palette, ditherAlgorithm, ditherStrength, fastAnalysis, flipY);
                AnalyzeTaskRecursiveOptimized taskRight = new AnalyzeTaskRecursiveOptimized(middle, right, frames, palette, ditherAlgorithm, ditherStrength, fastAnalysis, flipY);

                taskLeft.fork();
                Array<AnalyzedPixmap> rightResult = taskRight.compute();
                Array<AnalyzedPixmap> leftResult = taskLeft.join();

                if (leftResult == null || rightResult == null) return null;

                Array<AnalyzedPixmap> combined = new Array<>(leftResult.size + rightResult.size);
                combined.addAll(leftResult);
                combined.addAll(rightResult);
                return combined;
            }
        } catch (Exception e) {
            com.badlogic.gdx.Gdx.app.error("anim8", "Error in AnalyzeTask: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * @deprecated Більше не потрібно, оскільки ми не використовуємо статичні поля.
     */
    @Deprecated
    protected void clearAnalyzedPixmapArrayAndSeqFromTasks() {
        // Залишено для зворотної сумісності, якщо потрібно, але нічого не робить
    }
}
