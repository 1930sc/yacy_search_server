// kelondroRowCollection.java
// (C) 2006 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 12.01.2006 on http://www.anomic.de
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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import de.anomic.server.NamePrefixThreadFactory;
import de.anomic.server.serverFileUtils;
import de.anomic.server.serverMemory;
import de.anomic.server.serverProcessor;
import de.anomic.server.logging.serverLog;
import de.anomic.yacy.yacySeedDB;

public class kelondroRowCollection implements Iterable<kelondroRow.Entry> {

    public  static final double growfactor = 1.4;
    private static final int isortlimit = 20;
    static final Integer dummy = 0;
    
    public static final ExecutorService sortingthreadexecutor = (serverProcessor.useCPU > 1) ? Executors.newCachedThreadPool(new NamePrefixThreadFactory("sorting")) : null;
    public static final ExecutorService partitionthreadexecutor = (serverProcessor.useCPU > 1) ? Executors.newCachedThreadPool(new NamePrefixThreadFactory("partition")) : null;
    
    protected byte[]        chunkcache;
    protected int           chunkcount;
    protected long          lastTimeRead, lastTimeWrote;    
    protected kelondroRow   rowdef;
    protected int           sortBound;

    private static final int exp_chunkcount  = 0;
    private static final int exp_last_read   = 1;
    private static final int exp_last_wrote  = 2;
    private static final int exp_order_type  = 3;
    private static final int exp_order_bound = 4;
    private static final int exp_collection  = 5;
    
    public kelondroRowCollection(final kelondroRowCollection rc) {
        this.rowdef = rc.rowdef;
        this.chunkcache = rc.chunkcache;
        this.chunkcount = rc.chunkcount;
        this.sortBound = rc.sortBound;
        this.lastTimeRead = rc.lastTimeRead;
        this.lastTimeWrote = rc.lastTimeWrote;
    }
    
    public kelondroRowCollection(final kelondroRow rowdef, final int objectCount) {
        this.rowdef = rowdef;
        this.chunkcache = new byte[objectCount * rowdef.objectsize];
        this.chunkcount = 0;
        this.sortBound = 0;
        this.lastTimeRead = System.currentTimeMillis();
        this.lastTimeWrote = System.currentTimeMillis();
    }
     
    public kelondroRowCollection(final kelondroRow rowdef, final int objectCount, final byte[] cache, final int sortBound) {
        this.rowdef = rowdef;
        this.chunkcache = cache;
        this.chunkcount = objectCount;
        this.sortBound = sortBound;
        this.lastTimeRead = System.currentTimeMillis();
        this.lastTimeWrote = System.currentTimeMillis();
    }
    
    public kelondroRowCollection(final kelondroRow rowdef, final kelondroRow.Entry exportedCollectionRowEnvironment) {
        final int chunkcachelength = exportedCollectionRowEnvironment.cellwidth(1) - exportOverheadSize;
        final kelondroRow.Entry exportedCollection = exportRow(chunkcachelength).newEntry(exportedCollectionRowEnvironment, 1);
        
        this.rowdef = rowdef;
        this.chunkcount = (int) exportedCollection.getColLong(exp_chunkcount);
        if ((this.chunkcount > chunkcachelength / rowdef.objectsize)) {
            serverLog.logWarning("RowCollection", "corrected wrong chunkcount; chunkcount = " + this.chunkcount + ", chunkcachelength = " + chunkcachelength + ", rowdef.objectsize = " + rowdef.objectsize);
            this.chunkcount = chunkcachelength / rowdef.objectsize; // patch problem
        }
        this.lastTimeRead = (exportedCollection.getColLong(exp_last_read) + 10957) * day;
        this.lastTimeWrote = (exportedCollection.getColLong(exp_last_wrote) + 10957) * day;
        final String sortOrderKey = exportedCollection.getColString(exp_order_type, null);
        kelondroByteOrder oldOrder = null;
        if ((sortOrderKey == null) || (sortOrderKey.equals("__"))) {
            oldOrder = null;
        } else {
            oldOrder = kelondroNaturalOrder.bySignature(sortOrderKey);
            if (oldOrder == null) oldOrder = kelondroBase64Order.bySignature(sortOrderKey);
        }
        if ((rowdef.objectOrder != null) && (oldOrder != null) && (!(rowdef.objectOrder.signature().equals(oldOrder.signature()))))
            throw new kelondroException("old collection order does not match with new order; objectOrder.signature = " + rowdef.objectOrder.signature() + ", oldOrder.signature = " + oldOrder.signature());
        this.sortBound = (int) exportedCollection.getColLong(exp_order_bound);
        if (sortBound > chunkcount) {
            serverLog.logWarning("RowCollection", "corrected wrong sortBound; sortBound = " + sortBound + ", chunkcount = " + chunkcount);
            this.sortBound = chunkcount;
        }
        this.chunkcache = exportedCollection.getColBytes(exp_collection);        
    }

	public void reset() {
		this.chunkcache = new byte[0];
        this.chunkcount = 0;
        this.sortBound = 0;
	}
   
    private static final kelondroRow exportMeasureRow = exportRow(0 /* no relevance */);

    protected static final int sizeOfExportedCollectionRows(final kelondroRow.Entry exportedCollectionRowEnvironment, final int columnInEnvironment) {
    	final kelondroRow.Entry exportedCollectionEntry = exportMeasureRow.newEntry(exportedCollectionRowEnvironment, columnInEnvironment);
    	final int chunkcount = (int) exportedCollectionEntry.getColLong(exp_chunkcount);
        return chunkcount;
    }
    
    private static final long day = 1000 * 60 * 60 * 24;
    
    public static int daysSince2000(final long time) {
        return (int) (time / day) - 10957;
    }
    
