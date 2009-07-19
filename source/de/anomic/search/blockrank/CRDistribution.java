// plasmaRankingDistribution.java 
// -------------------------------------------
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2005
// created 9.11.2005
//
// $LastChangedDate: 2005-11-04 14:41:51 +0100 (Fri, 04 Nov 2005) $
// $LastChangedRevision: 1026 $
// $LastChangedBy: borg-0300 $
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

package de.anomic.search.blockrank;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.StringTokenizer;

import de.anomic.kelondro.util.FileUtils;
import de.anomic.yacy.yacyClient;
import de.anomic.yacy.yacySeed;
import de.anomic.yacy.yacySeedDB;
import de.anomic.yacy.yacyVersion;
import de.anomic.yacy.logging.Log;

public final class CRDistribution {

    public static final String CR_OWN   = "GLOBAL/010_owncr";
    public static final String CR_OTHER = "GLOBAL/014_othercr/";

    public static final int METHOD_NONE           =  0;
    public static final int METHOD_ANYSENIOR      =  1;
    public static final int METHOD_ANYPRINCIPAL   =  2;
    public static final int METHOD_MIXEDSENIOR    =  9;
    public static final int METHOD_MIXEDPRINCIPAL = 10;
    public static final int METHOD_FIXEDADDRESS   = 99;
    
    private final Log log;
    private final File sourcePath;     // where to load CR-files
    private int method;          // of peer selection
    private int percentage;      // to select any other peer
    private String address[];      // of fixed other peer
    private final yacySeedDB seedDB;
    private static Random random = new Random(System.currentTimeMillis());
    
    public CRDistribution(final Log log, final yacySeedDB seedDB, final File sourcePath, final int method, final int percentage, final String addresses) {
        this.log        = log;
        this.seedDB     = seedDB;
        this.sourcePath = sourcePath;
        this.method     = method;
        this.percentage = percentage;
        StringTokenizer st = new StringTokenizer(addresses, ",");
        int c = 0; while (st.hasMoreTokens()) {st.nextToken(); c++;}
        st = new StringTokenizer(addresses, ",");
        this.address = new String[c];
        c = 0;
        while (st.hasMoreTokens()) {this.address[c++] = st.nextToken();}
    }

    public void setMethod(final int method, final int percentage, final String address[]) {
        this.method     = method;
        this.percentage = percentage;
        this.address    = address;
    }
    
    public int size() {
        if ((sourcePath.exists()) && (sourcePath.isDirectory()))
            return sourcePath.list().length;
        return 0;
    }

    public boolean transferRanking(int count) throws InterruptedException {

        if (method == METHOD_NONE) {
            log.logFine("no ranking distribution: no transfer method given");
            return false;
        }
        if (seedDB == null) {
            log.logFine("no ranking distribution: seedDB == null");
            return false;
        }
        if (seedDB.mySeed() == null) {
            log.logFine("no ranking distribution: mySeed == null");
            return false;
        }
        if (seedDB.mySeed().isVirgin()) {
            log.logFine("no ranking distribution: status is virgin");
            return false;
        }
        
        final String[] outfiles = sourcePath.list();
        
        if (outfiles == null) {
            log.logFine("no ranking distribution: source path does not exist");
            return false;
        }
        if (outfiles.length == 0) {
            log.logFine("no ranking distribution: source path does not contain any file");
            return false;
        }
        
        if (outfiles.length < count) count = outfiles.length;
        File crfile = null;
        
        for (int i = 0; i < count; i++) {
            // check for interruption
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Shutdown in progress");
            
            // getting the next file to transfer
            crfile = new File(sourcePath, outfiles[i]);
            
            if ((method == METHOD_ANYSENIOR) || (method == METHOD_ANYPRINCIPAL)) {
                transferRankingAnySeed(crfile, 5);
            }
            if (method == METHOD_FIXEDADDRESS) {
                transferRankingAddress(crfile);
            }
            if ((method == METHOD_MIXEDSENIOR) || (method == METHOD_MIXEDPRINCIPAL)) {
                if (random.nextInt(100) > percentage) {
                    if (!(transferRankingAddress(crfile))) transferRankingAnySeed(crfile, 5);
                } else {
                    if (!(transferRankingAnySeed(crfile, 5))) transferRankingAddress(crfile);
                }
            }
            
        }
        log.logFine("no ranking distribution: no target available");
        return false;
    }
    
    private boolean transferRankingAnySeed(final File crfile, final int trycount) throws InterruptedException {
        yacySeed target = null;
        for (int j = 0; j < trycount; j++) {
            // check for interruption
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Shutdown in progress");
            
            // get next target
            target = seedDB.anySeedVersion(yacyVersion.YACY_ACCEPTS_RANKING_TRANSMISSION);
            
            if (target == null) continue;
            final String targetaddress = target.getPublicAddress();
            if (transferRankingAddress(crfile, targetaddress)) return true;
        }
        return false;
    }
    
    private boolean transferRankingAddress(final File crfile) throws InterruptedException {
        // try all addresses
        for (int i = 0; i < this.address.length; i++) {
            // check for interruption
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Shutdown in progress");
            
            // try to transfer ranking address using the next address
            if (transferRankingAddress(crfile, this.address[i])) return true;
        }
        return false;
    }
    
    private boolean transferRankingAddress(final File crfile, final String address) {
        // do the transfer
        final long starttime = System.currentTimeMillis();
        String result = "unknown";
        try {
            final byte[] b = FileUtils.read(crfile);
            result = yacyClient.transfer(address, crfile.getName(), b);
            if (result == null) {
                log.logInfo("RankingDistribution - transmitted file " + crfile + " to " + address + " successfully in " + ((System.currentTimeMillis() - starttime) / 1000) + " seconds");
                FileUtils.deletedelete(crfile); // the file is not needed any more locally
            } else {
                log.logInfo("RankingDistribution - error transmitting file " + crfile + " to " + address + ": " + result);
            }
        } catch (final IOException e) {
            log.logInfo("RankingDistribution - could not read file " + crfile + ": " + e.getMessage());
            result = "input file error: " + e.getMessage();
        }
        
        // show success
        return result == null;
    }

}