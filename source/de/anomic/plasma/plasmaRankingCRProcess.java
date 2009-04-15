// plasmaCRProcess.java
// -----------------------
// part of YaCy
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2005
// Created 15.11.2005
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
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

package de.anomic.plasma;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import de.anomic.kelondro.index.Row;
import de.anomic.kelondro.index.RowSet;
import de.anomic.kelondro.index.ObjectIndex;
import de.anomic.kelondro.order.Base64Order;
import de.anomic.kelondro.order.Bitfield;
import de.anomic.kelondro.order.CloneableIterator;
import de.anomic.kelondro.order.MicroDate;
import de.anomic.kelondro.table.EcoTable;
import de.anomic.kelondro.text.IndexCollection;
import de.anomic.kelondro.text.ReferenceContainer;
import de.anomic.kelondro.text.referencePrototype.WordReference;
import de.anomic.kelondro.util.DateFormatter;
import de.anomic.kelondro.util.MemoryControl;
import de.anomic.kelondro.util.AttrSeq;
import de.anomic.kelondro.util.FileUtils;

public class plasmaRankingCRProcess {
    
    /*
    header.append("# Name=YaCy " + ((type.equals("crl")) ? "Local" : "Global") + " Citation Reference Ticket"); header.append((char) 13); header.append((char) 10);
    header.append("# Created=" + System.currentTimeMillis()); header.append((char) 13); header.append((char) 10);
    header.append("# Structure=<Referee-12>,'=',<UDate-3>,<VDate-3>,<LCount-2>,<GCount-2>,<ICount-2>,<DCount-2>,<TLength-3>,<WACount-3>,<WUCount-3>,<Flags-1>,'|',*<Anchor-" + ((type.equals("crl")) ? "6" : "12") + ">"); header.append((char) 13); header.append((char) 10);
    header.append("# ---"); header.append((char) 13); header.append((char) 10);
    */
    
    public static final Row CRG_accrow = new Row(
            "byte[] Referee-12," +
            "Cardinal UDate-3 {b64e}, Cardinal VDate-3 {b64e}, " +
            "Cardinal LCount-2 {b64e}, Cardinal GCount-2 {b64e}, Cardinal ICount-2 {b64e}, Cardinal DCount-2 {b64e}, Cardinal TLength-3 {b64e}, " +
            "Cardinal WACount-3 {b64e}, Cardinal WUCount-3 {b64e}, Cardinal Flags-1 {b64e}, " +
            "Cardinal FUDate-3 {b64e}, Cardinal FDDate-3 {b64e}, Cardinal LUDate-3 {b64e}, " + 
            "Cardinal UCount-2 {b64e}, Cardinal PCount-2 {b64e}, Cardinal ACount-2 {b64e}, Cardinal VCount-2 {b64e}, Cardinal Vita-2 {b64e}",
            Base64Order.enhancedCoder);
    public static final Row CRG_colrow = new Row("byte[] Anchor-12", Base64Order.enhancedCoder);
    public static final String CRG_accname = "CRG-a-attr";
    public static final String CRG_seqname = "CRG-a-coli";
    public static final Row RCI_coli = new Row("byte[] RefereeDom-6", Base64Order.enhancedCoder);
    public static final String RCI_colname = "RCI-a-coli";