    private static kelondroRow exportRow(final int chunkcachelength) {
        // find out the size of this collection
        return new kelondroRow(
                "int size-4 {b256}," +
                "short lastread-2 {b256}," + // as daysSince2000
                "short lastwrote-2 {b256}," + // as daysSince2000
                "byte[] orderkey-2," +
                "int orderbound-4 {b256}," +
                "byte[] collection-" + chunkcachelength,
                kelondroNaturalOrder.naturalOrder, 0
                );
    }
    
    public static final int exportOverheadSize = 14;

    public synchronized byte[] exportCollection() {
        // returns null if the collection is empty
        trim(false);
        assert this.size() * this.rowdef.objectsize == this.chunkcache.length : "this.size() = " + this.size() + ", objectsize = " + this.rowdef.objectsize + ", chunkcache.length = " + this.chunkcache.length;
        final kelondroRow row = exportRow(chunkcache.length);
        final kelondroRow.Entry entry = row.newEntry();
        assert (sortBound <= chunkcount) : "sortBound = " + sortBound + ", chunkcount = " + chunkcount;
        assert (this.chunkcount <= chunkcache.length / rowdef.objectsize) : "chunkcount = " + this.chunkcount + ", chunkcache.length = " + chunkcache.length + ", rowdef.objectsize = " + rowdef.objectsize;
        entry.setCol(exp_chunkcount, this.chunkcount);
        entry.setCol(exp_last_read, daysSince2000(this.lastTimeRead));
        entry.setCol(exp_last_wrote, daysSince2000(this.lastTimeWrote));
        entry.setCol(exp_order_type, (this.rowdef.objectOrder == null) ? "__".getBytes() :this.rowdef.objectOrder.signature().getBytes());
        entry.setCol(exp_order_bound, this.sortBound);
        entry.setCol(exp_collection, this.chunkcache);
        return entry.bytes();
    }
    
    public void saveCollection(final File file) throws IOException {
        serverFileUtils.copy(exportCollection(), file);
    }

    public kelondroRow row() {
        return this.rowdef;
    }
    
    protected final void ensureSize(final int elements) {
        final int needed = elements * rowdef.objectsize;
        if (chunkcache.length >= needed) return;
        byte[] newChunkcache = new byte[(int) (needed * growfactor)]; // increase space
        System.arraycopy(chunkcache, 0, newChunkcache, 0, chunkcache.length);
        chunkcache = newChunkcache;
    }
    
    public final long memoryNeededForGrow() {
        return (long) ((((long) (chunkcount + 1)) * ((long) rowdef.objectsize)) * growfactor);
    }
    
    public synchronized void trim(final boolean plusGrowFactor) {
        if (chunkcache.length == 0) return;
        int needed = chunkcount * rowdef.objectsize;
        if (plusGrowFactor) needed = (int) (needed * growfactor);
        if (needed >= chunkcache.length)
            return; // in case that the growfactor causes that the cache would
                    // grow instead of shrink, simply ignore the growfactor
        if (serverMemory.available() + 1000 < needed)
            return; // if the swap buffer is not available, we must give up.
                    // This is not critical. Otherwise we provoke a serious
                    // problem with OOM
        byte[] newChunkcache = new byte[needed];
        System.arraycopy(chunkcache, 0, newChunkcache, 0, Math.min(
                chunkcache.length, newChunkcache.length));
        chunkcache = newChunkcache;
    }

    public final long lastRead() {
        return lastTimeRead;
    }
    
    public final long lastWrote() {
        return lastTimeWrote;
    }
    
    public synchronized final byte[] getKey(final int index) {
        assert (index >= 0) : "get: access with index " + index + " is below zero";
        assert (index < chunkcount) : "get: access with index " + index + " is above chunkcount " + chunkcount + "; sortBound = " + sortBound;
        assert (index * rowdef.objectsize < chunkcache.length);
        if ((chunkcache == null) || (rowdef == null)) return null; // case may appear during shutdown
        if (index >= chunkcount) return null;
        if ((index + 1) * rowdef.objectsize > chunkcache.length) return null; // the whole chunk does not fit into the chunkcache
        this.lastTimeRead = System.currentTimeMillis();
        final byte[] b = new byte[this.rowdef.width(0)];
        System.arraycopy(chunkcache, index * rowdef.objectsize, b, 0, b.length);
        return b;
    }
    
    public synchronized final kelondroRow.Entry get(final int index, final boolean clone) {
        assert (index >= 0) : "get: access with index " + index + " is below zero";
        assert (index < chunkcount) : "get: access with index " + index + " is above chunkcount " + chunkcount + "; sortBound = " + sortBound;
        assert (chunkcache != null && index * rowdef.objectsize < chunkcache.length);
        assert sortBound <= chunkcount : "sortBound = " + sortBound + ", chunkcount = " + chunkcount;
        if ((chunkcache == null) || (rowdef == null)) return null; // case may appear during shutdown
        kelondroRow.Entry entry;
        final int addr = index * rowdef.objectsize;
        synchronized (this) {
            if (index >= chunkcount) return null;
            if (addr + rowdef.objectsize > chunkcache.length) return null; // the whole chunk does not fit into the chunkcache
            entry = rowdef.newEntry(chunkcache, addr, clone);
        }
        this.lastTimeRead = System.currentTimeMillis();
        return entry;
    }
    
    public synchronized final void set(final int index, final kelondroRow.Entry a) {
        assert (index >= 0) : "set: access with index " + index + " is below zero";
        ensureSize(index + 1);
        a.writeToArray(chunkcache, index * rowdef.objectsize);
        if (index >= this.chunkcount) this.chunkcount = index + 1;
        if (index < this.sortBound) this.sortBound = index;
        this.lastTimeWrote = System.currentTimeMillis();
    }
    
