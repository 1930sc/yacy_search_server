// yacyCore.java
// -------------------------------------
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2004
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

/*
  the yacy process of getting in touch of other peers starts as follows:
  - init seed cache. It is needed to determine the right peer for the Hello-Process
  - create a own seed. This can be a new one or one loaded from a file
  - The httpd must start up then first
  - the own seed is completed by performing the 'yacyHello' process. This
    process will result in a request back to the own peer to check if it runs
    in server mode. This is the reason that the httpd must be started in advance.

*/

// contributions:
// principal peer status via file generation by Alexander Schier [AS]

package de.anomic.yacy;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import de.anomic.content.RSSMessage;
import de.anomic.document.parser.xml.RSSFeed;
import de.anomic.kelondro.util.DateFormatter;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.server.serverCore;
import de.anomic.server.serverSemaphore;
import de.anomic.yacy.dht.PeerSelection;
import de.anomic.yacy.logging.Log;

public class yacyCore {

    // statics
    public static final ThreadGroup publishThreadGroup = new ThreadGroup("publishThreadGroup");
    public static final HashMap<String, String> seedUploadMethods = new HashMap<String, String>();
    public static final Log log = new Log("YACY");
    public static long lastOnlineTime = 0;
    /** pseudo-random key derived from a time-interval while YaCy startup*/
    public static long speedKey = 0;
    public static final Map<String, yacyAccessible> amIAccessibleDB = Collections.synchronizedMap(new HashMap<String, yacyAccessible>()); // Holds PeerHash / yacyAccessible Relations
    // constants for PeerPing behaviour
    private static final int PING_INITIAL = 10;
    private static final int PING_MAX_RUNNING = 3;
    private static final int PING_MIN_RUNNING = 1;
    private static final int PING_MIN_DBSIZE = 5;
    private static final int PING_MIN_PEERSEEN = 1; // min. accessible to force senior
    private static final long PING_MAX_DBAGE = 15 * 60 * 1000; // in milliseconds
    
    // public static yacyShare shareManager = null;
    // public static boolean terminate = false;

    // class variables
    plasmaSwitchboard sb;

    public static int yacyTime() {
        // the time since startup of yacy in seconds
        return Math.max(0, (int) ((System.currentTimeMillis() - serverCore.startupTime) / 1000));
    }

    public yacyCore(final plasmaSwitchboard sb) {
        final long time = System.currentTimeMillis();

        this.sb = sb;
        sb.setConfig("yacyStatus", "");
        
        // create a peer news channel
        final RSSFeed peernews = RSSFeed.channels(RSSFeed.PEERNEWS);
        peernews.addMessage(new RSSMessage("YaCy started", "", ""));
        
        // ensure that correct IP is used
        final String staticIP = sb.getConfig("staticIP", "");
        if (staticIP.length() != 0 && yacySeed.isProperIP(staticIP) == null) {
            serverCore.useStaticIP = true;
            sb.peers.mySeed().setIP(staticIP);
            log.logInfo("staticIP set to "+ staticIP);
        } else {
            serverCore.useStaticIP = false;
        }

        loadSeedUploadMethods();

        log.logConfig("CORE INITIALIZED");
        // ATTENTION, VERY IMPORTANT: before starting the thread, the httpd yacy server must be running!

        speedKey = System.currentTimeMillis() - time;
        lastOnlineTime = 0;
    }

    synchronized static public void triggerOnlineAction() {
        lastOnlineTime = System.currentTimeMillis();
    }
    
