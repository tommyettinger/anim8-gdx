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