    public final void insertUnique(final int index, final kelondroRow.Entry a) {
        assert (a != null);

        if (index < chunkcount) {
            // make room
            ensureSize(chunkcount + 1);
            System.arraycopy(chunkcache, rowdef.objectsize * index, chunkcache, rowdef.objectsize * (index + 1), (chunkcount - index) * rowdef.objectsize);
            chunkcount++;
        }
        // insert entry into gap
        set(index, a);
    }
    
    public synchronized void addUnique(final kelondroRow.Entry row) {
        final byte[] r = row.bytes();
        addUnique(r, 0, r.length);
    }

    public synchronized void addUniqueMultiple(final List<kelondroRow.Entry> rows) {
        assert this.sortBound == 0 : "sortBound = " + this.sortBound + ", chunkcount = " + this.chunkcount;
        final Iterator<kelondroRow.Entry> i = rows.iterator();
        while (i.hasNext()) addUnique(i.next());
    }
    
    public synchronized void add(final byte[] a) {
        assert a.length == this.rowdef.objectsize;
        addUnique(a, 0, a.length);
    }
    
    private final void addUnique(final byte[] a, final int astart, final int alength) {
        assert (a != null);
        assert (astart >= 0) && (astart < a.length) : " astart = " + astart;
        assert (!(serverLog.allZero(a, astart, alength))) : "a = " + serverLog.arrayList(a, astart, alength);
        assert (alength > 0);
        assert (astart + alength <= a.length);
        assert alength == rowdef.objectsize : "alength =" + alength + ", rowdef.objectsize = " + rowdef.objectsize;
        final int l = Math.min(rowdef.objectsize, Math.min(alength, a.length - astart));
        ensureSize(chunkcount + 1);
        System.arraycopy(a, astart, chunkcache, rowdef.objectsize * chunkcount, l);
        chunkcount++;
        // if possible, increase the sortbound value to suppress unnecessary sorting
        if (this.chunkcount == 1) {
            assert this.sortBound == 0;
            this.sortBound = 1;
        } else if (
                this.sortBound + 1 == chunkcount &&
                this.rowdef.objectOrder.compare(chunkcache, rowdef.objectsize * (chunkcount - 2), rowdef.primaryKeyLength,
                                                chunkcache, rowdef.objectsize * (chunkcount - 1), rowdef.primaryKeyLength) == -1) {
            this.sortBound = chunkcount;
        }
        this.lastTimeWrote = System.currentTimeMillis();
    }
    
    protected final void addSorted(final byte[] a, final int astart, final int alength) {
        assert (a != null);
        assert (astart >= 0) && (astart < a.length) : " astart = " + astart;
        assert (!(serverLog.allZero(a, astart, alength))) : "a = " + serverLog.arrayList(a, astart, alength);
        assert (alength > 0);
        assert (astart + alength <= a.length);
        assert alength == rowdef.objectsize : "alength =" + alength + ", rowdef.objectsize = " + rowdef.objectsize;
        final int l = Math.min(rowdef.objectsize, Math.min(alength, a.length - astart));
        ensureSize(chunkcount + 1);
        System.arraycopy(a, astart, chunkcache, rowdef.objectsize * chunkcount, l);
        this.chunkcount++;
        this.sortBound = this.chunkcount;
        this.lastTimeWrote = System.currentTimeMillis();
    }
    
    public synchronized final void addAllUnique(final kelondroRowCollection c) {
        if (c == null) return;
        assert(rowdef.objectsize == c.rowdef.objectsize);
        ensureSize(chunkcount + c.size());
        System.arraycopy(c.chunkcache, 0, chunkcache, rowdef.objectsize * chunkcount, rowdef.objectsize * c.size());
        chunkcount += c.size();
    }
    
    /**
     * This method removes the entry at position p ensuring the order of the remaining
     * entries if specified by keepOrder.
     * Note: Keeping the order is expensive. If you want to remove more than one element in
     * a batch with this method, it'd be better to do the removes without order keeping and doing
     * the sort after all the removes are done.
     * 
     * @param p element at this position will be removed
     * @param keepOrder keep the order of remaining entries
     */
    protected synchronized final void removeRow(final int p, final boolean keepOrder) {
        assert p >= 0 : "p = " + p;
        assert p < chunkcount : "p = " + p + ", chunkcount = " + chunkcount;
        assert chunkcount > 0 : "chunkcount = " + chunkcount;
        assert sortBound <= chunkcount : "sortBound = " + sortBound + ", chunkcount = " + chunkcount;
        if (keepOrder && (p < sortBound)) {
            // remove by shift (quite expensive for big collections)
            final int addr = p * this.rowdef.objectsize;
            System.arraycopy(
                    chunkcache, addr + this.rowdef.objectsize,
                    chunkcache, addr,
                    (chunkcount - p - 1) * this.rowdef.objectsize);
            sortBound--; // this is only correct if p < sortBound, but this was already checked above
        } else {
            // remove by copying the top-element to the remove position
            if (p != chunkcount - 1) {
                System.arraycopy(
                        chunkcache, (chunkcount - 1) * this.rowdef.objectsize,
                        chunkcache, p * this.rowdef.objectsize,
                        this.rowdef.objectsize);
            }
            // we moved the last element to the remove position: (p+1)st element
            // only the first p elements keep their order (element p is already outside the order)
            if (sortBound > p) sortBound = p;
        }
        chunkcount--;
        this.lastTimeWrote = System.currentTimeMillis();
    }
    
    /**
     * removes the last entry from the collection
     * @return
     */
    public synchronized kelondroRow.Entry removeOne() {
        if (chunkcount == 0) return null;
        final kelondroRow.Entry r = get(chunkcount - 1, true);
        if (chunkcount == sortBound) sortBound--;
        chunkcount--;
        this.lastTimeWrote = System.currentTimeMillis();
        return r;
    }
    
