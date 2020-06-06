package com.github.tommyettinger.anim8;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;

import java.io.OutputStream;

/**
 * A common interface for various formats that can write an animated image to a FileHandle or OutputStream.
 * <br>
 * Created by Tommy Ettinger on 6/6/2020.
 */
public interface AnimationWriter {
    void write(FileHandle file, Array<Pixmap> frames);
    void write(FileHandle file, Array<Pixmap> frames, int fps);
    void write(OutputStream output, Array<Pixmap> frames, int fps);
}
