// kelondroEcoRecords.java
// (C) 2007 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 03.07.2007 on http://yacy.net
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

package de.anomic.kelondro.table;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.TreeMap;

import de.anomic.kelondro.index.Row;
import de.anomic.kelondro.io.RandomAccessInterface;
import de.anomic.kelondro.util.kelondroException;

public class FullRecords extends AbstractRecords {

    // static supervision objects: recognize and coordinate all activities
    private static TreeMap<String, FullRecords> recordTracker = new TreeMap<String, FullRecords>();
    
    public FullRecords(
            final File file,
            final short ohbytec, final short ohhandlec,
            final Row rowdef, final int FHandles, final int txtProps, final int txtPropWidth) throws IOException {
        super(file, true, ohbytec, ohhandlec, rowdef, FHandles, txtProps, txtPropWidth);
        recordTracker.put(this.filename, this);
    }
    
    public FullRecords(
            final RandomAccessInterface ra, final String filename,
            final short ohbytec, final short ohhandlec,
            final Row rowdef, final int FHandles, final int txtProps, final int txtPropWidth,
            final boolean exitOnFail) {
        super(ra, filename, true, ohbytec, ohhandlec, rowdef, FHandles, txtProps, txtPropWidth, exitOnFail);
        recordTracker.put(this.filename, this);
    }
    
    public FullRecords(
            final RandomAccessInterface ra, final String filename) throws IOException{
        super(ra, filename, true);
        recordTracker.put(this.filename, this);
    }

    public static final Iterator<String> filenames() {
        // iterates string objects; all file names from record tracker
        return recordTracker.keySet().iterator();
    }

    protected synchronized void deleteNode(final RecordHandle handle) throws IOException {
        super.deleteNode(handle);
    }
    
    public synchronized void close() {
        super.close();
        recordTracker.remove(this.filename);
    }
    
    public Node newNode(final RecordHandle handle, final byte[] bulk, final int offset) throws IOException {
        return new EcoNode(handle, bulk, offset);
    }

    public final class EcoNode implements Node {
        private RecordHandle handle = null; // index of the entry, by default NUL means undefined
        private byte[] ohChunk = null; // contains overhead values
        private byte[] bodyChunk = null; // contains all row values
        private boolean ohChanged = false;
        private boolean bodyChanged = false;

        public EcoNode(final byte[] rowinstance) throws IOException {
            // this initializer is used to create nodes from bulk-read byte arrays
            assert ((rowinstance == null) || (rowinstance.length == ROW.objectsize)) : "bulkchunk.length = " + (rowinstance == null ? "null" : rowinstance.length) + ", ROW.width(0) = " + ROW.width(0);
            this.handle = new RecordHandle(USAGE.allocatePayload(rowinstance));
            
            // create chunks
            this.ohChunk = new byte[overhead];
            this.bodyChunk = new byte[ROW.objectsize];
            for (int i = this.ohChunk.length - 1; i >= 0; i--) this.ohChunk[i] = (byte) 0xff;
            if (rowinstance == null) {
                for (int i = this.bodyChunk.length - 1; i >= 0; i--) this.bodyChunk[i] = (byte) 0xff;
            } else {
               System.arraycopy(rowinstance, 0, this.bodyChunk, 0, this.bodyChunk.length);
            }
            
            // mark chunks as not changed, we wrote that already during allocatePayload
            this.ohChanged = false;
            this.bodyChanged = false;
        }
        
        public EcoNode(final RecordHandle handle, final byte[] bulkchunk, final int offset) throws IOException {
            // this initializer is used to create nodes from bulk-read byte arrays
            // if write is true, then the chunk in bulkchunk is written to the file
            // othervise it is considered equal to what is stored in the file
            // (that is ensured during pre-loaded enumeration)
            this.handle = handle;
            boolean changed;
            if (handle.index >= USAGE.allCount()) {
                // this causes only a write action if we create a node beyond the end of the file
                USAGE.allocateRecord(handle.index, bulkchunk, offset);
                changed = false; // we have already wrote the record, so it is considered as unchanged
            } else {
                changed = true;
            }
            assert ((bulkchunk == null) || (bulkchunk.length - offset >= recordsize)) : "bulkchunk.length = " + (bulkchunk == null ? "null" : bulkchunk.length) + ", offset = " + offset + ", recordsize = " + recordsize;
            
            /*if ((offset == 0) && (overhead == 0) && ((bulkchunk == null) || (bulkchunk.length == ROW.objectsize()))) {
                this.ohChunk = new byte[0];
                if (bulkchunk == null) {
                    this.bodyChunk = new byte[ROW.objectsize()];
                } else {
                    this.bodyChunk = bulkchunk;
                }
            } else { */
                // create empty chunks
                this.ohChunk = new byte[overhead];
                this.bodyChunk = new byte[ROW.objectsize];
                
                // write content to chunks
                if (bulkchunk != null) {
                    if (overhead > 0) System.arraycopy(bulkchunk, offset, this.ohChunk, 0, overhead);
                    System.arraycopy(bulkchunk, offset + overhead, this.bodyChunk, 0, ROW.objectsize);
                }
            //}
            
            // mark chunks as changed
            this.ohChanged = changed;
            this.bodyChanged = changed;
        }
        