    public synchronized void clear() {
        if (this.chunkcache.length == 0) return;
        this.chunkcache = new byte[0];
        this.chunkcount = 0;
        this.sortBound = 0;
        this.lastTimeWrote = System.currentTimeMillis();
    }
    
    public int size() {
        return this.chunkcount;
    }
    
    public int sorted() {
        return this.sortBound;
    }
    
    public synchronized Iterator<byte[]> keys() {
        // iterates byte[] - type entries
        return new keyIterator();
    }
    
    /**
     * Iterator for kelondroRowCollection.
     * It supports remove() though it doesn't contain the order of the underlying
     * collection during removes.
     *
     */
    public class keyIterator implements Iterator<byte[]> {

        private int p;
        
        public keyIterator() {
            p = 0;
        }
        
        public boolean hasNext() {
            return p < chunkcount;
        }

        public byte[] next() {
            return getKey(p++);
        }
        
        public void remove() {
            p--;
            removeRow(p, false);
        }
    }    

    /**
     * return an iterator for the row entries in this object
     */
    public Iterator<kelondroRow.Entry> iterator() {
        // iterates kelondroRow.Entry - type entries
        return new rowIterator();
    }
    
    /**
     * Iterator for kelondroRowCollection.
     * It supports remove() though it doesn't contain the order of the underlying
     * collection during removes.
     *
     */
    public class rowIterator implements Iterator<kelondroRow.Entry> {

        private int p;
        
        public rowIterator() {
            p = 0;
        }
        
        public boolean hasNext() {
            return p < chunkcount;
        }

        public kelondroRow.Entry next() {
            return get(p++, true);
        }
        
        public void remove() {
            p--;
            removeRow(p, false);
        }

    }
    
    public synchronized void select(final Set<String> keys) {
        // removes all entries but the ones given by urlselection
        if ((keys == null) || (keys.isEmpty())) return;
        final Iterator<kelondroRow.Entry> i = iterator();
        kelondroRow.Entry row;
        while (i.hasNext()) {
            row = i.next();
            if (!(keys.contains(row.getColString(0, null)))) i.remove();
        }
    }
    
    public synchronized final void sort() {
        assert (this.rowdef.objectOrder != null);
        if (this.sortBound == this.chunkcount) return; // this is already sorted
        if (this.chunkcount < isortlimit) {
            isort(0, this.chunkcount, new byte[this.rowdef.objectsize]);
            this.sortBound = this.chunkcount;
            assert this.isSorted();
            return;
        }
        final byte[] swapspace = new byte[this.rowdef.objectsize];
        final int p = partition(0, this.chunkcount, this.sortBound, swapspace);
        if ((sortingthreadexecutor != null) &&
            (!sortingthreadexecutor.isShutdown()) &&
            (serverProcessor.useCPU > 1) && 
            (this.chunkcount > 8000)) {
        	// sort this using multi-threading
            final Future<Integer> part0 = partitionthreadexecutor.submit(new partitionthread(this, 0, p, 0));
            final Future<Integer> part1 = partitionthreadexecutor.submit(new partitionthread(this, p, this.chunkcount, p));
            try {
                final int p0 = part0.get().intValue();
                final Future<Object> sort0 = sortingthreadexecutor.submit(new qsortthread(this, 0, p0, 0));
                final Future<Object> sort1 = sortingthreadexecutor.submit(new qsortthread(this, p0, p, p0));
                final int p1 = part1.get().intValue();
                final Future<Object> sort2 = sortingthreadexecutor.submit(new qsortthread(this, p, p1, p));
                final Future<Object> sort3 = sortingthreadexecutor.submit(new qsortthread(this, p1, this.chunkcount, p1));
                sort0.get();
                sort1.get();
                sort2.get();
                sort3.get();
            } catch (final InterruptedException e) {
                e.printStackTrace();
            } catch (final ExecutionException e) {
                e.printStackTrace();
            }
        } else {
        	qsort(0, p, 0, swapspace);
        	qsort(p + 1, this.chunkcount, 0, swapspace);
        }
        this.sortBound = this.chunkcount;
        //assert this.isSorted();
    }

    public synchronized final void sort2() {
        assert (this.rowdef.objectOrder != null);
        if (this.sortBound == this.chunkcount) return; // this is already sorted
        if (this.chunkcount < isortlimit) {
            isort(0, this.chunkcount, new byte[this.rowdef.objectsize]);
            this.sortBound = this.chunkcount;
            assert this.isSorted();
            return;
        }
        final byte[] swapspace = new byte[this.rowdef.objectsize];
        final int p = partition(0, this.chunkcount, this.sortBound, swapspace);
        if ((sortingthreadexecutor != null) &&
            (!sortingthreadexecutor.isShutdown()) &&
            (serverProcessor.useCPU > 1) && 
            (this.chunkcount > 4000)) {
            // sort this using multi-threading
            final Future<Object> part = sortingthreadexecutor.submit(new qsortthread(this, 0, p, 0));
            //CompletionService<Object> sortingthreadcompletion = new ExecutorCompletionService<Object>(sortingthreadexecutor);
            //Future<Object> part = sortingthreadcompletion.submit(new qsortthread(this, 0, p, 0));
            qsort(p + 1, this.chunkcount, 0, swapspace);
            try {
                part.get();
            } catch (final InterruptedException e) {
                e.printStackTrace();
            } catch (final ExecutionException e) {
                e.printStackTrace();
            }
        } else {
            qsort(0, p, 0, swapspace);
            qsort(p + 1, this.chunkcount, 0, swapspace);
        }
        this.sortBound = this.chunkcount;
        //assert this.isSorted();
    }

