/*
 * Copyright (c) 2024  Tommy Ettinger
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

package com.github.tommyettinger;

import com.github.tommyettinger.anim8.AnimatedGif;

/**
 * A specialized version of AnimatedGif that uses {@link NeuQuant} instead of its inherited {@link #palette}.
 */
public class NQGif extends AnimatedGif {
    /**
     * Analyzes image colors and creates color map.
     */
    @Override
    protected void analyzePixels() {
        int nPix = width * height;
        indexedPixels = new byte[nPix];
        NeuQuant nq = new NeuQuant(image, 10);
        // initialize quantizer
        colorTab = nq.process(); // create reduced palette

        for (int i = 0, c = 0; i < colorTab.length; i += 3, c++) {
            usedEntry[c] = false;
        }
        // map image pixels to new palette
        int k = 0;
        for (int y = 0, i = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgba = image.getPixel(x, y);
                int index = nq.map(rgba >>> 24, rgba >>> 16 & 255, rgba >>> 8 & 255);
                usedEntry[index] = true;
                indexedPixels[i++] = (byte) index;
            }
        }
        colorDepth = 8;
        palSize = 7;
    }
}
