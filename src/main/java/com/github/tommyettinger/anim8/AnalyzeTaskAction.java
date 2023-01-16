package com.github.tommyettinger.anim8;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;

import java.util.concurrent.RecursiveAction;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AnalyzeTaskAction extends RecursiveAction {

    private final int left;
    private final int right;
    private final Array<Pixmap> frames;
    static Array<AnalyzedPixmap> analyzedPixmapArrayFromTasks = new Array<>();
    static Integer seq = 0;

    public AnalyzeTaskAction(int left, int right, Array<Pixmap> frames) {
        this.left = left;
        this.right = right;
        this.frames = frames;
    }

    @Override
    protected void compute() {
        if (right - left == 1) {
            Logger.getGlobal().log(Level.INFO, "Analyze: Compute on: " + Thread.currentThread().getName());
            Logger.getGlobal().log(Level.INFO, "Analyze: SEQ: " + seq);
            AnalyzedPixmap analyzedPixmap = new AnalyzePixels(seq, frames.get(0)).analyzePixels();
            ++seq;
            analyzedPixmapArrayFromTasks.add(analyzedPixmap);
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
            AnalyzeTaskAction taskLeft = new AnalyzeTaskAction(0, arrLeft.size, arrLeft);
            AnalyzeTaskAction taskRight = new AnalyzeTaskAction(0, arrRight.size, arrRight);
            taskLeft.compute();
            taskRight.compute();
        }
    }

    protected void clearAnalyzedPixmapArrayAndSeqFromTasks() {
        analyzedPixmapArrayFromTasks.clear();
        seq = 0;
    }

    protected Array<AnalyzedPixmap> getAnalyzedPixmapArrayFromTasks(){
        return analyzedPixmapArrayFromTasks;
    }
}