    public static class qsortthread implements Callable<Object> {
        kelondroRowCollection rc;
        int L, R, S;
        
    	public qsortthread(final kelondroRowCollection rc, final int L, final int R, final int S) {
    	    this.rc = rc;
    	    this.L = L;
    	    this.R = R;
    	    this.S = S;
        }
    	
    	public Object call() throws Exception {
    	    rc.qsort(L, R, S, new byte[rc.rowdef.objectsize]);
            return null;
        }
    }
    
    final void qsort(final int L, final int R, final int S, final byte[] swapspace) {
    	if (R - L < isortlimit) {
            isort(L, R, swapspace);
            return;
        }
    	assert R > L: "L = " + L + ", R = " + R + ", S = " + S;
		final int p = partition(L, R, S, swapspace);
		assert p >= L: "L = " + L + ", R = " + R + ", S = " + S + ", p = " + p;
		assert p < R: "L = " + L + ", R = " + R + ", S = " + S + ", p = " + p;
		qsort(L, p, 0, swapspace);
		qsort(p + 1, R, 0, swapspace);
	}
    
    public static class partitionthread implements Callable<Integer> {
        kelondroRowCollection rc;
        int L, R, S;
        
        public partitionthread(final kelondroRowCollection rc, final int L, final int R, final int S) {
            this.rc = rc;
            this.L = L;
            this.R = R;
            this.S = S;
        }
        
        public Integer call() throws Exception {
            return Integer.valueOf(rc.partition(L, R, S, new byte[rc.rowdef.objectsize]));
        }
    }
    
    /**
     * @param L is the first element in the sequence
     * @param R is the right bound of the sequence, and outside of the sequence
     * @param S is the bound of the sorted elements in the sequence
     * @param swapspace
     * @return
     */
    final int partition(final int L, final int R, int S, final byte[] swapspace) {
        assert (L < R - 1): "L = " + L + ", R = " + R + ", S = " + S;
        assert (R - L >= isortlimit): "L = " + L + ", R = " + R + ", S = " + S;
        
        int p = L;
        int q = R - 1;
        int pivot = pivot(L, R, S, swapspace);
        int oldpivot = -1;
        byte[] compiledPivot = null;
        if (this.rowdef.objectOrder instanceof kelondroBase64Order) {
        	while (p <= q) {
        		// wenn pivot < S: pivot befindet sich in sortierter Sequenz von L bis S - 1
        		// d.h. alle Werte von L bis pivot sind kleiner als das pivot
        		// zu finden ist ein minimales p <= q so dass chunk[p] >= pivot        		
        		if (compiledPivot == null) compiledPivot = compilePivot(pivot);
        		if ((pivot < S) && (p < pivot)) {
        			//System.out.println("+++ saved " + (pivot - p) + " comparisments");
        			p = pivot;
        			S = 0;
        		} else {
        			while ((p < R - 1) && (comparePivot(compiledPivot, p) >= 0)) p++; // chunkAt[p] < pivot
        		}
        		// nun gilt chunkAt[p] >= pivot
        		while ((q > L) && (comparePivot(compiledPivot, q) <= 0)) q--; // chunkAt[q] > pivot
        		if (p <= q) {
        			oldpivot = pivot;
        			pivot = swap(p, q, pivot, swapspace);
        			if (pivot != oldpivot && compiledPivot != null) compiledPivot = null; // must be computed again
        			p++;
        			q--;
        		}
        	}
        } else {
        	while (p <= q) {
        		if ((pivot < S) && (p < pivot)) {
        			p = pivot;
        			S = 0;
        		} else {
        			while ((p < R - 1) && (compare(pivot, p) >= 0)) p++; // chunkAt[p] < pivot
        		}
        		while ((q > L) && (compare(pivot, q) <= 0)) q--; // chunkAt[q] > pivot
        		if (p <= q) {
        			pivot = swap(p, q, pivot, swapspace);
        			p++;
        			q--;
        		}
        	}
        }
        // now p is the beginning of the upper sequence
        // finally, the pivot element should be exactly between the two sequences
        // distinguish two cases: pivot in lower and upper sequence
        // to do this it is sufficient to compare the index, not the entry content
        if (pivot < p) {
            // switch the pivot with the element _below_ p, the element in p belongs to the upper sequence
            // and does not fit into the lower sequence
            swap(pivot, p - 1, pivot, swapspace);
            return p - 1;
        } else if (pivot > p) {
            // switch the pivot with p, they are both in the same sequence
            swap(pivot, p, pivot, swapspace);
            return p;
        }
        assert pivot == p;
        return p;
    }
	
    private final int pivot(final int L, final int R, final int S, final byte[] swapspace) {
        if ((S == 0) || (S < L)) {
            // the collection has no ordering
            // or
            // the collection has an ordering, but this is not relevant for this pivot
            // because the ordered zone is outside of ordering zone
            final int m = picMiddle(new int[]{L, (3 * L + R - 1) / 4, (L + R - 1) / 2, (L + 3 * R - 3) / 4, R - 1}, 5);
            assert L <= m;
            assert m < R;
            return m;
        }
        if (S < R) {
            // the collection has an ordering
            // and part of the ordered zone is inside the to-be-ordered zone
            final int m = picMiddle(new int[]{L, L + (S - L) / 3, (L + R - 1) / 2, S, R - 1}, 5);
            assert L <= m;
            assert m < R;
            return m;
        }
        // use the sorted set to find good pivot:
        // the sort range is fully inside the sorted area:
        // the middle element must be the best
        // (however, it should be skipped because there is no point in sorting this)
        return (L + R - 1) / 2;
    }