    private static boolean accumulate_upd(final File f, final AttrSeq acc) {
        // open file
        AttrSeq source_cr = null;
        try {
            source_cr = new AttrSeq(f, false);
        } catch (final IOException e) {
            return false;
        }
        
        // put elements in accumulator file
        final Iterator<String> el = source_cr.keys();
        String key;
        AttrSeq.Entry new_entry, acc_entry;
        int FUDate, FDDate, LUDate, UCount, PCount, ACount, VCount, Vita;
        Bitfield acc_flags, new_flags;
        while (el.hasNext()) {
            key = el.next();
            new_entry = source_cr.getEntry(key);
            new_flags = new Bitfield(Base64Order.enhancedCoder.encodeLong(new_entry.getAttr("Flags", 0), 1).getBytes());
            // enrich information with additional values
            if ((acc_entry = acc.getEntry(key)) != null) {
                FUDate = (int) acc_entry.getAttr("FUDate", 0);
                FDDate = (int) acc_entry.getAttr("FDDate", 0);
                LUDate = (int) acc_entry.getAttr("LUDate", 0);
                UCount = (int) acc_entry.getAttr("UCount", 0);
                PCount = (int) acc_entry.getAttr("PCount", 0);
                ACount = (int) acc_entry.getAttr("ACount", 0);
                VCount = (int) acc_entry.getAttr("VCount", 0);
                Vita   = (int) acc_entry.getAttr("Vita", 0);
                
                // update counters and dates
                acc_entry.setSeq(new_entry.getSeqSet()); // need to be checked
                
                UCount++; // increase update counter
                PCount += (new_flags.get(1)) ? 1 : 0;
                ACount += (new_flags.get(2)) ? 1 : 0;
                VCount += (new_flags.get(3)) ? 1 : 0;
                
                // 'OR' the flags
                acc_flags = new Bitfield(Base64Order.enhancedCoder.encodeLong(acc_entry.getAttr("Flags", 0), 1).getBytes());
                for (int i = 0; i < 6; i++) {
                    if (new_flags.get(i)) acc_flags.set(i, true);
                }
                acc_entry.setAttr("Flags", (int) Base64Order.enhancedCoder.decodeLong(acc_flags.exportB64()));
            } else {
                // initialize counters and dates
                acc_entry = acc.newEntry(key, new_entry.getAttrs(), new_entry.getSeqSet());
                FUDate = MicroDate.microDateHoursInt(System.currentTimeMillis()); // first update date
                FDDate = MicroDate.microDateHoursInt(System.currentTimeMillis()); // very difficult to compute; this is only a quick-hack
                LUDate = (int) new_entry.getAttr("VDate", 0);
                UCount = 0;
                PCount = (new_flags.get(1)) ? 1 : 0;
                ACount = (new_flags.get(2)) ? 1 : 0;
                VCount = (new_flags.get(3)) ? 1 : 0;
                Vita   = 0;
            }
            // make plausibility check?
            
            // insert into accumulator
            acc_entry.setAttr("FUDate", FUDate);
            acc_entry.setAttr("FDDate", FDDate);
            acc_entry.setAttr("LUDate", LUDate);
            acc_entry.setAttr("UCount", UCount);
            acc_entry.setAttr("PCount", PCount);
            acc_entry.setAttr("ACount", ACount);
            acc_entry.setAttr("VCount", VCount);
            acc_entry.setAttr("Vita", Vita);
            acc.putEntrySmall(acc_entry);
        }
        
        return true;
    }
    
    private static boolean accumulate_upd(final File f, final ObjectIndex acc, final IndexCollection<WordReference> seq) throws IOException {
        // open file
        AttrSeq source_cr = null;
        try {
            source_cr = new AttrSeq(f, false);
        } catch (final IOException e) {
            return false;
        }
        
        // put elements in accumulator file
        final Iterator<String> el = source_cr.keys();
        String key;
        AttrSeq.Entry new_entry;
        Row.Entry acc_entry;
        int FUDate, FDDate, LUDate, UCount, PCount, ACount, VCount, Vita;
        Bitfield acc_flags, new_flags;
        while (el.hasNext()) {
            key = el.next();
            new_entry = source_cr.getEntry(key);
            new_flags = new Bitfield(Base64Order.enhancedCoder.encodeLong(new_entry.getAttr("Flags", 0), 1).getBytes());
            // enrich information with additional values
            if ((acc_entry = acc.get(key.getBytes())) != null) {
                FUDate = (int) acc_entry.getColLong("FUDate", 0);
                FDDate = (int) acc_entry.getColLong("FDDate", 0);
                LUDate = (int) acc_entry.getColLong("LUDate", 0);
                UCount = (int) acc_entry.getColLong("UCount", 0);
                PCount = (int) acc_entry.getColLong("PCount", 0);
                ACount = (int) acc_entry.getColLong("ACount", 0);
                VCount = (int) acc_entry.getColLong("VCount", 0);
                Vita   = (int) acc_entry.getColLong("Vita", 0);
                
                // update counters and dates
                seq.put(key.getBytes(), new_entry.getSeqCollection()); // FIXME: old and new collection must be joined
                
                UCount++; // increase update counter
                PCount += (new_flags.get(1)) ? 1 : 0;
                ACount += (new_flags.get(2)) ? 1 : 0;
                VCount += (new_flags.get(3)) ? 1 : 0;
                
                // 'OR' the flags
                acc_flags = new Bitfield(Base64Order.enhancedCoder.encodeLong(acc_entry.getColLong("Flags", 0), 1).getBytes());
                for (int i = 0; i < 6; i++) {
                    if (new_flags.get(i)) acc_flags.set(i, true);
                }
                acc_entry.setCol("Flags", (int) Base64Order.enhancedCoder.decodeLong(acc_flags.exportB64()));
            } else {
                // initialize counters and dates
                acc_entry = acc.row().newEntry();
                acc_entry.setCol("Referee", key, null);
                for (int i = 1; i < acc.row().columns(); i++) {
                    acc_entry.setCol(i, new_entry.getAttr(acc.row().column(i).nickname, 0));
                }
                seq.put(key.getBytes(), new_entry.getSeqCollection());
                FUDate = MicroDate.microDateHoursInt(System.currentTimeMillis()); // first update date
                FDDate = MicroDate.microDateHoursInt(System.currentTimeMillis()); // very difficult to compute; this is only a quick-hack
                LUDate = (int) new_entry.getAttr("VDate", 0);
                UCount = 0;
                PCount = (new_flags.get(1)) ? 1 : 0;
                ACount = (new_flags.get(2)) ? 1 : 0;
                VCount = (new_flags.get(3)) ? 1 : 0;
                Vita   = 0;
            }
            // make plausibility check?
            
            // insert into accumulator
            acc_entry.setCol("FUDate", FUDate);
            acc_entry.setCol("FDDate", FDDate);
            acc_entry.setCol("LUDate", LUDate);
            acc_entry.setCol("UCount", UCount);
            acc_entry.setCol("PCount", PCount);
            acc_entry.setCol("ACount", ACount);
            acc_entry.setCol("VCount", VCount);
            acc_entry.setCol("Vita", Vita);
            acc.put(acc_entry);
        }
        
        return true;
    }
    