    public final void publishSeedList() {
        if (log.isFine()) log.logFine("yacyCore.publishSeedList: Triggered Seed Publish");

        /*
        if (oldIPStamp.equals((String) seedDB.mySeed.get(yacySeed.IP, "127.0.0.1")))
            yacyCore.log.logDebug("***DEBUG publishSeedList: oldIP is equal");
        if (seedCacheSizeStamp == seedDB.sizeConnected())
            yacyCore.log.logDebug("***DEBUG publishSeedList: sizeConnected is equal");
        if (canReachMyself())
            yacyCore.log.logDebug("***DEBUG publishSeedList: I can reach myself");
        */

        if ((sb.peers.lastSeedUpload_myIP.equals(sb.peers.mySeed().getIP())) &&
            (sb.peers.lastSeedUpload_seedDBSize == sb.peers.sizeConnected()) &&
            (canReachMyself()) &&
            (System.currentTimeMillis() - sb.peers.lastSeedUpload_timeStamp < 1000 * 60 * 60 * 24) &&
            (sb.peers.mySeed().isPrincipal())
        ) {
            if (log.isFine()) log.logFine("yacyCore.publishSeedList: not necessary to publish: oldIP is equal, sizeConnected is equal and I can reach myself under the old IP.");
            return;
        }

        // getting the seed upload method that should be used ...
        final String seedUploadMethod = this.sb.getConfig("seedUploadMethod", "");

        if (
                (!seedUploadMethod.equalsIgnoreCase("none")) ||
                ((seedUploadMethod.equals("")) && (this.sb.getConfig("seedFTPPassword", "").length() > 0)) ||
                ((seedUploadMethod.equals("")) && (this.sb.getConfig("seedFilePath", "").length() > 0))
        ) {
            if (seedUploadMethod.equals("")) {
                if (this.sb.getConfig("seedFTPPassword", "").length() > 0) {
                    this.sb.setConfig("seedUploadMethod", "Ftp");
                }
                if (this.sb.getConfig("seedFilePath", "").length() > 0) {
                    this.sb.setConfig("seedUploadMethod", "File");
                }
            }
            // we want to be a principal...
            saveSeedList(sb);
        } else {
            if (seedUploadMethod.equals("")) {
                this.sb.setConfig("seedUploadMethod", "none");
            }
            if (log.isFine()) log.logFine("yacyCore.publishSeedList: No uploading method configured");
            return;
        }
    }

    public final void peerPing() {
        if ((sb.isRobinsonMode()) && (sb.getConfig("cluster.mode", "").equals("privatepeer"))) {
            // in case this peer is a privat peer we omit the peer ping
            // all other robinson peer types do a peer ping:
            // the privatecluster does the ping to the other cluster members
            // the publiccluster does the ping to all peers, but prefers the own peer
            // the publicpeer does the ping to all peers
            return;
        }

        // before publishing, update some seed data
        sb.updateMySeed();

        // publish own seed to other peer, this can every peer, but makes only sense for senior peers
        if (sb.peers.sizeConnected() == 0) {
            // reload the seed lists
            sb.loadSeedLists();
            log.logInfo("re-initialized seed list. received " + sb.peers.sizeConnected() + " new peer(s)");
        }
        final int newSeeds = publishMySeed(false);
        if (newSeeds > 0) {
            log.logInfo("received " + newSeeds + " new peer(s), know a total of " + sb.peers.sizeConnected() + " different peers");
        }
    }

    private boolean canReachMyself() { // TODO: check if this method is necessary - depending on the used router it will not work
        // returns true if we can reach ourself under our known peer address
        // if we cannot reach ourself, we call a forced publishMySeed and return false
    	final int urlc = yacyClient.queryUrlCount(sb.peers.mySeed());
        if (urlc >= 0) {
            sb.peers.mySeed().setLastSeenUTC();
            return true;
        }
        log.logInfo("re-connect own seed");
        final String oldAddress = sb.peers.mySeed().getPublicAddress();
        /*final int newSeeds =*/ publishMySeed(true);
        return (oldAddress != null && oldAddress.equals(sb.peers.mySeed().getPublicAddress()));
    }

    protected class publishThread extends Thread {
        int added;
        private final yacySeed seed;
        private final serverSemaphore sync;
        private final List<Thread> syncList;

        public publishThread(final ThreadGroup tg, final yacySeed seed,
                             final serverSemaphore sync, final List<Thread> syncList) throws InterruptedException {
            super(tg, "PublishSeed_" + seed.getName());

            this.sync = sync;
            this.sync.P();
            this.syncList = syncList;

            this.seed = seed;
            this.added = 0;
        }