    private final int picMiddle(final int[] list, int len) {
        assert len % 2 != 0;
        assert len <= list.length;
        final int cut = list.length / 2;
        for (int i = 0; i < cut; i++) {remove(list, len, min(list, len)); len--;}
        for (int i = 0; i < cut; i++) {remove(list, len, max(list, len)); len--;}
        // the remaining element must be the middle element
        assert len == 1;
        return list[0];
    }
    
    private final void remove(final int[] list, final int len, final int idx) {
        if (idx == len - 1) return;
        list[idx] = list[len - 1]; // shift last element to front
    }
    
    private final int min(final int[] list, int len) {
        assert len > 0;
        int f = 0;
        while (len-- > 0) {
            if (compare(list[f], list[len]) > 0) f = len;
        }
        return f;
    }
    
    private final int max(final int[] list, int len) {
        assert len > 0;
        int f = 0;
        while (len-- > 0) {
            if (compare(list[f], list[len]) < 0) f = len;
        }
        return f;
    }
    
    private final void isort(final int L, final int R, final byte[] swapspace) {
        for (int i = L + 1; i < R; i++)
            for (int j = i; j > L && compare(j - 1, j) > 0; j--)
                swap(j, j - 1, 0, swapspace);
    }

    private final int swap(final int i, final int j, final int p, final byte[] swapspace) {
        if (i == j) return p;
        System.arraycopy(chunkcache, this.rowdef.objectsize * i, swapspace, 0, this.rowdef.objectsize);
        System.arraycopy(chunkcache, this.rowdef.objectsize * j, chunkcache, this.rowdef.objectsize * i, this.rowdef.objectsize);
        System.arraycopy(swapspace, 0, chunkcache, this.rowdef.objectsize * j, this.rowdef.objectsize);
        if (i == p) return j; else if (j == p) return i; else return p;
    }

    public synchronized void uniq() {
        assert (this.rowdef.objectOrder != null);
        // removes double-occurrences of chunks
        // this works only if the collection was ordered with sort before
        // if the collection is large and the number of deletions is also large,
        // then this method may run a long time with 100% CPU load which is caused
        // by the large number of memory movements.
        if (chunkcount < 2) return;
        int i = chunkcount - 2;
        final long t = System.currentTimeMillis(); // for time-out
        int d = 0;
        boolean u = true;
        try {
            while (i >= 0) {
                if (compare(i, i + 1) == 0) {
                    removeRow(i + 1, false);
                    d++;
                    if (i + 1 < chunkcount - 1) u = false;
                }
                i--;
                if (System.currentTimeMillis() - t > 10000) {
                    throw new RuntimeException("uniq() time-out at " + i + " (backwards) from " + chunkcount + " elements after " + (System.currentTimeMillis() - t) + " milliseconds; " + d + " deletions so far");
                }
            }
        } catch (final RuntimeException e) {
            serverLog.logWarning("kelondroRowCollection", e.getMessage(), e);
        } finally {
            if (!u) this.sort();
        }
    }
    
    public synchronized ArrayList<kelondroRowCollection> removeDoubles() {
        assert (this.rowdef.objectOrder != null);
        // removes double-occurrences of chunks
        // in contrast to uniq() this removes also the remaining, non-double entry that had a double-occurrence to the others
        // all removed chunks are returned in an array
        this.sort();
        final ArrayList<kelondroRowCollection> report = new ArrayList<kelondroRowCollection>();
        if (chunkcount < 2) return report;
        int i = chunkcount - 2;
        int d = 0;
        boolean u = true;
        kelondroRowCollection collection = new kelondroRowCollection(this.rowdef, 2);
        try {
            while (i >= 0) {
                if (compare(i, i + 1) == 0) {
                    collection.addUnique(get(i + 1, false));
                    removeRow(i + 1, false);
                    d++;
                    if (i + 1 < chunkcount - 1) u = false;
                } else if (collection.size() > 0) {
                    // finish collection of double occurrences
                    collection.addUnique(get(i + 1, false));
                    removeRow(i + 1, false);
                    d++;
                    if (i + 1 < chunkcount - 1) u = false;
                    collection.trim(false);
                    report.add(collection);
                    collection = new kelondroRowSet(this.rowdef, 2);
                }
                i--;
            }
        } catch (final RuntimeException e) {
            serverLog.logWarning("kelondroRowCollection", e.getMessage(), e);
        } finally {
            if (!u) this.sort();
        }
        return report;
    }
    
    public synchronized boolean isSorted() {
        assert (this.rowdef.objectOrder != null);
        if (chunkcount <= 1) return true;
        if (chunkcount != this.sortBound) return false;
        for (int i = 0; i < chunkcount - 1; i++) {
        	//System.out.println("*" + new String(get(i).getColBytes(0)));
        	if (compare(i, i + 1) > 0) {
        		System.out.println("?" + new String(get(i + 1, false).getColBytes(0)));
        		return false;
        	}
        }
        return true;
    }
    
    public synchronized String toString() {
        final StringBuilder s = new StringBuilder();
        final Iterator<kelondroRow.Entry> i = iterator();
        if (i.hasNext()) s.append(i.next().toString());
        while (i.hasNext()) s.append(", " + (i.next()).toString());
        return new String(s);
    }