    public static void accumulate(final File from_dir, final File tmp_dir, final File err_dir, final File bkp_dir, final File to_file, int max_files, final boolean newdb) throws IOException {
        if (!(from_dir.isDirectory())) {
            System.out.println("source path " + from_dir + " is not a directory.");
            return;
        }
        if (!(tmp_dir.isDirectory())) {
            System.out.println("temporary path " + tmp_dir + " is not a directory.");
            return;
        }
        if (!(err_dir.isDirectory())) {
            System.out.println("error path " + err_dir + " is not a directory.");
            return;
        }
        if (!(bkp_dir.isDirectory())) {
            System.out.println("back-up path " + bkp_dir + " is not a directory.");
            return;
        }
        
        // open target file
        AttrSeq acc = null;
        ObjectIndex newacc = null;
        IndexCollection<WordReference> newseq = null;
        if (newdb) {
            final File path = to_file.getParentFile(); // path to storage place
            newacc = new EcoTable(new File(path, CRG_accname), CRG_accrow, EcoTable.tailCacheUsageAuto, 0, 0);
            newseq = new IndexCollection<WordReference>(path, CRG_seqname, plasmaWordIndex.wordReferenceFactory, 12, Base64Order.enhancedCoder, 9, CRG_colrow, false);
        } else {
            if (!(to_file.exists())) {
                acc = new AttrSeq("Global Ranking Accumulator File",
                    "<Referee-12>,'='," +
                    "<UDate-3>,<VDate-3>,<LCount-2>,<GCount-2>,<ICount-2>,<DCount-2>,<TLength-3>,<WACount-3>,<WUCount-3>,<Flags-1>," +
                    "<FUDate-3>,<FDDate-3>,<LUDate-3>,<UCount-2>,<PCount-2>,<ACount-2>,<VCount-2>,<Vita-2>," +
                    "'|',*<Anchor-12>", false);
                acc.toFile(to_file);
            }
            acc = new AttrSeq(to_file, false);
        }        
        // collect source files
        File source_file = null;
        final String[] files = from_dir.list();
        if (files.length < max_files) max_files = files.length;
        for (int i = 0; i < max_files; i++) {
            // open file
            source_file = new File(from_dir, files[i]);
            if (newdb) {
                if (accumulate_upd(source_file, newacc, newseq)) {
                    // move CR file to temporary folder
                    source_file.renameTo(new File(tmp_dir, files[i]));
                } else {
                    // error case: the CR-file is not valid; move to error path
                    source_file.renameTo(new File(err_dir, files[i]));
                }
            } else {
                if (accumulate_upd(source_file, acc)) {
                    // move CR file to temporary folder
                    source_file.renameTo(new File(tmp_dir, files[i]));
                } else {
                    // error case: the CR-file is not valid; move to error path
                    source_file.renameTo(new File(err_dir, files[i]));
                }
            }
        }
        
        try {
            if (newdb) {
                newacc.close();
                newseq.close();
            } else {
                // save accumulator to temporary file
                File tmp_file;
                if (to_file.toString().endsWith(".gz")) {
                    tmp_file = new File(to_file.toString() + "." + (System.currentTimeMillis() % 1000) + ".tmp.gz");
                } else {
                    tmp_file = new File(to_file.toString() + "." + (System.currentTimeMillis() % 1000) + ".tmp");
                }
                // store the file
                acc.toFile(tmp_file);
                // since this was successful, we remove the old file and move the new file to it
                FileUtils.deletedelete(to_file);
                tmp_file.renameTo(to_file);
            }
            FileUtils.moveAll(tmp_dir, bkp_dir);
        } catch (final IOException e) {
            // move previously processed files back
            e.printStackTrace();
            FileUtils.moveAll(tmp_dir, from_dir);
        }
        
    }
    
