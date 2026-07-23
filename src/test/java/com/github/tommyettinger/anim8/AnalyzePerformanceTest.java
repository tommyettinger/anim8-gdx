package com.github.tommyettinger.anim8;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.TimeUtils;
import org.junit.Test;

import java.util.concurrent.ForkJoinPool;

public class AnalyzePerformanceTest {

    @Test
    public void testPerformance() {
        // Завантаження нативів
        try {
            Class<?> loader = Class.forName("com.badlogic.gdx.backends.lwjgl3.Lwjgl3NativesLoader");
            loader.getMethod("load").invoke(null);
        } catch (Exception e) {
            System.err.println("Could not load natives: " + e.getMessage());
        }

        int numFrames = 20;
        int width = 256;
        int height = 256;

        System.out.println("Preparing " + numFrames + " frames (" + width + "x" + height + ")...");
        Array<Pixmap> frames = new Array<>();
        for (int i = 0; i < numFrames; i++) {
            Pixmap p = new Pixmap(width, height, Pixmap.Format.RGBA8888);
            p.setColor((float)Math.random(), (float)Math.random(), (float)Math.random(), 1f);
            p.fill();
            frames.add(p);
        }

        System.out.println("--- Starting Benchmark (New Implementation) ---");

        // 1. Original (Sequential)
        runOriginal(frames);

        // 2. New Parallel Implementation
        runNewParallel(frames);

        for (Pixmap p : frames) p.dispose();
    }

    private void runOriginal(Array<Pixmap> frames) {
        long start = TimeUtils.millis();
        Array<AnalyzedPixmap> results = new Array<>();
        PaletteReducer palette = new PaletteReducer(frames.first());
        for (int i = 0; i < frames.size; i++) {
            results.add(new AnalyzePixels(i, frames.get(i), palette, Dithered.DitherAlgorithm.WREN, 1f, true, true).analyzePixels());
        }
        System.out.println("Original (Sequential) took: " + (TimeUtils.millis() - start) + " ms");
    }

    private void runNewParallel(Array<Pixmap> frames) {
        long start = TimeUtils.millis();
        ForkJoinPool pool = new ForkJoinPool();
        PaletteReducer palette = new PaletteReducer(frames.first());
        AnalyzeTaskRecursiveOptimized task = new AnalyzeTaskRecursiveOptimized(0, frames.size, frames, palette, Dithered.DitherAlgorithm.WREN, 1f, true, true);
        pool.invoke(task);
        System.out.println("New Parallel Implementation took: " + (TimeUtils.millis() - start) + " ms");
        pool.shutdown();
    }
}
