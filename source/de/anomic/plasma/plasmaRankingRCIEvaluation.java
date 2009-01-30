// plasmaRCIEvaluation.java
// -----------------------
// part of YaCy
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2005
// Created 18.11.2005
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

package de.anomic.plasma;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.TreeSet;

import de.anomic.kelondro.order.Base64Order;
import de.anomic.kelondro.order.Digest;
import de.anomic.kelondro.util.AttrSeq;
import de.anomic.server.serverFileUtils;
import de.anomic.yacy.yacyURL;

public class plasmaRankingRCIEvaluation {
    
    public static int[] rcieval(final AttrSeq rci) {
        // collect information about which entry has how many references
        // the output is a reference-count:occurrences relation
        final HashMap<Integer, Integer> counts = new HashMap<Integer, Integer>();
        final Iterator<String> i = rci.keys();
        String key;
        AttrSeq.Entry entry;
        Integer count_key, count_count;
        int c, maxcount = 0;
        while (i.hasNext()) {
            key = i.next();
            entry = rci.getEntry(key);
            c = entry.getSeqSet().size();
            if (c > maxcount) maxcount = c;
            count_key = Integer.valueOf(c);
            count_count = counts.get(count_key);
            if (count_count == null) {
                count_count = 1;
            } else {
                count_count = Integer.valueOf(count_count.intValue() + 1);
            }
            counts.put(count_key, count_count);
        }
        final int[] ctable = new int[maxcount + 1];
        for (int j = 0; j <= maxcount; j++) {
            count_count = counts.get(Integer.valueOf(j));
            if (count_count == null) {
                ctable[j] = 0;
            } else {
                ctable[j] = count_count.intValue();
            }
        }
        return ctable;
    }
    
    public static long sum(final int[] c) {
        long s = 0;
        for (int i = 0; i < c.length; i++) s += c[i];
        return s;
    }
    
    public static int[] interval(final int[] counts, final int parts) {
        long limit = sum(counts) / 2;
        final int[] partition = new int[parts];
        int s = 0, p = parts - 1;
        for (int i = 1; i < counts.length; i++) {
            s += counts[i];
            if ((s > limit) && (p >= 0)) {
                partition[p--] = i;
                limit = (2 * limit - s) / 2;
                s = 0;
            }
        }
        partition[0] = counts.length - 1;
        for (int i = 1; i < 10; i++) partition[i] = (partition[i - 1] + 4 * partition[i]) / 5;
        return partition;
    }
    
    public static void checkPartitionTable0(final int[] counts, final int[] partition) {
        int sumsum = 0;
        int sum;
        int j = 0;
        for (int i = partition.length - 1; i >= 0; i--) {
            sum = 0;
            while (j <= partition[i]) {
                sum += counts[j++];
            }
            System.out.println("sum of YBR-" + i + " entries: " + sum);
            sumsum += sum;
        }
        System.out.println("complete sum = " + sumsum);
    }
    
    public static void checkPartitionTable1(final int[] counts, final int[] partition) {
        int sumsum = 0;
        final int[] sum = new int[partition.length];
        for (int i = 0; i < partition.length; i++) sum[i] = 0;
        for (int i = 0; i < counts.length; i++) sum[orderIntoYBI(partition, i)] += counts[i];
        for (int i = partition.length - 1; i >= 0; i--) {
            System.out.println("sum of YBR-" + i + " entries: " + sum[i]);
            sumsum += sum[i];
        }
        System.out.println("complete sum = " + sumsum);
    }
    
    public static int orderIntoYBI(final int[] partition, final int count) {
        for (int i = 0; i < partition.length - 1; i++) {
            if ((count >= (partition[i + 1] + 1)) && (count <= partition[i])) return i;
        }
        return partition.length - 1;
    }
    