    private final int compare(final int i, final int j) {
        assert (chunkcount * this.rowdef.objectsize <= chunkcache.length) : "chunkcount = " + chunkcount + ", objsize = " + this.rowdef.objectsize + ", chunkcache.length = " + chunkcache.length;
        assert (i >= 0) && (i < chunkcount) : "i = " + i + ", chunkcount = " + chunkcount;
        assert (j >= 0) && (j < chunkcount) : "j = " + j + ", chunkcount = " + chunkcount;
        assert (this.rowdef.objectOrder != null);
        if (i == j) return 0;
        assert (this.rowdef.primaryKeyIndex == 0) : "this.sortColumn = " + this.rowdef.primaryKeyIndex;
        final int colstart = (this.rowdef.primaryKeyIndex <= 0) ? 0 : this.rowdef.colstart[this.rowdef.primaryKeyIndex];
        //assert (!bugappearance(chunkcache, i * this.rowdef.objectsize + colstart, this.rowdef.primaryKeyLength));
        //assert (!bugappearance(chunkcache, j * this.rowdef.objectsize + colstart, this.rowdef.primaryKeyLength));
        final int c = this.rowdef.objectOrder.compare(
                chunkcache,
                i * this.rowdef.objectsize + colstart,
                this.rowdef.primaryKeyLength,
                chunkcache,
                j * this.rowdef.objectsize + colstart,
                this.rowdef.primaryKeyLength);
        return c;
    }
    
    protected final byte[] compilePivot(final int i) {
        assert (i >= 0) && (i < chunkcount) : "i = " + i + ", chunkcount = " + chunkcount;
        assert (this.rowdef.objectOrder != null);
        assert (this.rowdef.objectOrder instanceof kelondroBase64Order);
        assert (this.rowdef.primaryKeyIndex == 0) : "this.sortColumn = " + this.rowdef.primaryKeyIndex;
        final int colstart = (this.rowdef.primaryKeyIndex <= 0) ? 0 : this.rowdef.colstart[this.rowdef.primaryKeyIndex];
        //assert (!bugappearance(chunkcache, i * this.rowdef.objectsize + colstart, this.rowdef.primaryKeyLength));
        return ((kelondroBase64Order) this.rowdef.objectOrder).compilePivot(chunkcache, i * this.rowdef.objectsize + colstart, this.rowdef.primaryKeyLength);
    }
    
    protected final byte[] compilePivot(final byte[] a, final int astart, final int alength) {
        assert (this.rowdef.objectOrder != null);
        assert (this.rowdef.objectOrder instanceof kelondroBase64Order);
        assert (this.rowdef.primaryKeyIndex == 0) : "this.sortColumn = " + this.rowdef.primaryKeyIndex;
        return ((kelondroBase64Order) this.rowdef.objectOrder).compilePivot(a, astart, alength);
    }
    
    protected final int comparePivot(final byte[] compiledPivot, final int j) {
        assert (chunkcount * this.rowdef.objectsize <= chunkcache.length) : "chunkcount = " + chunkcount + ", objsize = " + this.rowdef.objectsize + ", chunkcache.length = " + chunkcache.length;
        assert (j >= 0) && (j < chunkcount) : "j = " + j + ", chunkcount = " + chunkcount;
        assert (this.rowdef.objectOrder != null);
        assert (this.rowdef.objectOrder instanceof kelondroBase64Order);
        assert (this.rowdef.primaryKeyIndex == 0) : "this.sortColumn = " + this.rowdef.primaryKeyIndex;
        final int colstart = (this.rowdef.primaryKeyIndex <= 0) ? 0 : this.rowdef.colstart[this.rowdef.primaryKeyIndex];
        //assert (!bugappearance(chunkcache, j * this.rowdef.objectsize + colstart, this.rowdef.primaryKeyLength));
        final int c = ((kelondroBase64Order) this.rowdef.objectOrder).comparePivot(
        		compiledPivot,
                chunkcache,
                j * this.rowdef.objectsize + colstart,
                this.rowdef.primaryKeyLength);
        return c;
    }

    protected synchronized int compare(final byte[] a, final int astart, final int alength, final int chunknumber) {
        assert (chunknumber < chunkcount);
        final int l = Math.min(this.rowdef.primaryKeyLength, Math.min(a.length - astart, alength));
        return rowdef.objectOrder.compare(a, astart, l, chunkcache, chunknumber * this.rowdef.objectsize + ((rowdef.primaryKeyIndex <= 0) ? 0 : this.rowdef.colstart[rowdef.primaryKeyIndex]), this.rowdef.primaryKeyLength);
    }
    
    protected synchronized boolean match(final byte[] a, final int astart, final int alength, final int chunknumber) {
        if (chunknumber >= chunkcount) return false;
        int i = 0;
        int p = chunknumber * this.rowdef.objectsize + ((rowdef.primaryKeyIndex <= 0) ? 0 : this.rowdef.colstart[rowdef.primaryKeyIndex]);
        final int len = Math.min(this.rowdef.primaryKeyLength, Math.min(alength, a.length - astart));
        while (i < len) if (a[astart + i++] != chunkcache[p++]) return false;
        return ((len == this.rowdef.primaryKeyLength) || (chunkcache[len] == 0)) ;
    }

    public synchronized void close() {
        chunkcache = null;
    }
    
    private static long d(final long a, final long b) {
    	if (b == 0) return a;
    	return a / b;
    }
    
    private static Random random = null;
    private static String randomHash() {
    	return
    		kelondroBase64Order.enhancedCoder.encodeLong(random.nextLong(), 4) +
    		kelondroBase64Order.enhancedCoder.encodeLong(random.nextLong(), 4) +
    		kelondroBase64Order.enhancedCoder.encodeLong(random.nextLong(), 4);
    }
    
