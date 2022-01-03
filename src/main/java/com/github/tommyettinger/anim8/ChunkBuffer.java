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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

/**
 * Copied straight out of libGDX, in the PixmapIO class.
 */
class ChunkBuffer extends DataOutputStream {
    final ByteArrayOutputStream buffer;
    final CRC32 crc;

    ChunkBuffer(int initialSize) {
        this(new ByteArrayOutputStream(initialSize), new CRC32());
    }

    private ChunkBuffer(ByteArrayOutputStream buffer, CRC32 crc) {
        super(new CheckedOutputStream(buffer, crc));
        this.buffer = buffer;
        this.crc = crc;
    }

    public void endChunk(DataOutputStream target) throws IOException {
        flush();
        target.writeInt(buffer.size() - 4);
        buffer.writeTo(target);
        target.writeInt((int) crc.getValue());
        buffer.reset();
        crc.reset();
    }
}
