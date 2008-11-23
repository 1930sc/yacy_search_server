// kelondroRAMIndex.java
// (C) 2008 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 07.01.2008 on http://yacy.net
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

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import de.anomic.kelondro.kelondroRow.Entry;

public class kelondroRAMIndex implements kelondroIndex {
    
    private final kelondroRow rowdef;
    private kelondroRowSet index0, index1;
    private final kelondroRow.EntryComparator entryComparator;
    
    public kelondroRAMIndex(final kelondroRow rowdef, final int initialspace) {
    	this.rowdef = rowdef;
    	this.entryComparator = new kelondroRow.EntryComparator(rowdef.objectOrder);
        reset(initialspace);
    }
    
    public void clear() {
		reset(0);
	}
    
	public void reset(final int initialspace) {
	    this.index0 = null; // first flush RAM to make room
		this.index0 = new kelondroRowSet(rowdef, initialspace);
        this.index1 = null; // to show that this is the initialization phase
	}
    
    public kelondroRow row() {
        return index0.row();
    }
    
    protected final void finishInitialization() {
    	if (index1 == null) {
            // finish initialization phase
            index0.sort();
            index0.uniq();
            index1 = new kelondroRowSet(rowdef, 0);
        }
    }
    
    public synchronized kelondroRow.Entry get(final byte[] key) {
        assert (key != null);
        finishInitialization();
        final kelondroRow.Entry indexentry = index0.get(key);
        if (indexentry != null) return indexentry;
        return index1.get(key);
    }

	public boolean has(final byte[] key) {
		assert (key != null);
        finishInitialization();
        if (index0.has(key)) return true;
        return index1.has(key);
	}
    
    public synchronized kelondroRow.Entry put(final kelondroRow.Entry entry) {
    	assert (entry != null);
    	finishInitialization();
        // if the new entry is within the initialization part, just overwrite it
        final kelondroRow.Entry indexentry = index0.get(entry.getPrimaryKeyBytes());
        if (indexentry != null) {
        	index0.put(entry);
            return indexentry;
        }
        // else place it in the index1
        return index1.put(entry);
    }
    
	public Entry put(final Entry row, final Date entryDate) {
		return put(row);
	}
	
	public void putMultiple(final List<Entry> rows) {
		final Iterator<Entry> i = rows.iterator();
		while (i.hasNext()) {
			put(i.next());
		}
	}

	public synchronized void addUnique(final kelondroRow.Entry entry) {    	
    	assert (entry != null);
        if (index1 == null) {
            // we are in the initialization phase
        	index0.addUnique(entry);
        	return;
        }
        // initialization is over, add to secondary index
        index1.addUnique(entry);
    }

	public void addUniqueMultiple(final List<Entry> rows) {
		final Iterator<Entry> i = rows.iterator();
		while (i.hasNext()) addUnique(i.next());
	}
	
	public synchronized ArrayList<kelondroRowCollection> removeDoubles() {
	    // finish initialization phase explicitely
	    if (index1 == null) index1 = new kelondroRowSet(rowdef, 0);
	    return index0.removeDoubles();
	}
	
    public synchronized kelondroRow.Entry remove(final byte[] key) {
        finishInitialization();
        // if the new entry is within the initialization part, just delete it
        final kelondroRow.Entry indexentry = index0.remove(key);
        if (indexentry != null) {
            assert index0.get(key) == null; // check if remove worked
            return indexentry;
        }
        // else remove it from the index1
        final kelondroRow.Entry removed = index1.remove(key);
        assert index1.get(key) == null : "removed " + ((removed == null) ? " is null" : " is not null") + ", and index entry still exists"; // check if remove worked
        return removed;
    }

    public synchronized kelondroRow.Entry removeOne() {
        if ((index1 != null) && (index1.size() != 0)) {
            return index1.removeOne();
        }
        if ((index0 != null) && (index0.size() != 0)) {
        	return index0.removeOne();
        }
        return null;
    }
    
    public synchronized int size() {
        if ((index0 != null) && (index1 == null)) {
            return index0.size();
        }
        if ((index0 == null) && (index1 != null)) {
            return index1.size();
        }
        assert ((index0 != null) && (index1 != null));
        return index0.size() + index1.size();
    }
    
    public synchronized kelondroCloneableIterator<byte[]> keys(final boolean up, final byte[] firstKey) {
        // returns the key-iterator of the underlying kelondroIndex
        if (index1 == null) {
            // finish initialization phase
            index0.sort();
            index0.uniq();
            index1 = new kelondroRowSet(rowdef, 0);
            return index0.keys(up, firstKey);
        }
        assert (index1 != null);
        if (index0 == null) {
            //assert consistencyAnalysis0() : "consistency problem: " + consistencyAnalysis();
            return index1.keys(up, firstKey);
        }
        // index0 should be sorted
        // sort index1 to enable working of the merge iterator
        index1.sort();
        //assert consistencyAnalysis0() : "consistency problem: " + consistencyAnalysis();
        return new kelondroMergeIterator<byte[]>(
                index0.keys(up, firstKey),
                index1.keys(up, firstKey),
                rowdef.objectOrder,
                kelondroMergeIterator.simpleMerge,
                true);
    }

    public synchronized kelondroCloneableIterator<kelondroRow.Entry> rows(final boolean up, final byte[] firstKey) {
        // returns the row-iterator of the underlying kelondroIndex
        if (index1 == null) {
            // finish initialization phase
            index0.sort();
            index0.uniq();
            index1 = new kelondroRowSet(rowdef, 0);
            return index0.rows(up, firstKey);
        }
        assert (index1 != null);
        if (index0 == null) {
            //assert consistencyAnalysis0() : "consistency problem: " + consistencyAnalysis();
            return index1.rows(up, firstKey);
        }
        // index0 should be sorted
        // sort index1 to enable working of the merge iterator
        index1.sort();
        //assert consistencyAnalysis0() : "consistency problem: " + consistencyAnalysis();
        return new kelondroMergeIterator<kelondroRow.Entry>(
                index0.rows(up, firstKey),
                index1.rows(up, firstKey),
                entryComparator,
                kelondroMergeIterator.simpleMerge,
                true);
    }
    
    public kelondroProfile profile() {
        if (index0 == null) return index1.profile();
        if (index1 == null) return index0.profile();
        return kelondroProfile.consolidate(index0.profile(), index1.profile());
    }
    
    public synchronized void close() {
        if (index0 != null) index0.close();
        if (index1 != null) index1.close();
    }

	public String filename() {
		return null; // this does not have a file name
	}
	
	public void deleteOnExit() {
        // do nothing, there is no file
    }

}
