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

/**
 * Full-color animated PNG encoder with compression.
 * This is purely here for compatibility; FastAPNG is identical to {@link AnimatedPNG}.
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
 * @see AnimatedPNG the recommended variant on this class; identical in code
 * @author Matthias Mann
 * @author Nathan Sweet
 * @author Tommy Ettinger
 * @deprecated Use {@link AnimatedPNG} instead.
 */
@Deprecated
public class FastAPNG extends AnimatedPNG {
    /**
     * Creates an AnimatedPNG writer with an initial buffer size of 1024. The buffer can resize later if needed.
     */
    public FastAPNG() {
        super(1024);
    }

    /**
     * Creates an AnimatedPNG writer with the given initial buffer size. The buffer can resize if needed, so using a
     * small size is only a problem if it slows down writing by forcing a resize for several parts of a PNG. A default
     * of 1024 is reasonable.
     * @param initialBufferSize the initial size for the buffer that stores PNG chunks; 1024 is a reasonable default
     */
    public FastAPNG(int initialBufferSize) {
        super(initialBufferSize);
    }
}