    public static int genrci(File cr_in, final File rci_out) throws IOException {
        if (!(cr_in.exists())) return 0;
        AttrSeq cr = new AttrSeq(cr_in, false);
        //if (rci_out.exists()) FileUtils.deletedelete(rci_out); // we want only fresh rci here (during testing) 
        if (!(rci_out.exists())) {
            final AttrSeq rcix = new AttrSeq("Global Ranking Reverse Citation Index",
                    "<AnchorDom-6>,'='," +
                    "<UDate-3>," +
                    "'|',*<Referee-12>", false);
            rcix.toFile(rci_out);
        }
        final AttrSeq rci = new AttrSeq(rci_out, false);
        
        // loop over all referees
        int count = 0;
        final int size = cr.size();
        final long start = System.currentTimeMillis();
        long l;
        final Iterator<String> i = cr.keys();
        String referee, anchor, anchorDom;
        AttrSeq.Entry cr_entry, rci_entry;
        long cr_UDate, rci_UDate;
        while (i.hasNext()) {
            referee = i.next();
            cr_entry = cr.getEntry(referee);
            cr_UDate = cr_entry.getAttr("UDate", 0);
            
            // loop over all anchors
            final Iterator<String> j = cr_entry.getSeqSet().iterator();
            while (j.hasNext()) {
                // get domain of anchors
                anchor = j.next();
                if (anchor.length() == 6) anchorDom = anchor; else anchorDom = anchor.substring(6);

                // update domain-specific entry
                rci_entry = rci.getEntry(anchorDom);
                if (rci_entry == null) rci_entry = rci.newEntry(anchorDom, false);
                rci_entry.addSeq(referee);
                
                // update Update-Date
                rci_UDate = rci_entry.getAttr("UDate", 0);
                if (cr_UDate > rci_UDate) rci_entry.setAttr("UDate", cr_UDate);
                
                // insert entry
                rci.putEntry(rci_entry);
            }
            count++;
            if ((count % 1000) == 0) {
                l = java.lang.Math.max(1, (System.currentTimeMillis() - start) / 1000);
                System.out.println("processed " + count + " citations, " + (count / l) + " per second, rci.size = " + rci.size() + ", " + ((size - count) / (count / l)) + " seconds remaining; mem = " + MemoryControl.available());  
            }
            i.remove();
        }

        // finished. write to file
        cr = null;
        cr_in = null;
        rci.toFile(rci_out);
        return count;
    }
    