    public static void test(final int testsize) {
    	final kelondroRow r = new kelondroRow(new kelondroColumn[]{
    			new kelondroColumn("hash", kelondroColumn.celltype_string, kelondroColumn.encoder_bytes, yacySeedDB.commonHashLength, "hash")},
    			kelondroBase64Order.enhancedCoder, 0);
    	
    	kelondroRowCollection a = new kelondroRowCollection(r, testsize);
    	a.add("AAAAAAAAAAAA".getBytes());
    	a.add("BBBBBBBBBBBB".getBytes());
    	a.add("BBBBBBBBBBBB".getBytes());
    	a.add("BBBBBBBBBBBB".getBytes());
    	a.add("CCCCCCCCCCCC".getBytes());
    	final ArrayList<kelondroRowCollection> del = a.removeDoubles();
    	System.out.println(del + "rows double");
    	final Iterator<kelondroRow.Entry> j = a.iterator();
    	while (j.hasNext()) System.out.println(new String(j.next().bytes()));
    	
        System.out.println("kelondroRowCollection test with size = " + testsize);
        a = new kelondroRowCollection(r, testsize);
        long t0 = System.nanoTime();
        random = new Random(0);
        for (int i = 0; i < testsize; i++) a.add(randomHash().getBytes());
        random = new Random(0);
        for (int i = 0; i < testsize; i++) a.add(randomHash().getBytes());
        a.sort();
        a.uniq();
        long t1 = System.nanoTime();
        System.out.println("create a   : " + (t1 - t0) + " nanoseconds, " + d(testsize, (t1 - t0)) + " entries/nanoseconds; a.size() = " + a.size());
        
    	final kelondroRowCollection c = new kelondroRowCollection(r, testsize);
    	random = new Random(0);
    	t0 = System.nanoTime();
    	for (int i = 0; i < testsize; i++) {
    		c.add(randomHash().getBytes());
    	}
    	t1 = System.nanoTime();
    	System.out.println("create c   : " + (t1 - t0) + " nanoseconds, " + d(testsize, (t1 - t0)) + " entries/nanoseconds");
    	final kelondroRowCollection d = new kelondroRowCollection(r, testsize);
    	for (int i = 0; i < testsize; i++) {
    		d.add(c.get(i, false).getColBytes(0));
    	}
    	final long t2 = System.nanoTime();
    	System.out.println("copy c -> d: " + (t2 - t1) + " nanoseconds, " + d(testsize, (t2 - t1)) + " entries/nanoseconds");
    	serverProcessor.useCPU = 1;
    	c.sort();
    	final long t3 = System.nanoTime();
    	System.out.println("sort c (1) : " + (t3 - t2) + " nanoseconds, " + d(testsize, (t3 - t2)) + " entries/nanoseconds");
    	serverProcessor.useCPU = 2;
    	d.sort();
    	final long t4 = System.nanoTime();
    	System.out.println("sort d (2) : " + (t4 - t3) + " nanoseconds, " + d(testsize, (t4 - t3)) + " entries/nanoseconds");
    	c.uniq();
    	final long t5 = System.nanoTime();
    	System.out.println("uniq c     : " + (t5 - t4) + " nanoseconds, " + d(testsize, (t5 - t4)) + " entries/nanoseconds");
    	d.uniq();
    	final long t6 = System.nanoTime();
    	System.out.println("uniq d     : " + (t6 - t5) + " nanoseconds, " + d(testsize, (t6 - t5)) + " entries/nanoseconds");
    	random = new Random(0);
    	final kelondroRowSet e = new kelondroRowSet(r, testsize);
    	for (int i = 0; i < testsize; i++) {
    		e.put(r.newEntry(randomHash().getBytes()));
    	}
    	final long t7 = System.nanoTime();
    	System.out.println("create e   : " + (t7 - t6) + " nanoseconds, " + d(testsize, (t7 - t6)) + " entries/nanoseconds");
    	e.sort();
    	final long t8 = System.nanoTime();
    	System.out.println("sort e (2) : " + (t8 - t7) + " nanoseconds, " + d(testsize, (t8 - t7)) + " entries/nanoseconds");
    	e.uniq();
    	final long t9 = System.nanoTime();
    	System.out.println("uniq e     : " + (t9 - t8) + " nanoseconds, " + d(testsize, (t9 - t8)) + " entries/nanoseconds");
    	final boolean cis = c.isSorted();
    	final long t10 = System.nanoTime();
    	System.out.println("c isSorted = " + ((cis) ? "true" : "false") + ": " + (t10 - t9) + " nanoseconds");
    	final boolean dis = d.isSorted();
    	final long t11 = System.nanoTime();
    	System.out.println("d isSorted = " + ((dis) ? "true" : "false") + ": " + (t11 - t10) + " nanoseconds");
    	final boolean eis = e.isSorted();
    	final long t12 = System.nanoTime();
    	System.out.println("e isSorted = " + ((eis) ? "true" : "false") + ": " + (t12 - t11) + " nanoseconds");
    	random = new Random(0);
    	boolean allfound = true;
        for (int i = 0; i < testsize; i++) {
            final String rh = randomHash();
            if (e.get(rh.getBytes()) == null) {
                allfound = false;
                System.out.println("not found hash " + rh + " at attempt " + i);
                break;
            }
        }
        final long t13 = System.nanoTime();
        System.out.println("e allfound = " + ((allfound) ? "true" : "false") + ": " + (t13 - t12) + " nanoseconds");
        boolean noghosts = true;
        for (int i = 0; i < testsize; i++) {
            if (e.get(randomHash().getBytes()) != null) {
                noghosts = false;
                break;
            }
        }
        final long t14 = System.nanoTime();
        System.out.println("e noghosts = " + ((noghosts) ? "true" : "false") + ": " + (t14 - t13) + " nanoseconds");
        System.out.println("Result size: c = " + c.size() + ", d = " + d.size() + ", e = " + e.size());
    	System.out.println();
    	if (sortingthreadexecutor != null) sortingthreadexecutor.shutdown();
    }
    
    public static void main(final String[] args) {
    	//test(1000);
    	test(50000);
    	//test(100000);
    	//test(1000000);
    	
    	/*   	
        System.out.println(new java.util.Date(10957 * day));
        System.out.println(new java.util.Date(0));
        System.out.println(daysSince2000(System.currentTimeMillis()));
        */
    }
}
