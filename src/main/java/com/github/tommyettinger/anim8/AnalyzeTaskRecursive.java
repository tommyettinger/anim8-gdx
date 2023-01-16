package com.github.tommyettinger.anim8;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;

import java.util.Arrays;
import java.util.concurrent.RecursiveTask;
import java.util.logging.Level;
import java.util.logging.Logger;

class AnalyzeTaskRecursive extends RecursiveTask<Array<AnalyzedPixmap>> {

    private final int left;
    private final int right;
    private final Array<Pixmap> frames;
    static Array<AnalyzedPixmap> analyzedPixmapArrayFromTasks = new Array<>();
    static Integer seq = 0;

    public AnalyzeTaskRecursive(int left, int right, Array<Pixmap> frames) {
        this.left = left;
        this.right = right;
        this.frames = frames;
    }

    @Override
    protected Array<AnalyzedPixmap> compute() {
        if (right - left == 1) {
            Logger.getGlobal().log(Level.INFO, "Analyze: Compute on: " + Thread.currentThread().getName());
            Logger.getGlobal().log(Level.INFO, "Analyze: SEQ: " + seq);
            AnalyzedPixmap analyzedPixmap = new AnalyzePixels(seq, frames.get(0)).analyzePixels();
            analyzedPixmapArrayFromTasks.add(analyzedPixmap);
            ++seq;
            return analyzedPixmapArrayFromTasks;
        } else {
            int middle = (right + left) / 2;
            Array<Pixmap> arrLeft = new Array<>();
            Array<Pixmap> arrRight = new Array<>();
            for (int i = 0; i < frames.size; i++) {
                if (i < middle) {
                    arrLeft.add(frames.get(i));
                } else {
                    arrRight.add(frames.get(i));
                }
            }

            AnalyzeTaskRecursive taskLeft = new AnalyzeTaskRecursive(0, arrLeft.size, arrLeft);
            AnalyzeTaskRecursive taskRight = new AnalyzeTaskRecursive(0, arrRight.size, arrRight);
            taskLeft.fork();
            taskLeft.join();
            return taskRight.compute();
        }
    }

    protected void clearAnalyzedPixmapArrayAndSeqFromTasks() {
        analyzedPixmapArrayFromTasks.clear();
        seq = 0;
    }
}