    public static int genrcix(final File cr_path_in, final File rci_path_out) throws IOException {
        //kelondroFlexTable       acc = new kelondroFlexTable(cr_path_in, CRG_accname, kelondroBase64Order.enhancedCoder, 128 * 1024 * 1024, -1, CRG_accrow, true);
        final IndexCollection<WordReference> seq = new IndexCollection<WordReference>(cr_path_in, CRG_seqname, plasmaWordIndex.wordReferenceFactory, 12, Base64Order.enhancedCoder, 9, CRG_colrow, false);
        final IndexCollection<WordReference> rci = new IndexCollection<WordReference>(rci_path_out, RCI_colname, plasmaWordIndex.wordReferenceFactory, 6, Base64Order.enhancedCoder, 9, RCI_coli, false);
        
        // loop over all referees
        int count = 0;
        final int size = seq.size();
        final long start = System.currentTimeMillis();
        long l;
        final CloneableIterator<ReferenceContainer<WordReference>> i = seq.references(null, false);
        ReferenceContainer<WordReference> keycollection;
        String referee, refereeDom, anchor, anchorDom;
        RowSet rci_entry;
        CloneableIterator<Row.Entry> cr_entry;
        while (i.hasNext()) {
            keycollection = i.next();
            referee = keycollection.getTermHash();
            if (referee.length() == 6) refereeDom = referee; else refereeDom = referee.substring(6);
            cr_entry = keycollection.rows();
            
            // loop over all anchors
            Row.Entry entry;
            while (cr_entry.hasNext()) {
            	entry = cr_entry.next();
                anchor = entry.getColString(0, null);
                if (anchor.length() == 6) anchorDom = anchor; else anchorDom = anchor.substring(6);

                // update domain-specific entry
                rci_entry = rci.get(anchorDom, null);
                if (rci_entry == null) rci_entry = new RowSet(RCI_coli, 0);
                rci_entry.add(refereeDom.getBytes());
                
                // insert entry
                rci.put(anchorDom.getBytes(), rci_entry);
            }
            count++;
            if ((count % 1000) == 0) {
                l = java.lang.Math.max(1, (System.currentTimeMillis() - start) / 1000);
                System.out.println("processed " + count + " citations, " + (count / l) + " per second, rci.size = " + rci.size() + ", " + ((size - count) / (count / l) / 60) + " minutes remaining; mem = " + MemoryControl.free());
            }
        }

        // finished. write to file
        seq.close();
        rci.close();
        return count;
    }
    