        public EcoNode(final RecordHandle handle) throws IOException {
            // this creates an entry with an pre-reserved entry position.
            // values can be written using the setValues() method,
            // but we expect that values are already there in the file.
            assert (handle != null): "node handle is null";
            assert (handle.index >= 0): "node handle too low: " + handle.index;
           
            if (handle == null) throw new kelondroException(filename, "INTERNAL ERROR: node handle is null.");
            if (handle.index >= USAGE.allCount()) {
                throw new kelondroException(filename, "INTERNAL ERROR, Node/init: node handle index " + handle.index + " exceeds size. No auto-fix node was submitted. This is a serious failure.");
            }

            // use given handle
            this.handle = new RecordHandle(handle.index);

            // read record
            this.ohChunk = new byte[overhead];
            if (overhead > 0) entryFile.readFully(seekpos(this.handle), this.ohChunk, 0, overhead);
            this.bodyChunk = null; /*new byte[ROW.objectsize];
            entryFile.readFully(seekpos(this.handle) + overhead, this.bodyChunk, 0, this.bodyChunk.length);
            */
            // mark chunks as not changed
            this.ohChanged = false;
            this.bodyChanged = false;
        }
        
        public RecordHandle handle() {
            // if this entry has an index, return it
            if (this.handle.index == RecordHandle.NUL) throw new kelondroException(filename, "the entry has no index assigned");
            return this.handle;
        }

        public void setOHByte(final int i, final byte b) {
            if (i >= OHBYTEC) throw new IllegalArgumentException("setOHByte: wrong index " + i);
            if (this.handle.index == RecordHandle.NUL) throw new kelondroException(filename, "setOHByte: no handle assigned");
            this.ohChunk[i] = b;
            this.ohChanged = true;
        }
        
        public void setOHHandle(final int i, final RecordHandle otherhandle) {
            assert (i < OHHANDLEC): "setOHHandle: wrong array size " + i;
            assert (this.handle.index != RecordHandle.NUL): "setOHHandle: no handle assigned ind file" + filename;
            if (otherhandle == null) {
                NUL2bytes(this.ohChunk, OHBYTEC + 4 * i);
            } else {
                if (otherhandle.index >= USAGE.allCount()) throw new kelondroException(filename, "INTERNAL ERROR, setOHHandles: handle " + i + " exceeds file size (" + handle.index + " >= " + USAGE.allCount() + ")");
                int2bytes(otherhandle.index, this.ohChunk, OHBYTEC + 4 * i);
            }
            this.ohChanged = true;
        }
        
        public byte getOHByte(final int i) {
            if (i >= OHBYTEC) throw new IllegalArgumentException("getOHByte: wrong index " + i);
            if (this.handle.index == RecordHandle.NUL) throw new kelondroException(filename, "Cannot load OH values");
            return this.ohChunk[i];
        }

        public RecordHandle getOHHandle(final int i) {
            if (this.handle.index == RecordHandle.NUL) throw new kelondroException(filename, "Cannot load OH values");
            assert (i < OHHANDLEC): "handle index out of bounds: " + i + " in file " + filename;
            final int h = bytes2int(this.ohChunk, OHBYTEC + 4 * i);
            return (h == RecordHandle.NUL) ? null : new RecordHandle(h);
        }

        public synchronized void setValueRow(final byte[] row) throws IOException {
            // if the index is defined, then write values directly to the file, else only to the object
            if ((row != null) && (row.length != ROW.objectsize)) throw new IOException("setValueRow with wrong (" + row.length + ") row length instead correct: " + ROW.objectsize);
            
            // set values
            if (this.handle.index != RecordHandle.NUL) {
                this.bodyChunk = row;
                this.bodyChanged = true;
            }
        }

        public synchronized boolean valid() {
            // returns true if the key starts with non-zero byte
            // this may help to detect deleted entries
            return this.bodyChunk == null || (this.bodyChunk[0] != 0) && ((this.bodyChunk[0] != -128) || (this.bodyChunk[1] != 0));
        }

        public synchronized byte[] getKey() throws IOException {
            // read key
            if (this.bodyChunk == null) {
                // load all values from the database file
                this.bodyChunk = new byte[ROW.objectsize];
                // read values
                entryFile.readFully(seekpos(this.handle) + overhead, this.bodyChunk, 0, this.bodyChunk.length);
            }
            return trimCopy(this.bodyChunk, 0, ROW.width(0));
        }

        public synchronized byte[] getValueRow() throws IOException {
            
            if (this.bodyChunk == null) {
                // load all values from the database file
                this.bodyChunk = new byte[ROW.objectsize];
                // read values
                entryFile.readFully(seekpos(this.handle) + overhead, this.bodyChunk, 0, this.bodyChunk.length);
            }

            return this.bodyChunk;
        }

        public synchronized void commit() throws IOException {
            // this must be called after all write operations to the node are finished

            // place the data to the file

            final boolean doCommit = this.ohChanged || this.bodyChanged;
            
            // save head
            synchronized (entryFile) {
            if (this.ohChanged) {
                //System.out.println("WRITEH(" + filename + ", " + seekpos(this.handle) + ", " + this.headChunk.length + ")");
                assert (ohChunk == null) || (ohChunk.length == overhead);
                entryFile.write(seekpos(this.handle), (this.ohChunk == null) ? new byte[overhead] : this.ohChunk);
                this.ohChanged = false;
            }

            // save tail
            if ((this.bodyChunk != null) && (this.bodyChanged)) {
                //System.out.println("WRITET(" + filename + ", " + (seekpos(this.handle) + headchunksize) + ", " + this.tailChunk.length + ")");
                assert (this.bodyChunk == null) || (this.bodyChunk.length == ROW.objectsize);
                entryFile.write(seekpos(this.handle) + overhead, (this.bodyChunk == null) ? new byte[ROW.objectsize] : this.bodyChunk);
                this.bodyChanged = false;
            }
            
            if (doCommit) entryFile.commit();
            }
        }
        
    }
    
}
