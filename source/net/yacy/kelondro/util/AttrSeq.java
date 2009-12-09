// kelondroAttrSeq.java
// -----------------------
// part of YaCy
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2005
// Created 15.11.2005
//
// $LastChangedDate: 2005-10-22 15:28:04 +0200 (Sat, 22 Oct 2005) $
// $LastChangedRevision: 968 $
// $LastChangedBy: theli $
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

package net.yacy.kelondro.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

import net.yacy.kelondro.index.Column;
import net.yacy.kelondro.index.Row;
import net.yacy.kelondro.index.RowCollection;
import net.yacy.kelondro.index.RowSpaceExceededException;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.order.Base64Order;


public class AttrSeq {
    
    // class objects
    private final File file;
    private final Map<String, Object> entries; // value may be of type String or of type Entry
    protected Structure structure;
    private String name;
    private long created;
    
    // optional logger
    protected Logger theLogger = null;
    
    public AttrSeq(final File file, final boolean tree) throws IOException {
        this.file = file;
        this.structure = null;
        this.created = -1;
        this.name = "";
        this.entries = (tree) ? (Map<String, Object>) new TreeMap<String, Object>() : (Map<String, Object>) new HashMap<String, Object>();
        readAttrFile(file);
    }

    public AttrSeq(final String name, final String struct, final boolean tree) {
        this.file = null;
        this.structure = new Structure(struct);
        this.created = System.currentTimeMillis();
        this.name = name;
        this.entries = (tree) ? (Map<String, Object>) new TreeMap<String, Object>() : (Map<String, Object>) new HashMap<String, Object>();
    }
        
    public void setLogger(final Logger newLogger) {
        this.theLogger = newLogger;
    }
    
    public void logInfo(final String message) {
        if (this.theLogger == null)
            System.err.println("ATTRSEQ INFO for file " + this.file + ": " + message);
        else
            this.theLogger.info("ATTRSEQ INFO for file " + this.file + ": " + message);
    }
    
    public void logWarning(final String message) {
        if (this.theLogger == null)
            System.err.println("ATTRSEQ WARNING for file " + this.file + ": " + message);
        else
            this.theLogger.warning("ATTRSEQ WARNING for file " + this.file + ": " + message);
    }
    