    public static void main(final String[] args) {
        // java -classpath source de.anomic.plasma.kelondroPropFile -transcode DATA/RANKING/GLOBAL/CRG-test-unsorted-original.cr DATA/RANKING/GLOBAL/CRG-test-generated.cr
        try {
            if ((args.length == 5) && (args[0].equals("-accumulate"))) {
                accumulate(new File(args[1]), new File(args[2]), new File(args[3]), new File(args[4]), new File(args[5]), Integer.parseInt(args[6]), true);
            }
            if ((args.length == 2) && (args[0].equals("-accumulate"))) {
                final File root_path = new File(args[1]);
                final File from_dir = new File(root_path, "DATA/RANKING/GLOBAL/014_othercr");
                final File ready_dir = new File(root_path, "DATA/RANKING/GLOBAL/015_ready");
                final File tmp_dir = new File(root_path, "DATA/RANKING/GLOBAL/016_tmp");
                final File err_dir = new File(root_path, "DATA/RANKING/GLOBAL/017_err");
                final File acc_dir = new File(root_path, "DATA/RANKING/GLOBAL/018_acc");
                final String filename = "CRG-a-" + new DateFormatter().toShortString(true) + ".cr.gz";
                final File to_file = new File(root_path, "DATA/RANKING/GLOBAL/020_con0/" + filename);
                if (!(ready_dir.exists())) ready_dir.mkdirs();
                if (!(tmp_dir.exists())) tmp_dir.mkdirs();
                if (!(err_dir.exists())) err_dir.mkdirs();
                if (!(acc_dir.exists())) acc_dir.mkdirs();
                if (!(to_file.getParentFile().exists())) to_file.getParentFile().mkdirs();
                FileUtils.moveAll(from_dir, ready_dir);
                final long start = System.currentTimeMillis();
                final int files = ready_dir.list().length;
                accumulate(ready_dir, tmp_dir, err_dir, acc_dir, to_file, 1000, true);
                final long seconds = java.lang.Math.max(1, (System.currentTimeMillis() - start) / 1000);
                System.out.println("Finished accumulate for " + files + " files in " + seconds + " seconds (" + (files / seconds) + " files/second)");
            }
            if ((args.length == 3) && (args[0].equals("-recycle"))) {
                final File root_path = new File(args[1]);
                final int max_age_hours = Integer.parseInt(args[2]);
                final File own_dir = new File(root_path, "DATA/RANKING/GLOBAL/010_owncr");
                final File acc_dir = new File(root_path, "DATA/RANKING/GLOBAL/018_acc");
                final File bkp_dir = new File(root_path, "DATA/RANKING/GLOBAL/019_bkp");
                if (!(own_dir.exists())) return;
                if (!(acc_dir.exists())) return;
                if (!(bkp_dir.exists())) bkp_dir.mkdirs();
                final String[] list = acc_dir.list();
                final long start = System.currentTimeMillis();
                final int files = list.length;
                long d;
                File f;
                for (int i = 0; i < list.length; i++) {
                    f = new File(acc_dir, list[i]);
                    try {
                        d = (System.currentTimeMillis() - (new AttrSeq(f, false)).created()) / 3600000;
                        if (d > max_age_hours) {
                            // file is considered to be too old, it is not recycled
                            System.out.println("file " + f.getName() + " is old (" + d + " hours) and not recycled, only moved to backup");
                            f.renameTo(new File(bkp_dir, list[i]));
                        } else {
                            // file is fresh, it is duplicated and moved to be transferred to other peers again
                            System.out.println("file " + f.getName() + " is fresh (" + d + " hours old), recycled and moved to backup");
                            FileUtils.copy(f, new File(own_dir, list[i]));
                            f.renameTo(new File(bkp_dir, list[i]));
                        }
                    } catch (final IOException e) {
                        // there is something wrong with this file; delete it
                        System.out.println("file " + f.getName() + " is corrupted and deleted");
                        FileUtils.deletedelete(f);
                    }
                }
                final long seconds = java.lang.Math.max(1, (System.currentTimeMillis() - start) / 1000);
                System.out.println("Finished recycling of " + files + " files in " + seconds + " seconds (" + (files / seconds) + " files/second)");
            }
            if ((args.length == 2) && (args[0].equals("-genrci"))) {
                final File root_path = new File(args[1]);
                final File cr_filedir = new File(root_path, "DATA/RANKING/GLOBAL/020_con0");
                final File rci_filedir = new File(root_path, "DATA/RANKING/GLOBAL/030_rci0");
                rci_filedir.mkdirs();
                final long start = System.currentTimeMillis();
                final int count = genrcix(cr_filedir, rci_filedir);
                final long seconds = java.lang.Math.max(1, (System.currentTimeMillis() - start) / 1000);
                System.out.println("Completed RCI generation: " + count + " citation references in " + seconds + " seconds (" + (count / seconds) + " CR-records/second)");                
            }
            /*
            if ((args.length == 2) && (args[0].equals("-genrci"))) {
                File root_path = new File(args[1]);
                File cr_filedir = new File(root_path, "DATA/RANKING/GLOBAL/020_con0");
                File rci_file = new File(root_path, "DATA/RANKING/GLOBAL/030_rci0/RCI-0.rci.gz");
                rci_file.getParentFile().mkdirs();
                String[] cr_filenames = cr_filedir.list();
                for (int i = 0; i < cr_filenames.length; i++) {
                    long start = System.currentTimeMillis();
                    int count = genrci(new File(cr_filedir, cr_filenames[i]), rci_file);
                    long seconds = java.lang.Math.max(1, (System.currentTimeMillis() - start) / 1000);
                    System.out.println("Completed RCI generation for input file " + cr_filenames[i] + ": " + count + " citation references in " + seconds + " seconds (" + (count / seconds) + " CR-records/second)");
                }
            }
            */
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }
    
    /*
      Class-A File format:
      
      UDate  : latest update timestamp of the URL (as virtual date, hours since epoch)
      VDate  : last visit timestamp of the URL (as virtual date, hours since epoch)
      LCount : count of links to local resources
      GCount : count of links to global resources
      ICount : count of links to images (in document)
      DCount : count of links to other documents
      TLength: length of the plain text content (bytes)
      WACount: total number of all words in content
      WUCount: number of unique words in content (removed doubles)
      Flags  : Flags (0=update, 1=popularity, 2=attention, 3=vote)
     
      Class-a File format is an extension of Class-A plus the following attributes
      FUDate : first update timestamp of the URL
      FDDate : first update timestamp of the domain
      LUDate : latest update timestamp of the URL
      UCount : Update Counter (of 'latest update timestamp')
      PCount : Popularity Counter (proxy clicks)
      ACount : Attention Counter (search result clicks)
      VCount : Votes
      Vita   : Vitality (normed number of updates per time)
     */
}