    @SuppressWarnings("unchecked")
    public static TreeSet<String>[] genRankingTable(final AttrSeq rci, final int[] partition) {
        final TreeSet<String>[] ranked = new TreeSet[partition.length];
        for (int i = 0; i < partition.length; i++) ranked[i] = new TreeSet<String>(Base64Order.enhancedComparator);
        final Iterator<String> i = rci.keys();
        String key;
        AttrSeq.Entry entry;
        while (i.hasNext()) {
            key = i.next();
            entry = rci.getEntry(key);
            ranked[orderIntoYBI(partition, entry.getSeqSet().size())].add(key);
        }
        return ranked;
    }

    public static HashMap<String, String> genReverseDomHash(final File domlist) {
        final HashSet<String> domset = serverFileUtils.loadList(domlist);
        final HashMap<String, String> dommap = new HashMap<String, String>();
        final Iterator<String> i = domset.iterator();
        String dom;
        while (i.hasNext()) {
            dom = i.next();
            if (dom.startsWith("www.")) dom = dom.substring(4);
            try {
                dommap.put((new yacyURL("http://" + dom, null)).hash().substring(6), dom);
                dommap.put((new yacyURL("http://www." + dom, null)).hash().substring(6), "www." + dom);
            } catch (final MalformedURLException e) {}
        }
        return dommap;
    }

    public static void storeRankingTable(final TreeSet<String>[] ranking, final File tablePath) throws IOException {
        String filename;
        if (!(tablePath.exists())) tablePath.mkdirs();
        for (int i = 0; i < ranking.length - 1; i++) {
            filename = "YBR-4-" + Digest.encodeHex(i, 2) + ".idx";
            serverFileUtils.saveSet(new File(tablePath, filename), "plain", ranking[i], "");
        }
    }
    
    public static void main(final String[] args) {
        try {
            if ((args.length == 2) && (args[0].equals("-genybr"))) {
                final File root_path = new File(args[1]);
                final File rci_file = new File(root_path, "DATA/RANKING/GLOBAL/030_rci0/RCI-0.rci.gz");
                final long start = System.currentTimeMillis();
                if (!(rci_file.exists())) return;
                
                // create partition table
                final AttrSeq rci = new AttrSeq(rci_file, false);
                final int counts[] = rcieval(rci);
                final int[] partition = interval(counts, 16);
                
                // check the table
                System.out.println("partition position table:");
                for (int i = 0; i < partition.length - 1; i++) {
                    System.out.println("YBR-" + i + ": " + (partition[i + 1] + 1) + " - " + partition[i] + " references");
                }
                System.out.println("YBR-" + (partition.length - 1) + ": 0 - " + partition[partition.length - 1] + " references");
                checkPartitionTable0(counts, partition);
                checkPartitionTable1(counts, partition);
                int sum = 0;
                for (int i = 0; i < counts.length; i++) sum += counts[i];
                System.out.println("sum of all references: " + sum);
                
                // create ranking
                final TreeSet<String>[] ranked = genRankingTable(rci, partition);
                storeRankingTable(ranked, new File(root_path, "ranking/YBR"));
                final long seconds = java.lang.Math.max(1, (System.currentTimeMillis() - start) / 1000);
                System.out.println("Finished YBR generation in " + seconds + " seconds.");
            }
            if ((args.length == 2) && (args[0].equals("-rcieval"))) {
                final File root_path = new File(args[1]);
                
                // load a partition table
                plasmaSearchRankingProcess.loadYBR(new File(root_path, "ranking/YBR"), 16);
                
                // load domain list and generate hash index for domains
                final HashMap<String, String> dommap = genReverseDomHash(new File(root_path, "domlist.txt"));
                
                // print out the table
                String hash, dom;
                for (int i = 0; i < 9; i++) {
                    System.out.print("YBR-" + i + ": ");
                    for (int j = 0; j < plasmaSearchRankingProcess.ybrTables[i].size(); j++) {
                        hash = new String(plasmaSearchRankingProcess.ybrTables[i].get(j));
                        dom = dommap.get(hash);
                        if (dom == null) System.out.print("[" + hash + "], "); else System.out.print(dom + ", ");
                    }
                    System.out.println();
                }
                
            }
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }
    
}
