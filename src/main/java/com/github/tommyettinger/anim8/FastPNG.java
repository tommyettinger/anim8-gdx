/*
 * Copyright (c) 2023 Tommy Ettinger
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
import com.badlogic.gdx.utils.ByteArray;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.StreamUtils;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * An almost-drop-in replacement for {@link com.badlogic.gdx.graphics.PixmapIO.PNG},
 * optimized for speed at the expense of features.
 * This type of PNG supports both full color and a full alpha channel; it
 * does not reduce the colors to match a palette. If your image does not have a full
 * alpha channel and has 256 or fewer colors, you can use {@link AnimatedGif} or
 * {@link PNG8}, which have comparable APIs. An instance can be
 * reused to encode multiple PNGs with minimal allocation.
 * <br>
 * The PNG files this produces default to using a somewhat-low compression level, but
 * you can change the compression level to write large files quickly or small files
 * slowly. The {@link #setCompression(int)} method can be set to 0 for the former large
 * files, or 6 or higher for small files. You are encouraged to use some kind of tool to
 * optimize the file size of less-compressed PNGs that you want to host online;
 * <a href="https://github.com/shssoichiro/oxipng">oxipng</a> or
 * <a href="http://www.advsys.net/ken/utils.htm">PNGOUT</a> are good choices.
 * <br>
 * This class has been optimized at the expense of some features. It reads bytes from a
 * Pixmap's {@link Pixmap#getPixels()} buffer directly, and if the Pixmap uses RGBA8888
 * format, it can copy whole rows at a time into the output PNG. If the Pixmap doesn't
 * use RGBA8888 format, this isn't as fast and still produces RGBA8888 PNGs. This class
 * <em>does not support {@link #setFlipY(boolean)}</em>. The method can be called, but
 * it doesn't do anything. This is a consequence of how bytes are read in.
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
public class FastPNG implements Disposable {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final int IHDR = 0x49484452;
    private static final int IDAT = 0x49444154;
    private static final int IEND = 0x49454E44;
    private static final byte COLOR_ARGB = 6;
    private static final byte COMPRESSION_DEFLATE = 0;
    private static final byte FILTER_NONE = 0;
    private static final byte INTERLACE_NONE = 0;

    private final ChunkBuffer buffer;
    private final Deflater deflater;
    private ByteArray curLineBytes;
    private boolean flipY = true;

    /**
     * Creates an FastPNG writer with an initial buffer size of 1024. The buffer can resize later if needed.
     */
    public FastPNG() {
        this(1024);
    }

    /**
     * Creates an FastPNG writer with the given initial buffer size. The buffer can resize if needed, so using a
     * small size is only a problem if it slows down writing by forcing a resize for several parts of a PNG. A default
     * of 1024 is reasonable.
     * @param initialBufferSize the initial size for the buffer that stores PNG chunks; 1024 is a reasonable default
     */
    public FastPNG(int initialBufferSize) {
        buffer = new ChunkBuffer(initialBufferSize);
        deflater = new Deflater(2);
    }

    /**
     * If true, the resulting PNG is flipped vertically. Default is true.
     */
    public void setFlipY(boolean flipY) {
        this.flipY = flipY;
    }

    /**
     * Sets the deflate compression level. Default is 2 here instead of the default in PixmapIO.PNG, which is 6. Using
     * compression level 2 is faster, but doesn't compress quite as well. You can set the compression level as low as 0,
     * which is extremely fast but does no compression and so produces large files. You can set the compression level as
     * high as 9, which is extremely slow and typically not much smaller than compression level 6.
     */
    public void setCompression(int level) {
        deflater.setLevel(level);
    }

    /**
     * Writes the given Pixmap to the requested FileHandle. This can use all 32-bit colors.
     * @param file a FileHandle that must be writable, and will have the given Pixmap written as a PNG image
     * @param pixmap a Pixmap to write to the given file
     */
    public void write (FileHandle file, Pixmap pixmap) {
        OutputStream output = file.write(false);
        try {
            write(output, pixmap);
        } finally {
            StreamUtils.closeQuietly(output);
        }
    }


    /**
     * Writes the given Pixmap as a PNG to the given {@code output} stream without
     * closing the stream. This can use all 32-bit colors.
     * <br>
     * This makes some decisions in order to optimize speed at the expense of file size, by
     * default. You can adjust the compression ratio from the default 6 with {@link #setCompression(int)},
     * either up to 9 (slightly better file size, but much slower to write), or down to as low as 2
     * (not-much-worse file size, but much faster to write), or even 0 (with no compression, this writes
     * drastically more quickly... but the files are huge unless recompressed). Using compression level 0
     * can be a good idea if you know that the output files will go into a ZIP or JAR file, since those
     * use the same DEFLATE algorithm that PNG does, and that can't be done twice for any gain. It can
     * also be a good idea if you intend to optimize the output later using a much smarter tool, like
     * <a href="https://github.com/shssoichiro/oxipng">oxipng</a> or
     * <a href="http://www.advsys.net/ken/utils.htm">PNGOUT</a>.
     *
     * @param output the stream to write to; the stream will not be closed
     * @param pixmap the Pixmap to write
     */
    public void write(OutputStream output, Pixmap pixmap){
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);
            buffer.writeInt(pixmap.getWidth());
            buffer.writeInt(pixmap.getHeight());
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_ARGB);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(IDAT);
            deflater.reset();

            final int width = pixmap.getWidth(), height = pixmap.getHeight();
            int lineLen = width * 4;
            byte[] curLine;
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
////NONE filtering
                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, lineLen);
//// End of filtering code
//
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

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
     * a safe assumption in Java 9 and later. Note, don't use the same FastPNG object after you call
     * this method; you'll need to make a new one if you need to write again after disposing.
     */
    @Override
    public void dispose() {
        deflater.end();
    }
}