    private void readAttrFile(final File loadfile) throws IOException {
        BufferedReader br = null;
        int p;
        if (loadfile.toString().endsWith(".gz")) {
            br = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(loadfile))));
        } else {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(loadfile)));
        }
        String line, key, oldvalue, newvalue;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.length() == 0) continue;
            if (line.startsWith("#")) {
                if (line.startsWith("# Structure=")) {
                    structure = new Structure(line.substring(12));
                }
                if (line.startsWith("# Name=")) {
                    name = line.substring(7);
                }
                if (line.startsWith("# Created=")) {
                    created = Long.parseLong(line.substring(10));
                }
                continue;
            }
            if ((p = line.indexOf('=')) > 0) {
                key = line.substring(0, p).trim();
                newvalue = line.substring(p + 1).trim();
                oldvalue = (String) entries.get(key);
                if (oldvalue != null) {
                    if (newvalue.equals(oldvalue)) {
                        //logWarning("key " + key + ": double occurrence. values are equal. second appearance is ignored");
                    } else {
                        if (newvalue.length() < oldvalue.length()) {
                            if (oldvalue.substring(0, newvalue.length()).equals(newvalue)) {
                                logWarning("key " + key + ": double occurrence. new value is subset of old value. second appearance is ignored");
                            } else {
                                logWarning("key " + key + ": double occurrence. new value is shorter than old value, but not a subsequence. old = " + oldvalue + ", new = " + newvalue);
                            }
                        } else if (newvalue.length() > oldvalue.length()) {
                            if (newvalue.substring(0, oldvalue.length()).equals(oldvalue)) {
                                logWarning("key " + key + ": double occurrence. old value is subset of new value. first appearance is ignored");
                            } else {
                                logWarning("key " + key + ": double occurrence. old value is shorter than new value, but not a subsequence. old = " + oldvalue + ", new = " + newvalue);
                            }
                            entries.put(key, newvalue);
                        } else {
                            logWarning("key " + key + ": double occurrence. old and new value have equal length but are not equal. old = " + oldvalue + ", new = " + newvalue);
                            //entries.put(key, newvalue);
                        }
                    }
                } else {
                    entries.put(key, newvalue);
                }
            }
        }
        br.close();
        if (structure == null) throw new IOException("file contains no structure tag");
        if (name == null) throw new IOException("file contains no name tag");
        if (created == -1) throw new IOException("file contains no created tag");
    }
    
    public int size() {
        return entries.size();
    }
    
    public long created() {
        return this.created;
    }
    
    public void toFile(final File out) throws IOException {
        // generate header
        final StringBuilder sb = new StringBuilder(2000);
        sb.append("# Name=" + this.name); sb.append((char) 13); sb.append((char) 10);
        sb.append("# Created=" + this.created); sb.append((char) 13); sb.append((char) 10);
        sb.append("# Structure=" + this.structure.toString()); sb.append((char) 13); sb.append((char) 10);
        sb.append("# ---"); sb.append((char) 13); sb.append((char) 10);
        final Iterator<Map.Entry<String, Object>> i = entries.entrySet().iterator();
        Map.Entry<String, Object> entry;
        String k;
        Object v;
        while (i.hasNext()) {
            entry = i.next();
            k = entry.getKey();
            v = entry.getValue();
            sb.append(k); sb.append('=');
            if (v instanceof String) sb.append((String) v);
            if (v instanceof Entry) sb.append(((Entry) v).toString());
            sb.append((char) 13); sb.append((char) 10);
        }
        if (out.toString().endsWith(".gz")) {
            FileUtils.writeAndGZip((new String(sb)).getBytes(), out);
        } else {
            FileUtils.copy((new String(sb)).getBytes(), out);
        }
    }
    
    public Iterator<String> keys() {
        return entries.keySet().iterator();
    }
    
    public Entry newEntry(final String pivot, final boolean tree) {
        return new Entry(pivot, new HashMap<String, Long>(), (tree) ? (Set<String>) new TreeSet<String>() : (Set<String>) new HashSet<String>());
    }
    
    public Entry newEntry(final String pivot, final HashMap<String, Long> props, final Set<String> seq) {
        return new Entry(pivot, props, seq);
    }
    
    /*
    public void putEntry(String pivot, String attrseq) {
        entries.put(pivot, attrseq);
    }
    */
    
    public void putEntry(final Entry entry) {
        if (shortmem())
            entries.put(entry.pivot, entry.toString());
        else
            entries.put(entry.pivot, entry);
    }
    
    public void putEntrySmall(final Entry entry) {
        entries.put(entry.pivot, entry.toString());
    }
    
    public Entry getEntry(final String pivot) {
        final Object e = entries.get(pivot);
        if (e == null) return null;
        if (e instanceof String) return new Entry(pivot, (String) e, false);
        if (e instanceof Entry) return (Entry) e;
        return null;
    }
   
    public Entry removeEntry(final String pivot) {
        final Object e = entries.remove(pivot);
        if (e == null) return null;
        if (e instanceof String) return new Entry(pivot, (String) e, false);
        if (e instanceof Entry) return (Entry) e;
        return null;
    }
   
    public static class Structure {
        
        protected String   pivot_name = null;
        protected int      pivot_len = -1;
        protected String[] prop_names = null;
        protected int[]    prop_len = null, prop_pos = null;
        protected String[] seq_names = null;
        protected int[]    seq_len = null, seq_pos = null;
        protected Row seqrow;
        // example:
        //# Structure=<pivot-12>,'=',<UDate-3>,<VDate-3>,<LCount-2>,<GCount-2>,<ICount-2>,<DCount-2>,<TLength-3>,<WACount-3>,<WUCount-3>,<Flags-1>,'|',*<Anchor-12>

        public Structure(String structure) {
            // parse a structure string
            
            // parse pivot definition:
            int p = structure.indexOf(",'='");
            if (p < 0) return;
            final String pivot = structure.substring(0, p);
            structure = structure.substring(p + 5);
            Column a = new Column(pivot);
            pivot_name = a.nickname;
            pivot_len = a.cellwidth;
            
            // parse property part definition:
            p = structure.indexOf(",'|'");
            if (p < 0) return;
            ArrayList<Column> l = new ArrayList<Column>();
            final String attr = structure.substring(0, p);
            String seqs = structure.substring(p + 5);
            StringTokenizer st = new StringTokenizer(attr, ",");
            while (st.hasMoreTokens()) {
                a = new Column(st.nextToken());
                l.add(a);
            }
            prop_names = new String[l.size()];
            prop_len = new int[l.size()];
            prop_pos = new int[l.size()];
            p = 0;
            for (int i = 0; i < l.size(); i++) {
                a = l.get(i);
                prop_names[i] = a.nickname;
                prop_len[i] = a.cellwidth;
                prop_pos[i] = p;
                p += prop_len[i];
            }
            
            // parse sequence definition:
            if (seqs.startsWith("*")) seqs = seqs.substring(1);
            l = new ArrayList<Column>();
            st = new StringTokenizer(seqs, ",");
            while (st.hasMoreTokens()) {
                a = new Column(st.nextToken());
                l.add(a);
            }
            seq_names = new String[l.size()];
            seq_len = new int[l.size()];
            seq_pos = new int[l.size()];
            p = 0;
            for (int i = 0; i < l.size(); i++) {
                a = l.get(i);
                seq_names[i] = a.nickname;
                seq_len[i] = a.cellwidth;
                seq_pos[i] = p;
                p += seq_len[i];
            }
            
            // generate rowdef for seq row definition
            final StringBuilder rowdef = new StringBuilder();
            rowdef.append("byte[] ");
            rowdef.append(seq_names[0]);
            rowdef.append('-');
            rowdef.append(seq_len[0]);
            
            for (int i = 1; i < seq_names.length; i++) {
                rowdef.append(", byte[] ");
                rowdef.append(seq_names[i]);
                rowdef.append('-');
                rowdef.append(seq_len[i]);
            }
            seqrow = new Row(new String(rowdef), null);
        }
        
        public String toString() {
            final StringBuilder sb = new StringBuilder(100);
            sb.append('<'); sb.append(pivot_name); sb.append('-'); sb.append(Integer.toString(pivot_len)); sb.append(">,'=',");
            if (prop_names.length > 0) {
                for (int i = 0; i < prop_names.length; i++) {
                    sb.append('<'); sb.append(prop_names[i]); sb.append('-'); sb.append(Integer.toString(prop_len[i])); sb.append(">,");
                }
            }
            sb.append("'|'");
            if (seq_names.length > 0) {
                for (int i = 0; i < seq_names.length; i++) {
                    sb.append(",<"); sb.append(seq_names[i]); sb.append('-'); sb.append(Integer.toString(seq_len[i])); sb.append('>');
                }
            }
            return new String(sb);
        }
    }
    
    public class Entry {
        String                pivot;
        HashMap<String, Long> attrs;
        Set<String>           seq;
        
        public Entry(final String pivot, final HashMap<String, Long> attrs, final Set<String> seq) {
            this.pivot = pivot;
            this.attrs = attrs;
            this.seq = seq;
        }
        
        public Entry(final String pivot, final String attrseq, final boolean tree) {
            this.pivot = pivot;
            attrs = new HashMap<String, Long>();
            seq = (tree) ? (Set<String>) new TreeSet<String>() : (Set<String>) new HashSet<String>();
            for (int i = 0; i < structure.prop_names.length; i++) {
                attrs.put(structure.prop_names[i], Long.valueOf(Base64Order.enhancedCoder.decodeLong(attrseq.substring(structure.prop_pos[i], structure.prop_pos[i] + structure.prop_len[i]))));
            }
            
            int p = attrseq.indexOf('|') + 1;
            //long[] seqattrs = new long[structure.seq_names.length - 1];
            String seqname;
            while (p + structure.seq_len[0] <= attrseq.length()) {
                seqname = attrseq.substring(p, p + structure.seq_len[0]);
                p += structure.seq_len[0];
                for (int i = 1; i < structure.seq_names.length; i++) {
                    //seqattrs[i - 1] = kelondroBase64Order.enhancedCoder.decodeLong(attrseq.substring(p, p + structure.seq_len[i]));
                    p += structure.seq_len[i];
                }
                seq.add(seqname/*, seqattrs*/);
            }
        }
        
        public HashMap<String, Long> getAttrs() {
            return attrs;
        }
        
        public long getAttr(final String key, final long dflt) {
            final Long i = attrs.get(key);
            if (i == null) return dflt;
            return i.longValue();
        }
        
        public void setAttr(final String key, final long attr) {
            attrs.put(key, Long.valueOf(attr));
        }
        
        public Set<String> getSeqSet() {
            return seq;
        }
        
        public RowCollection getSeqCollection() throws RowSpaceExceededException {
            final RowCollection collection = new RowCollection(structure.seqrow, seq.size());
            final Iterator<String> i = seq.iterator();
            while (i.hasNext()) {
                collection.addUnique(structure.seqrow.newEntry(i.next().getBytes()));
            }
            return collection;
        }
        
        public void setSeq(final Set<String> seq) {
            this.seq = seq;
        }
        
        public void addSeq(final String s/*, long[] seqattrs*/) {
            this.seq.add(s/*, seqattrs*/);
        }
        
        public String toString() {
            // creates only the attribute field and the sequence, not the pivot
            final StringBuilder sb = new StringBuilder(100 + structure.seq_len[0] * seq.size());
            Long val;
            for (int i = 0; i < structure.prop_names.length; i++) {
                val = attrs.get(structure.prop_names[i]);
                sb.append(Base64Order.enhancedCoder.encodeLongSmart((val == null) ? 0 : val.longValue(), structure.prop_len[i]));
            }
            sb.append('|');
            final Iterator<String> q = seq.iterator();
            //long[] seqattrs;
            while (q.hasNext()) {
                sb.append(q.next());
                //seqattrs = (long[]) entry.getValue();
                /*
                for (int i = 1; i < structure.seq_names.length; i++) {
                    sb.append(kelondroBase64Order.enhancedCoder.encodeLong(seqattrs[i - 1], structure.seq_len[i]));
                }
                */
            }
            return new String(sb);
        }
    }
    
    private static final long cc = 0;
    private static boolean shortmemstate = false;
    private static boolean shortmem() {
        if ((cc % 300) == 0) {
            shortmemstate = (MemoryControl.available() < 20000000L);
        }
        return shortmemstate;
    }
    
    public static void transcode(final File from_file, final File to_file) throws IOException {
        final AttrSeq crp = new AttrSeq(from_file, true);
        //crp.toFile(new File(args[1]));
        final AttrSeq cro = new AttrSeq(crp.name + "/Transcoded from " + crp.file.getName(), crp.structure.toString(), true);
        final Iterator<String> i = crp.entries.keySet().iterator();
        while (i.hasNext()) {
            cro.putEntry(crp.getEntry(i.next()));
        }
        cro.toFile(to_file);
    }
    
    public static void main(final String[] args) {
        // java -classpath source de.anomic.kelondro.kelondroPropFile -transcode DATA/RANKING/GLOBAL/CRG-test-unsorted-original.cr DATA/RANKING/GLOBAL/CRG-test-generated.cr
        try {
            if ((args.length == 3) && (args[0].equals("-transcode"))) {
                transcode(new File(args[1]), new File(args[2]));
            }
        } catch (final IOException e) {
            Log.logException(e);
        }
    }
    
}
