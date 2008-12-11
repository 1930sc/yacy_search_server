//kelondrobyteArray.java
// (C) 2007 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 30.03.2007 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2006-04-02 22:40:07 +0200 (So, 02 Apr 2006) $
// $LastChangedRevision: 1986 $
// $LastChangedBy: orbiter $
//
// LICENSE
// 
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package de.anomic.kelondro;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

// this class is a experimental replacement of byte[]. It should be used
// if frequent System.arraycopy usage is common for byte[] data types
// when replaced by this class, all copies share the same byte[] but can
// access a different part of the byte array

public class kelondroByteArray {
    
    private byte[] buffer;
    private int offset;
    private int length;

    public kelondroByteArray(final int initLength) {
        this.buffer = new byte[initLength];
        this.length = 0;
        this.offset = 0;
    }        
    
    public kelondroByteArray(final byte[] bb) {
        this.buffer = bb;
        this.length = bb.length;
        this.offset = 0;
    }

    public kelondroByteArray(final byte[] bb, final int offset, final int length) {
        this.buffer = bb;
        this.length = length;
        this.offset = offset;
    }

    public kelondroByteArray(final kelondroByteArray ba, final int offset, final int length) {
        this.buffer = ba.buffer;
        this.length = length;
        this.offset = ba.offset + offset;
    }

    public void ensureSize(final int needed) {
        if (buffer.length - offset >= needed) return;
        byte[] newbuffer = new byte[needed];
        System.arraycopy(buffer, offset, newbuffer, 0, length);
        buffer = newbuffer;
        offset = 0;
    }
    
    public void trim(final int needed) {
        if (buffer.length - offset < needed) return;
        if (buffer.length - offset == length) return;
        byte[] newbuffer = new byte[needed];
        System.arraycopy(buffer, offset, newbuffer, 0, length);
        buffer = newbuffer;
        offset = 0;
    }
    
    public final void removeShift(final int pos, final int dist, final int upBound) {
        assert (pos + dist >= 0) : "pos = " + pos + ", dist = " + dist;
        assert (pos >= 0) : "pos = " + pos;
        assert (this.offset + upBound <= buffer.length) : "upBound = " + upBound + ", buffer.length = " + buffer.length;
        assert (this.offset + upBound - dist <= buffer.length) : "dist = " + dist + ", upBound = " + upBound + ", buffer.length = " + buffer.length;
        System.arraycopy(buffer, this.offset + pos + dist, buffer, this.offset + pos, upBound - pos - dist);
    }
    
    public final void swap(final int i, final int j, final int size) {
        if (this.offset + this.length + size <= buffer.length) {
            // there is space in the chunkcache that we can use as buffer
            System.arraycopy(buffer, this.offset + size * i, buffer, buffer.length - size, size);
            System.arraycopy(buffer, this.offset + size * j, buffer, this.offset + size * i, size);
            System.arraycopy(buffer, buffer.length - size, buffer, this.offset + size * j, size);
        } else {
            // allocate a chunk to use as buffer
            final byte[] a = new byte[size];
            System.arraycopy(buffer, this.offset + size * i, a, 0, size);
            System.arraycopy(buffer, this.offset + size * j, buffer, this.offset + size * i, size);
            System.arraycopy(a, 0, buffer, this.offset + size * j, size);
        }
    }
    
    public void clear() {
        length = 0;
        offset = 0;
    }
    
    public int length() {
        return length;
    }
    
    public byte[] asBytes() {
        final byte[] tmp = new byte[length];
        System.arraycopy(buffer, offset, tmp, 0, length);
        return tmp;
    }
    
    public byte readByte(final int pos) {
        return buffer[this.offset + pos];
    }
    
    public long readLongB64e(final int pos, final int length) {
        return kelondroBase64Order.enhancedCoder.decodeLong(buffer, this.offset + pos, length);
    }
    
    public long readLongB256(final int pos, final int length) {
        return kelondroNaturalOrder.decodeLong(buffer, this.offset + pos, length);
    }
    
    public byte[] readBytes(final int from_pos, final int length) {
        final byte[] buf = new byte[length];
        System.arraycopy(buffer, this.offset + from_pos, buf, 0, length);
        return buf;
    }
    
    public String readString(final int from_pos, final int length) {
        return new String(buffer, this.offset + from_pos, length);
    }
    
    public String readString(final int from_pos, final int length, final String encoding) {
        try {
            return new String(buffer, this.offset + from_pos, length, encoding);
        } catch (final UnsupportedEncodingException e) {
            return "";
        }
    }
    
    public void readToRA(final int from_pos, final kelondroRA to_file, final int len) throws IOException {
        to_file.write(this.buffer, from_pos, len);
    }
    
    public void write(final int to_position, final byte b) {
        buffer[this.offset + to_position] = b;
    }
    
    public void write(final int to_position, final byte[] from_array, final int from_offset, final int from_length) {
        System.arraycopy(from_array, from_offset, this.buffer, this.offset + to_position, from_length);
    }
    
    public void write(final int to_position, final kelondroByteArray from_array) {
        System.arraycopy(from_array.buffer, from_array.offset, this.buffer, this.offset + to_position, from_array.length);
    }
    
    public void write(final int to_position, final kelondroByteArray from_array, final int from_offset, final int from_length) {
        System.arraycopy(from_array.buffer, from_array.offset + from_offset, this.buffer, this.offset + to_position, from_length);
    }
    
    public static boolean equals(final byte[] buffer, final byte[] pattern) {
        // compares two byte arrays: true, if pattern appears completely at offset position
        if (buffer == null && pattern == null) return true;
        if (buffer == null || pattern == null) return false;
        if (buffer.length < pattern.length) return false;
        for (int i = 0; i < pattern.length; i++) if (buffer[i] != pattern[i]) return false;
        return true;
    }

    public void reset() {
        this.length = 0;
        this.offset = 0;
    }
    
    public int compareTo(final kelondroByteArray b, final kelondroByteOrder order) {
        return order.compare(this.buffer, this.offset, this.length, b.buffer, b.offset, b.length);
    }
    
    public int compareTo(final int aoffset, final int alength, final kelondroByteArray b, final int boffset, final int blength, final kelondroByteOrder order) {
        return order.compare(this.buffer, this.offset + aoffset, alength, b.buffer, b.offset + boffset, blength);
    }
}