        public final void run() {
            try {
                this.added = yacyClient.publishMySeed(sb.peers.mySeed(), sb.peers.peerActions, seed.getClusterAddress(), seed.hash);
                if (this.added < 0) {
                    // no or wrong response, delete that address
                    final String cause = "peer ping to peer resulted in error response (added < 0)";
                    log.logInfo("publish: disconnected " + this.seed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR) + " peer '" + this.seed.getName() + "' from " + this.seed.getPublicAddress() + ": " + cause);
                    sb.peers.peerActions.peerDeparture(this.seed, cause);
                } else {
                    // success! we have published our peer to a senior peer
                    // update latest news from the other peer
                    log.logInfo("publish: handshaked " + this.seed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR) + " peer '" + this.seed.getName() + "' at " + this.seed.getPublicAddress());
                    // check if seed's lastSeen has been updated
                    final yacySeed newSeed = sb.peers.getConnected(this.seed.hash);
                    if (newSeed != null) {
                        if (!newSeed.isOnline()) {
                            if (log.isFine()) log.logFine("publish: recently handshaked " + this.seed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR) +
                                " peer '" + this.seed.getName() + "' at " + this.seed.getPublicAddress() + " is not online." +
                                " Removing Peer from connected");
                            sb.peers.peerActions.peerDeparture(newSeed, "peer not online");
                        } else
                        if (newSeed.getLastSeenUTC() < (System.currentTimeMillis() - 10000)) {
                            // update last seed date
                            if (newSeed.getLastSeenUTC() >= this.seed.getLastSeenUTC()) {
                                if (log.isFine()) log.logFine("publish: recently handshaked " + this.seed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR) +
                                    " peer '" + this.seed.getName() + "' at " + this.seed.getPublicAddress() + " with old LastSeen: '" +
                                    DateFormatter.formatShortSecond(new Date(newSeed.getLastSeenUTC())) + "'");
                                newSeed.setLastSeenUTC();
                                sb.peers.peerActions.peerArrival(newSeed, true);
                            } else {
                                if (log.isFine()) log.logFine("publish: recently handshaked " + this.seed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR) +
                                    " peer '" + this.seed.getName() + "' at " + this.seed.getPublicAddress() + " with old LastSeen: '" +
                                    DateFormatter.formatShortSecond(new Date(newSeed.getLastSeenUTC())) + "', this is more recent: '" +
                                    DateFormatter.formatShortSecond(new Date(this.seed.getLastSeenUTC())) + "'");
                                this.seed.setLastSeenUTC();
                                sb.peers.peerActions.peerArrival(this.seed, true);
                            }
                        }
                    } else {
                        if (log.isFine()) log.logFine("publish: recently handshaked " + this.seed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR) + " peer '" + this.seed.getName() + "' at " + this.seed.getPublicAddress() + " not in connectedDB");
                    }
                }
            } catch (final Exception e) {
                log.logSevere("publishThread: error with target seed " + seed.toString() + ": " + e.getMessage(), e);
            } finally {
                this.syncList.add(this);
                this.sync.V();
            }
        }
    }

    private int publishMySeed(final boolean force) {
        try {
            // call this after the httpd was started up

            // we need to find out our own ip
            // This is not always easy, since the application may
            // live behind a firewall or nat.
            // the normal way to do this is either measure the value that java gives us,
            // but this is not correct if the peer lives behind a NAT/Router or has several
            // addresses and not the right one can be found out.
            // We have several alternatives:
            // 1. ask another peer. This should be normal and the default method.
            //    but if no other peer lives, or we don't know them, we cannot do that
            // 2. ask own NAT. This is only an option if the NAT is a DI604, because this is the
            //    only supported for address retrieval
            // 3. ask ip respond services in the internet. There are several, and they are all
            //    probed until we get a valid response.

            // init yacyHello-process
            Map<String, yacySeed> seeds; // hash/yacySeed relation

            int attempts = sb.peers.sizeConnected();

            // getting a list of peers to contact
            if (sb.peers.mySeed().get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_VIRGIN).equals(yacySeed.PEERTYPE_VIRGIN)) {
                if (attempts > PING_INITIAL) { attempts = PING_INITIAL; }
                final Map<byte[], String> ch = plasmaSwitchboard.getSwitchboard().clusterhashes;
                seeds = PeerSelection.seedsByAge(sb.peers, true, attempts - ((ch == null) ? 0 : ch.size())); // best for fast connection
                // add also all peers from cluster if this is a public robinson cluster
                if (ch != null) {
                    final Iterator<Map.Entry<byte[], String>> i = ch.entrySet().iterator();
                    String hash;
                    Map.Entry<byte[], String> entry;
                    yacySeed seed;
                    while (i.hasNext()) {
                        entry = i.next();
                        hash = new String(entry.getKey());
                        seed = seeds.get(hash);
                        if (seed == null) {
                            seed = sb.peers.get(hash);
                            if (seed == null) continue;
                        }
                        seed.setAlternativeAddress(entry.getValue());
                        seeds.put(hash, seed);
                	}
                }
            } else {
                int diff = PING_MIN_DBSIZE - amIAccessibleDB.size();
                if (diff > PING_MIN_RUNNING) {
                    diff = Math.min(diff, PING_MAX_RUNNING);
                    if (attempts > diff) { attempts = diff; }
                } else {
                    if (attempts > PING_MIN_RUNNING) { attempts = PING_MIN_RUNNING; }
                }
                seeds = PeerSelection.seedsByAge(sb.peers, false, attempts); // best for seed list maintenance/cleaning
            }

            if ((seeds == null) || seeds.size() == 0) { return 0; }
            if (seeds.size() < attempts) { attempts = seeds.size(); }

            // This will try to get Peers that are not currently in amIAccessibleDB
            final Iterator<yacySeed> si = seeds.values().iterator();
            yacySeed seed;

            // include a YaCyNews record to my seed
            try {
                final yacyNewsRecord record = sb.peers.newsPool.myPublication();
                if (record == null) {
                    sb.peers.mySeed().put("news", "");
                } else {
                    sb.peers.mySeed().put("news", de.anomic.tools.crypt.simpleEncode(record.toString()));
                }
            } catch (final IOException e) {
                log.logSevere("publishMySeed: problem with news encoding", e);
            }
            sb.peers.mySeed().setUnusedFlags();

            // include current citation-rank file count
            sb.peers.mySeed().put(yacySeed.CRWCNT, Integer.toString(sb.rankingOwnDistribution.size()));
            sb.peers.mySeed().put(yacySeed.CRTCNT, Integer.toString(sb.rankingOtherDistribution.size()));
            int newSeeds = -1;
            //if (seeds.length > 1) {
            // holding a reference to all started threads
            int contactedSeedCount = 0;
            final List<Thread> syncList = Collections.synchronizedList(new LinkedList<Thread>()); // memory for threads
            final serverSemaphore sync = new serverSemaphore(attempts);

            // going through the peer list and starting a new publisher thread for each peer
            int i = 0;
            while (si.hasNext()) {
                seed = si.next();
                if (seed == null) {
                    sync.P();
                    continue;
                }
                i++;

                final String address = seed.getClusterAddress();
                if (log.isFine()) log.logFine("HELLO #" + i + " to peer '" + seed.get(yacySeed.NAME, "") + "' at " + address); // debug
                final String seederror = seed.isProper(false);
                if ((address == null) || (seederror != null)) {
                    // we don't like that address, delete it
                    sb.peers.peerActions.peerDeparture(seed, "peer ping to peer resulted in address = " + address + "; seederror = " + seederror);
                    sync.P();
                } else {
                    // starting a new publisher thread
                    contactedSeedCount++;
                    (new publishThread(yacyCore.publishThreadGroup, seed, sync, syncList)).start();
                }
            }

            // receiving the result of all started publisher threads
            for (int j = 0; j < contactedSeedCount; j++) {

                // waiting for the next thread to finish
                sync.P();

                // if this is true something is wrong ...
                if (syncList.isEmpty()) {
                    log.logWarning("PeerPing: syncList.isEmpty()==true");
                    continue;
                    //return 0;
                }

                // getting a reference to the finished thread
                final publishThread t = (publishThread) syncList.remove(0);

                // getting the amount of new reported seeds
                if (t.added >= 0) {
                    if (newSeeds == -1) {
                        newSeeds =  t.added;
                    } else {
                        newSeeds += t.added;
                    }
                }
            }

            int accessible = 0;
            int notaccessible = 0;
            final long cutofftime = System.currentTimeMillis() - PING_MAX_DBAGE;
            final int dbSize;
            synchronized (amIAccessibleDB) {
                dbSize = amIAccessibleDB.size();
                final Iterator<String> ai = amIAccessibleDB.keySet().iterator();
                while (ai.hasNext()) {
                    final yacyAccessible ya = amIAccessibleDB.get(ai.next());
                    if (ya.lastUpdated < cutofftime) {
                        ai.remove();
                    } else {
                        if (ya.IWasAccessed) {
                            accessible++;
                        } else {
                            notaccessible++;
                        }
                    }
                }
                if (log.isFine()) log.logFine("DBSize before -> after Cleanup: " + dbSize + " -> " + amIAccessibleDB.size());
            }
            log.logInfo("PeerPing: I am accessible for " + accessible +
                " peer(s), not accessible for " + notaccessible + " peer(s).");

            if ((accessible + notaccessible) > 0) {
                final String newPeerType;
                // At least one other Peer told us our type
                if ((accessible >= PING_MIN_PEERSEEN) ||
                    (accessible >= notaccessible)) {
                    // We can be reached from a majority of other Peers
                    if (sb.peers.mySeed().isPrincipal()) {
                        newPeerType = yacySeed.PEERTYPE_PRINCIPAL;
                    } else {
                        newPeerType = yacySeed.PEERTYPE_SENIOR;
                    }
                } else {
                    // We cannot be reached from the outside
                    newPeerType = yacySeed.PEERTYPE_JUNIOR;
                }
                if (sb.peers.mySeed().orVirgin().equals(newPeerType)) {
                    log.logInfo("PeerPing: myType is " + sb.peers.mySeed().orVirgin());
                } else {
                    log.logInfo("PeerPing: changing myType from '" + sb.peers.mySeed().orVirgin() + "' to '" + newPeerType + "'");
                    sb.peers.mySeed().put(yacySeed.PEERTYPE, newPeerType);
                }
            } else {
                log.logInfo("PeerPing: No data, staying at myType: " + sb.peers.mySeed().orVirgin());
            }

            // success! we have published our peer to a senior peer
            // update latest news from the other peer
            // log.logInfo("publish: handshaked " + t.seed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR) + " peer '" + t.seed.getName() + "' at " + t.seed.getAddress());
            sb.peers.saveMySeed();

            // if we have an address, we do nothing
            if (sb.peers.mySeed().isProper(true) == null && !force) { return 0; }
            if (newSeeds > 0) return newSeeds;
            
            // still no success: ask own NAT or internet responder
            //final boolean DI604use = switchboard.getConfig("DI604use", "false").equals("true");
            //final String  DI604pw  = switchboard.getConfig("DI604pw", "");
            final String  ip = sb.getConfig("staticIP", "");
            //if (ip.equals("")) ip = natLib.retrieveIP(DI604use, DI604pw);
            
            // yacyCore.log.logDebug("DEBUG: new IP=" + ip);
            if (yacySeed.isProperIP(ip) == null) sb.peers.mySeed().setIP(ip);
            if (sb.peers.mySeed().get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_JUNIOR).equals(yacySeed.PEERTYPE_JUNIOR)) // ???????????????
                sb.peers.mySeed().put(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR); // to start bootstraping, we need to be recognised as PEERTYPE_SENIOR peer
            log.logInfo("publish: no recipient found, our address is " +
                    ((sb.peers.mySeed().getPublicAddress() == null) ? "unknown" : sb.peers.mySeed().getPublicAddress()));
            sb.peers.saveMySeed();
            return 0;
        } catch (final InterruptedException e) {
            try {
                log.logInfo("publish: Interruption detected while publishing my seed.");

                // consuming the theads interrupted signal
                Thread.interrupted();

                // interrupt all already started publishThreads
                log.logInfo("publish: Signaling shutdown to " + yacyCore.publishThreadGroup.activeCount() +  " remaining publishing threads ...");
                yacyCore.publishThreadGroup.interrupt();

                // waiting some time for the publishThreads to finish execution
                try { Thread.sleep(500); } catch (final InterruptedException ex) {}

                // getting the amount of remaining publishing threads
                int threadCount  = yacyCore.publishThreadGroup.activeCount();
                final Thread[] threadList = new Thread[threadCount];
                threadCount = yacyCore.publishThreadGroup.enumerate(threadList);

                // we need to use a timeout here because of missing interruptable session threads ...
                if (log.isFine()) log.logFine("publish: Waiting for " + yacyCore.publishThreadGroup.activeCount() +  " remaining publishing threads to finish shutdown ...");
                for (int currentThreadIdx = 0; currentThreadIdx < threadCount; currentThreadIdx++) {
                    final Thread currentThread = threadList[currentThreadIdx];

                    if (currentThread.isAlive()) {
                        if (log.isFine()) log.logFine("publish: Waiting for remaining publishing thread '" + currentThread.getName() + "' to finish shutdown");
                        try { currentThread.join(500); } catch (final InterruptedException ex) {}
                    }
                }

                log.logInfo("publish: Shutdown off all remaining publishing thread finished.");

            } catch (final Exception ee) {
                log.logWarning("publish: Unexpected error while trying to shutdown all remaining publishing threads.", e);
            }

            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    public static HashMap<String, String> getSeedUploadMethods() {
        synchronized (yacyCore.seedUploadMethods) {
            return (HashMap<String, String>) yacyCore.seedUploadMethods.clone();
        }
    }

    public static yacySeedUploader getSeedUploader(final String methodname) {
        String className = null;
        synchronized (yacyCore.seedUploadMethods) {
            if (yacyCore.seedUploadMethods.containsKey(methodname)) {
                className = yacyCore.seedUploadMethods.get(methodname);
            }
        }

        if (className == null) { return null; }
        try {
            final Class<?> uploaderClass = Class.forName(className);
            final Object uploader = uploaderClass.newInstance();
            return (yacySeedUploader) uploader;
        } catch (final Exception e) {
            return null;
        }
    }

    public static void loadSeedUploadMethods() {
        final HashMap<String, String> availableUploaders = new HashMap<String, String>();
        try {
            final String uploadersPkgName = yacyCore.class.getPackage().getName() + ".seedUpload";
            final String packageURI = yacyCore.class.getResource("/" + uploadersPkgName.replace('.', '/')).toString();

            // open the parser directory
            final File uploadersDir = new File(new URI(packageURI));
            if ((uploadersDir == null) || (!uploadersDir.exists()) || (!uploadersDir.isDirectory())) {
                yacyCore.seedUploadMethods.clear();
                changeSeedUploadMethod("none");
            }

            final String[] uploaderClasses = uploadersDir.list(new FilenameFilter() {
                public boolean accept(final File dir, final String name) {
                    return name.startsWith("yacySeedUpload") && name.endsWith(".class");
                }
            });

            final String javaClassPath = System.getProperty("java.class.path");

            if (uploaderClasses == null) { return; }
            for (int uploaderNr = 0; uploaderNr < uploaderClasses.length; uploaderNr++) {
                final String className = uploaderClasses[uploaderNr].substring(0, uploaderClasses[uploaderNr].indexOf(".class"));
                final String fullClassName = uploadersPkgName + "." + className;
                try {
                    final Class<?> uploaderClass = Class.forName(fullClassName);
                    final Object theUploader = uploaderClass.newInstance();
                    if (!(theUploader instanceof yacySeedUploader)) { continue; }
                    final String[] neededLibx = ((yacySeedUploader)theUploader).getLibxDependencies();
                    if (neededLibx != null) {
                        for (int libxId=0; libxId < neededLibx.length; libxId++) {
                            if (javaClassPath.indexOf(neededLibx[libxId]) == -1) {
                                throw new Exception("Missing dependency");
                            }
                        }
                    }
                    availableUploaders.put(className.substring("yacySeedUpload".length()), fullClassName);
                } catch (final Exception e) { /* we can ignore this for the moment */
                } catch (final Error e)     { /* we can ignore this for the moment */
                }
            }
        } catch (final Exception e) {
        } finally {
            synchronized (yacyCore.seedUploadMethods) {
                yacyCore.seedUploadMethods.clear();
                yacyCore.seedUploadMethods.putAll(availableUploaders);
            }
        }
    }

    public static boolean changeSeedUploadMethod(final String method) {
        if (method == null || method.length() == 0) { return false; }

        if (method.equalsIgnoreCase("none")) { return true; }

        synchronized (yacyCore.seedUploadMethods) {
            return yacyCore.seedUploadMethods.containsKey(method);
        }
    }

    public static final String saveSeedList(final plasmaSwitchboard sb) {
        try {
            // return an error if this is not successful, and NULL if everything is fine
            String logt;

            // be shure that we have something to say
            if (sb.peers.mySeed().getPublicAddress() == null) {
                final String errorMsg = "We have no valid IP address until now";
                log.logWarning("SaveSeedList: " + errorMsg);
                return errorMsg;
            }

            // getting the configured seed uploader
            String seedUploadMethod = sb.getConfig("seedUploadMethod", "");

            // for backward compatiblity ....
            if (seedUploadMethod.equalsIgnoreCase("Ftp") ||
                (seedUploadMethod.equals("") &&
                 sb.getConfig("seedFTPPassword", "").length() > 0)) {

                seedUploadMethod = "Ftp";
                sb.setConfig("seedUploadMethod", seedUploadMethod);

            } else if (seedUploadMethod.equalsIgnoreCase("File") ||
                       (seedUploadMethod.equals("") &&
                        sb.getConfig("seedFilePath", "").length() > 0)) {

                seedUploadMethod = "File";
                sb.setConfig("seedUploadMethod", seedUploadMethod);
            }

            //  determine the seed uploader that should be used ...
            if (seedUploadMethod.equalsIgnoreCase("none")) { return "no uploader specified"; }

            final yacySeedUploader uploader = getSeedUploader(seedUploadMethod);
            if (uploader == null) {
                final String errorMsg = "Unable to get the proper uploader-class for seed uploading method '" + seedUploadMethod + "'.";
                log.logWarning("SaveSeedList: " + errorMsg);
                return errorMsg;
            }

            // ensure that the seed file url is configured properly
            yacyURL seedURL;
            try {
                final String seedURLStr = sb.peers.mySeed().get(yacySeed.SEEDLIST, "");
                if (seedURLStr.length() == 0) { throw new MalformedURLException("The seed-file url must not be empty."); }
                if (!(
                        seedURLStr.toLowerCase().startsWith("http://") ||
                        seedURLStr.toLowerCase().startsWith("https://")
                )) {
                    throw new MalformedURLException("Unsupported protocol.");
                }
                seedURL = new yacyURL(seedURLStr, null);
            } catch (final MalformedURLException e) {
                final String errorMsg = "Malformed seed file URL '" + sb.peers.mySeed().get(yacySeed.SEEDLIST, "") + "'. " + e.getMessage();
                log.logWarning("SaveSeedList: " + errorMsg);
                return errorMsg;
            }

            // upload the seed-list using the configured uploader class
            String prevStatus = sb.peers.mySeed().get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_JUNIOR);
            if (prevStatus.equals(yacySeed.PEERTYPE_PRINCIPAL)) { prevStatus = yacySeed.PEERTYPE_SENIOR; }

            try {
                sb.peers.mySeed().put(yacySeed.PEERTYPE, yacySeed.PEERTYPE_PRINCIPAL); // this information shall also be uploaded

                if (log.isFine()) log.logFine("SaveSeedList: Using seed uploading method '" + seedUploadMethod + "' for seed-list uploading." +
                            "\n\tPrevious peerType is '" + sb.peers.mySeed().get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_JUNIOR) + "'.");

//              logt = seedDB.uploadCache(seedFTPServer, seedFTPAccount, seedFTPPassword, seedFTPPath, seedURL);
                logt = sb.peers.uploadCache(uploader, sb, sb.peers, seedURL);
                if (logt != null) {
                    if (logt.indexOf("Error") >= 0) {
                        sb.peers.mySeed().put(yacySeed.PEERTYPE, prevStatus);
                        final String errorMsg = "SaveSeedList: seed upload failed using " + uploader.getClass().getName() + " (error): " + logt.substring(logt.indexOf("Error") + 6);
                        log.logSevere(errorMsg);
                        return errorMsg;
                    }
                    log.logInfo(logt);
                }

                // finally, set the principal status
                sb.setConfig("yacyStatus", yacySeed.PEERTYPE_PRINCIPAL);
                return null;
            } catch (final Exception e) {
                sb.peers.mySeed().put(yacySeed.PEERTYPE, prevStatus);
                sb.setConfig("yacyStatus", prevStatus);
                final String errorMsg = "SaveSeedList: Seed upload failed (IO error): " + e.getMessage();
                log.logInfo(errorMsg, e);
                return errorMsg;
            }
        } finally {
            sb.peers.lastSeedUpload_seedDBSize = sb.peers.sizeConnected();
            sb.peers.lastSeedUpload_timeStamp = System.currentTimeMillis();
            sb.peers.lastSeedUpload_myIP = sb.peers.mySeed().getIP();
        }
    }

}
