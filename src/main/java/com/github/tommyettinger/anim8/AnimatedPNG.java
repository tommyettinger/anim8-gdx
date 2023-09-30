/*
 * Copyright (c) 2022  Tommy Ettinger
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package com.github.tommyettinger.anim8;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ByteArray;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.StreamUtils;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Full-color animated PNG encoder with compression.
 * This type of animated PNG supports both full color and a full alpha channel; it
 * does not reduce the colors to match a palette. If your image does not have a full
 * alpha channel and has 256 or fewer colors, you can use {@link AnimatedGif} or the
 * animated mode of {@link PNG8}, which have comparable APIs. An instance can be 
 * reused to encode multiple animated PNGs with minimal allocation.
 * <br>
 * The animated PNG (often called APNG) files this produces default to using a high
 * compression level, but this is somewhat slow. You can use {@link #setCompression(int)}
 * to set compression to 2, which results in about 10-15% larger files that take about
 * half the time to write, or leave it at the default 6, which may take a few seconds
 * longer to write large files. You are encouraged to use some kind of tool to optimize
 * the file size of less-compressed APNGs that you want to host online;
 * <a href="http://sourceforge.net/projects/apng/files/APNG_Optimizer/">APNG Optimizer</a>
 * is a good choice.
 * <br>
 * <pre>
 * Copyright (c) 2007 Matthias Mann - www.matthiasmann.de
 * Copyright (c) 2014 Nathan Sweet
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * </pre>
 *
 * @author Matthias Mann
 * @author Nathan Sweet
 * @author Tommy Ettinger
 */
public class AnimatedPNG implements AnimationWriter, Disposable {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final int IHDR = 0x49484452, acTL = 0x6163544C,
            fcTL = 0x6663544C, IDAT = 0x49444154,
            fdAT = 0x66644154, IEND = 0x49454E44;
    private static final byte COLOR_ARGB = 6;
    private static final byte COMPRESSION_DEFLATE = 0;
    private static final byte FILTER_NONE = 0;
    private static final byte INTERLACE_NONE = 0;
//    private static final byte FILTER_SUB = 1;
//    private static final byte FILTER_PAETH = 4;

    private final ChunkBuffer buffer;
    private final Deflater deflater;
    private ByteArray curLineBytes;
    private boolean flipY = true;

    /**
     * Creates an AnimatedPNG writer with an initial buffer size of 16384. The buffer can resize later if needed.
     */
    public AnimatedPNG () {
        this(16384);
    }

    /**
     * Creates an AnimatedPNG writer with the given initial buffer size. The buffer can resize if needed, so using a
     * small size is only a problem if it slows down writing by forcing a resize for several parts of a PNG. A default
     * of 16384 is reasonable.
     * @param initialBufferSize the initial size for the buffer that stores PNG chunks; 16384 is a reasonable default
     */
    public AnimatedPNG (int initialBufferSize) {
        buffer = new ChunkBuffer(initialBufferSize);
        deflater = new Deflater();
    }

    /**
     * If true, the resulting AnimatedPNG is flipped vertically. Default is true.
     */
    public void setFlipY(boolean flipY) {
        this.flipY = flipY;
    }

    /**
     * Sets the deflate compression level. Default is {@link Deflater#DEFAULT_COMPRESSION}, which is currently 6 on all
     * Java versions in the 8 to 14 range, but is permitted to change.
     */
    public void setCompression(int level) {
        deflater.setLevel(level);
    }

    /**
     * Writes an animated PNG file consisting of the given {@code frames} to the given {@code file}, at 60 frames per
     * second. This doesn't guarantee that the animated PNG will be played back at a steady 60 frames per second, just
     * that the duration of each frame is 1/60 of a second if playback is optimal.
     * @param file the file location to write to; any existing file with this name will be overwritten
     * @param frames an Array of Pixmap frames to write in order to the animated PNG
     */
    @Override
    public void write(FileHandle file, Array<Pixmap> frames) {
        OutputStream output = file.write(false);
        try {
            write(output, frames, 60);
        } finally {
            StreamUtils.closeQuietly(output);
        }
    }

    /**
     * Writes an animated PNG file consisting of the given {@code frames} to the given {@code file},
     * at {@code fps} frames per second.
     * @param file the file location to write to; any existing file with this name will be overwritten
     * @param frames an Array of Pixmap frames to write in order to the animated PNG
     * @param fps how many frames per second the animated PNG should display
     */
    @Override
    public void write(FileHandle file, Array<Pixmap> frames, int fps) {
        OutputStream output = file.write(false);
        try {
            write(output, frames, fps);
        } finally {
            StreamUtils.closeQuietly(output);
        }
    }

    /**
     * Writes animated PNG data consisting of the given {@code frames} to the given {@code output} stream without
     * closing the stream, at {@code fps} frames per second.
     * @param output the stream to write to; the stream will not be closed
     * @param frames an Array of Pixmap frames to write in order to the animated PNG
     * @param fps how many frames per second the animated PNG should display
     */
    @SuppressWarnings("RedundantCast")
    @Override
    public void write(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);
            final int width = pixmap.getWidth();
            final int height = pixmap.getHeight();

            buffer.writeInt(IHDR);
            buffer.writeInt(width);
            buffer.writeInt(height);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_ARGB);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

            int lineLen = width * 4;
            byte[] curLine;

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(width);
                buffer.writeInt(height);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);
                }
                deflater.reset();

                if (curLineBytes == null) {
                    curLine = (curLineBytes = new ByteArray(lineLen)).items;
                } else {
                    curLine = curLineBytes.ensureCapacity(lineLen);
                }

                for (int y = 0; y < height; y++) {
                    int py = flipY ? (height - y - 1) : y;
                    for (int px = 0, x = 0; px < width; px++) {
                        int pixel = pixmap.getPixel(px, py);
                        curLine[x++] = (byte) ((pixel >>> 24) & 0xff);
                        curLine[x++] = (byte) ((pixel >>> 16) & 0xff);
                        curLine[x++] = (byte) ((pixel >>> 8) & 0xff);
                        curLine[x++] = (byte) (pixel & 0xff);
                    }
////PAETH
//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//                    lineOut[1] = (byte) (curLine[1] - prevLine[1]);
//                    lineOut[2] = (byte) (curLine[2] - prevLine[2]);
//                    lineOut[3] = (byte) (curLine[3] - prevLine[3]);
//
//                    for (int x = 4; x < lineLen; x++) {
//                        int a = curLine[x - 4] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 4] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc) //
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(PAETH);
//                    deflaterOutput.write(lineOut, 0, lineLen);
////NONE
                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, lineLen);
////SUB
//                    lineOut[0] = curLine[0];
//                    lineOut[1] = curLine[1];
//                    lineOut[2] = curLine[2];
//                    lineOut[3] = curLine[3];
//
//                    for (int x = 4; x < lineLen; x++) {
//                        lineOut[x] = (byte) (curLine[x] - curLine[x - 4]);
//                    }
//                    deflaterOutput.write(FILTER_SUB);
//                    deflaterOutput.write(lineOut, 0, lineLen);
//// End of filtering code
//
//// used by Paeth filtering
//                    byte[] temp = curLine;
//                    curLine = prevLine;
//                    prevLine = temp;
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    /**
     * Disposal should probably be done explicitly, especially if using JRE versions after 8.
     * In Java 8 and earlier, you could rely on finalize() doing what this does, but that isn't
     * a safe assumption in Java 9 and later. Note, don't use the same AnimatedPNG object after you call
     * this method; you'll need to make a new one if you need to write again after disposing.
     */
    @Override
    public void dispose() {
        deflater.end();
    }
}
