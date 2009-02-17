// CrawlQueues.java
// (C) 2007 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 29.10.2007 on http://yacy.net
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

package de.anomic.crawler;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.anomic.index.indexDocumentMetadata;
import de.anomic.kelondro.order.DateFormatter;
import de.anomic.kelondro.table.FlexWidthArray;
import de.anomic.kelondro.util.Log;
import de.anomic.plasma.plasmaParser;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.plasma.plasmaSwitchboardConstants;
import de.anomic.server.serverProcessorJob;
import de.anomic.xml.RSSFeed;
import de.anomic.xml.RSSMessage;
import de.anomic.yacy.yacyClient;
import de.anomic.yacy.yacySeed;
import de.anomic.yacy.yacyURL;
import de.anomic.yacy.dht.PeerSelection;

public class CrawlQueues {

    plasmaSwitchboard sb;
    Log log;
    Map<Integer, crawlWorker> workers; // mapping from url hash to Worker thread object
    ProtocolLoader loader;
    private final ArrayList<String> remoteCrawlProviderHashes;

    public  NoticedURL noticeURL;
    public  ZURL errorURL, delegatedURL;
    
    public CrawlQueues(final plasmaSwitchboard sb, final File plasmaPath) {
        this.sb = sb;
        this.log = new Log("CRAWLER");
        this.workers = new ConcurrentHashMap<Integer, crawlWorker>();
        this.loader = new ProtocolLoader(sb, log);
        this.remoteCrawlProviderHashes = new ArrayList<String>();
        
        // start crawling management
        log.logConfig("Starting Crawling Management");
        noticeURL = new NoticedURL(plasmaPath);
        //errorURL = new plasmaCrawlZURL(); // fresh error DB each startup; can be hold in RAM and reduces IO;
        final File errorDBFile = new File(plasmaPath, "urlError2.db");
        if (errorDBFile.exists()) {
            // delete the error db to get a fresh each time on startup
            // this is useful because there is currently no re-use of the data in this table.
            if (errorDBFile.isDirectory()) FlexWidthArray.delete(plasmaPath, "urlError2.db"); else errorDBFile.delete();
        }
        errorURL = new ZURL(plasmaPath, "urlError3.db", false);
        delegatedURL = new ZURL(plasmaPath, "urlDelegated3.db", true);
    }
    
    public String urlExists(final String hash) {
        // tests if hash occurrs in any database
        // if it exists, the name of the database is returned,
        // if it not exists, null is returned
        if (noticeURL.existsInStack(hash)) return "crawler";
        if (delegatedURL.exists(hash)) return "delegated";
        if (errorURL.exists(hash)) return "errors";
        for (final crawlWorker worker: workers.values()) {
            if (worker.entry.url().hash().equals(hash)) return "worker";
        }
        return null;
    }
    
    public void urlRemove(final String hash) {
        noticeURL.removeByURLHash(hash);
        delegatedURL.remove(hash);
        errorURL.remove(hash);
    }
    
    public yacyURL getURL(final String urlhash) {
        assert urlhash != null;
        if (urlhash == null || urlhash.length() == 0) return null;
        final CrawlEntry ne = noticeURL.get(urlhash);
        if (ne != null) return ne.url();
        ZURL.Entry ee = delegatedURL.getEntry(urlhash);
        if (ee != null) return ee.url();
        ee = errorURL.getEntry(urlhash);
        if (ee != null) return ee.url();
        for (final crawlWorker w: workers.values()) {
            if (w.entry.url().hash().equals(urlhash)) return w.entry.url();
        }
        return null;
    }
    
    public void clear() {
        // wait for all workers to finish
        for (final crawlWorker w: workers.values()) {
            w.interrupt();
        }
        // TODO: wait some more time until all threads are finished
        workers.clear();
        remoteCrawlProviderHashes.clear();
        noticeURL.clear();
        try {
            errorURL.clear();
        } catch (final IOException e) {
            e.printStackTrace();
        }
        try {
            delegatedURL.clear();
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }
    
    public void close() {
        // wait for all workers to finish
        for (final crawlWorker w: workers.values()) {
            w.interrupt();
        }
        // TODO: wait some more time until all threads are finished
        noticeURL.close();
        errorURL.close();
        delegatedURL.close();
    }
    
    public CrawlEntry[] activeWorkerEntries() {
        synchronized (workers) {
            final CrawlEntry[] e = new CrawlEntry[workers.size()];
            int i = 0;
            for (final crawlWorker w: workers.values()) e[i++] = w.entry;
            return e;
        }
    }
    
    public boolean isSupportedProtocol(final String protocol) {
        return loader.isSupportedProtocol(protocol);
    }
    
    public int coreCrawlJobSize() {
        return noticeURL.stackSize(NoticedURL.STACK_TYPE_CORE);
    }
    
    public boolean coreCrawlJob() {
        
        final boolean robinsonPrivateCase = ((sb.isRobinsonMode()) && 
                (!sb.getConfig(plasmaSwitchboardConstants.CLUSTER_MODE, "").equals(plasmaSwitchboardConstants.CLUSTER_MODE_PUBLIC_CLUSTER)) &&
                (!sb.getConfig(plasmaSwitchboardConstants.CLUSTER_MODE, "").equals(plasmaSwitchboardConstants.CLUSTER_MODE_PRIVATE_CLUSTER)));
        
        if (((robinsonPrivateCase) || (coreCrawlJobSize() <= 20)) && (limitCrawlJobSize() > 0)) {
            // move some tasks to the core crawl job so we have something to do
            final int toshift = Math.min(10, limitCrawlJobSize()); // this cannot be a big number because the balancer makes a forced waiting if it cannot balance
            for (int i = 0; i < toshift; i++) {
                noticeURL.shift(NoticedURL.STACK_TYPE_LIMIT, NoticedURL.STACK_TYPE_CORE, sb.webIndex.profilesActiveCrawls);
            }
            log.logInfo("shifted " + toshift + " jobs from global crawl to local crawl (coreCrawlJobSize()=" + coreCrawlJobSize() +
                    ", limitCrawlJobSize()=" + limitCrawlJobSize() + ", cluster.mode=" + sb.getConfig(plasmaSwitchboardConstants.CLUSTER_MODE, "") +
                    ", robinsonMode=" + ((sb.isRobinsonMode()) ? "on" : "off"));
        }
        
        if(!crawlIsPossible(NoticedURL.STACK_TYPE_CORE, "Core")) return false;
        
        if(isPaused(plasmaSwitchboardConstants.CRAWLJOB_LOCAL_CRAWL)) return false;
        
        // do a local crawl        
        CrawlEntry urlEntry = null;
        while (urlEntry == null && noticeURL.stackSize(NoticedURL.STACK_TYPE_CORE) > 0) {
            final String stats = "LOCALCRAWL[" + noticeURL.stackSize(NoticedURL.STACK_TYPE_CORE) + ", " + noticeURL.stackSize(NoticedURL.STACK_TYPE_LIMIT) + ", " + noticeURL.stackSize(NoticedURL.STACK_TYPE_OVERHANG) + ", " + noticeURL.stackSize(NoticedURL.STACK_TYPE_REMOTE) + "]";
            try {
                urlEntry = noticeURL.pop(NoticedURL.STACK_TYPE_CORE, true, sb.webIndex.profilesActiveCrawls);
                if (urlEntry == null) continue;
                final String profileHandle = urlEntry.profileHandle();
                // System.out.println("DEBUG plasmaSwitchboard.processCrawling:
                // profileHandle = " + profileHandle + ", urlEntry.url = " + urlEntry.url());
                if (profileHandle == null) {
                    log.logSevere(stats + ": NULL PROFILE HANDLE '" + urlEntry.profileHandle() + "' for URL " + urlEntry.url());
                    return true;
                }
                generateCrawl(urlEntry, stats, profileHandle);
                return true;
            } catch (final IOException e) {
                log.logSevere(stats + ": CANNOT FETCH ENTRY: " + e.getMessage(), e);
                if (e.getMessage().indexOf("hash is null") > 0) noticeURL.clear(NoticedURL.STACK_TYPE_CORE);
            }
        }
        return true;
    }

    /**
     * Make some checks if crawl is valid and start it
     * 
     * @param urlEntry
     * @param profileHandle
     * @param stats String for log prefixing
     * @return
     */
    private void generateCrawl(CrawlEntry urlEntry, final String stats, final String profileHandle) {
        final CrawlProfile.entry profile = sb.webIndex.profilesActiveCrawls.getEntry(profileHandle);
        if (profile != null) {

            // check if the protocol is supported
            final yacyURL url = urlEntry.url();
            final String urlProtocol = url.getProtocol();
            if (this.sb.crawlQueues.isSupportedProtocol(urlProtocol)) {

                if (this.log.isFine())
                    log.logFine(stats + ": URL=" + urlEntry.url()
                            + ", initiator=" + urlEntry.initiator()
                            + ", crawlOrder=" + ((profile.remoteIndexing()) ? "true" : "false")
                            + ", depth=" + urlEntry.depth()
                            + ", crawlDepth=" + profile.depth()
                            + ", must-match=" + profile.mustMatchPattern().toString()
                            + ", must-not-match=" + profile.mustNotMatchPattern().toString()
                            + ", permission=" + ((sb.webIndex.seedDB == null) ? "undefined" : (((sb.webIndex.seedDB.mySeed().isSenior()) || (sb.webIndex.seedDB.mySeed().isPrincipal())) ? "true" : "false")));

                processLocalCrawling(urlEntry, stats);
            } else {
                this.log.logSevere("Unsupported protocol in URL '" + url.toString());
            }
        } else {
            log.logWarning(stats + ": LOST PROFILE HANDLE '" + urlEntry.profileHandle() + "' for URL " + urlEntry.url());
        }
    }

    /**
     * if crawling was paused we have to wait until we were notified to continue
     * blocks until pause is ended
     * @param crawljob
     * @return
     */
    private boolean isPaused(String crawljob) {
        final Object[] status = sb.crawlJobsStatus.get(crawljob);
        boolean pauseEnded = false;
        synchronized(status[plasmaSwitchboardConstants.CRAWLJOB_SYNC]) {
            if (((Boolean)status[plasmaSwitchboardConstants.CRAWLJOB_STATUS]).booleanValue()) {
                try {
                    status[plasmaSwitchboardConstants.CRAWLJOB_SYNC].wait();
                }
                catch (final InterruptedException e) { pauseEnded = true;}
            }
        }
        return pauseEnded;
    }

    /**
     * Checks if crawl queue has elements and new crawl will not exceed thread-limit
     * @param stackType 
     * @param type
     * @return
     */
    private boolean crawlIsPossible(int stackType, final String type) {
        if (noticeURL.stackSize(stackType) == 0) {
            //log.logDebug("GlobalCrawl: queue is empty");
            return false;
        }
        if (sb.webIndex.queuePreStack.size() >= (int) sb.getConfigLong(plasmaSwitchboardConstants.INDEXER_SLOTS, 30)) {
            if (this.log.isFine()) log.logFine(type + "Crawl: too many processes in indexing queue, dismissed (" + "sbQueueSize=" + sb.webIndex.queuePreStack.size() + ")");
            return false;
        }
        if (this.size() >= sb.getConfigLong(plasmaSwitchboardConstants.CRAWLER_THREADS_ACTIVE_MAX, 10)) {
            if (this.log.isFine()) log.logFine(type + "Crawl: too many processes in loader queue, dismissed (" + "cacheLoader=" + this.size() + ")");
            return false;
        }
        if (sb.onlineCaution()) {
            if (this.log.isFine()) log.logFine(type + "Crawl: online caution, omitting processing");
            return false;
        }
        return true;
    }
    
    public boolean remoteCrawlLoaderJob() {
        // check if we are allowed to crawl urls provided by other peers
        if (!sb.webIndex.seedDB.mySeed().getFlagAcceptRemoteCrawl()) {
            //this.log.logInfo("remoteCrawlLoaderJob: not done, we are not allowed to do that");
            return false;
        }
        
        // check if we are a senior peer
        if (!sb.webIndex.seedDB.mySeed().isActive()) {
            //this.log.logInfo("remoteCrawlLoaderJob: not done, this should be a senior or principal peer");
            return false;
        }
        
        if (sb.webIndex.queuePreStack.size() >= (int) sb.getConfigLong(plasmaSwitchboardConstants.INDEXER_SLOTS, 30) / 2) {
            if (this.log.isFine()) log.logFine("remoteCrawlLoaderJob: too many processes in indexing queue, dismissed (" + "sbQueueSize=" + sb.webIndex.queuePreStack.size() + ")");
            return false;
        }
        
        if (this.size() >= sb.getConfigLong(plasmaSwitchboardConstants.CRAWLER_THREADS_ACTIVE_MAX, 10)) {
            if (this.log.isFine()) log.logFine("remoteCrawlLoaderJob: too many processes in loader queue, dismissed (" + "cacheLoader=" + this.size() + ")");
            return false;
        }
        
        if (sb.onlineCaution()) {
            if (this.log.isFine()) log.logFine("remoteCrawlLoaderJob: online caution, omitting processing");
            return false;
        }
        
        if (remoteTriggeredCrawlJobSize() > 100) {
            if (this.log.isFine()) log.logFine("remoteCrawlLoaderJob: the remote-triggered crawl job queue is filled, omitting processing");
            return false;
        }
        
        if (coreCrawlJobSize() > 0) {
            if (this.log.isFine()) log.logFine("remoteCrawlLoaderJob: a local crawl is running, omitting processing");
            return false;
        }
        
        // check if we have an entry in the provider list, otherwise fill the list
        yacySeed seed;
        if (remoteCrawlProviderHashes.size() == 0) {
            if (sb.webIndex.seedDB != null && sb.webIndex.seedDB.sizeConnected() > 0) {
                final Iterator<yacySeed> e = PeerSelection.getProvidesRemoteCrawlURLs(sb.webIndex.seedDB);
                while (e.hasNext()) {
                    seed = e.next();
                    if (seed != null) {
                        remoteCrawlProviderHashes.add(seed.hash);
                    }
                }
            }
        }
        if (remoteCrawlProviderHashes.size() == 0) return false;
        
        // take one entry from the provider list and load the entries from the remote peer
        seed = null;
        String hash = null;
        while ((seed == null) && (remoteCrawlProviderHashes.size() > 0)) {
            hash = remoteCrawlProviderHashes.remove(remoteCrawlProviderHashes.size() - 1);
            if (hash == null) continue;
            seed = sb.webIndex.seedDB.get(hash);
            if (seed == null) continue;
            // check if the peer is inside our cluster
            if ((sb.isRobinsonMode()) && (!sb.isInMyCluster(seed))) {
                seed = null;
                continue;
            }
        }
        if (seed == null) return false;
        
        // we know a peer which should provide remote crawl entries. load them now.
        final RSSFeed feed = yacyClient.queryRemoteCrawlURLs(sb.webIndex.seedDB, seed, 30, 60000);
        if (feed == null || feed.size() == 0) {
            // something is wrong with this provider. To prevent that we get not stuck with this peer
            // we remove it from the peer list
            sb.webIndex.seedDB.peerActions.peerDeparture(seed, "no results from provided remote crawls");
            // ask another peer
            return remoteCrawlLoaderJob();
        }
        
        // parse the rss
        yacyURL url, referrer;
        Date loaddate;
        for (final RSSMessage item: feed) {
            //System.out.println("URL=" + item.getLink() + ", desc=" + item.getDescription() + ", pubDate=" + item.getPubDate());
            
            // put url on remote crawl stack
            try {
                url = new yacyURL(item.getLink(), null);
            } catch (final MalformedURLException e) {
                url = null;
            }
            try {
                referrer = new yacyURL(item.getReferrer(), null);
            } catch (final MalformedURLException e) {
                referrer = null;
            }
            try {
                loaddate = DateFormatter.parseShortSecond(item.getPubDate());
            } catch (final ParseException e) {
                loaddate = new Date();
            }
            final String urlRejectReason = sb.crawlStacker.urlInAcceptedDomain(url);
            if (urlRejectReason == null) {
                // stack url
                if (sb.getLog().isFinest()) sb.getLog().logFinest("crawlOrder: stack: url='" + url + "'");
                sb.crawlStacker.enqueueEntry(new CrawlEntry(
                        hash,
                        url,
                        (referrer == null) ? null : referrer.hash(),
                        item.getDescription(),
                        null,
                        loaddate,
                        sb.webIndex.defaultRemoteProfile.handle(),
                        0,
                        0,
                        0
                ));
            } else {
                log.logWarning("crawlOrder: Rejected URL '" + urlToString(url) + "': " + urlRejectReason);
            }
        }
        return true;
    }

    /**
     * @param url
     * @return
     */
    private String urlToString(final yacyURL url) {
        return (url == null ? "null" : url.toNormalform(true, false));
    }
    
    public int limitCrawlJobSize() {
        return noticeURL.stackSize(NoticedURL.STACK_TYPE_LIMIT);
    }
    
    public int remoteTriggeredCrawlJobSize() {
        return noticeURL.stackSize(NoticedURL.STACK_TYPE_REMOTE);
    }
    
    public boolean remoteTriggeredCrawlJob() {
        // work off crawl requests that had been placed by other peers to our crawl stack
        
        // do nothing if either there are private processes to be done
        // or there is no global crawl on the stack
        if(!crawlIsPossible(NoticedURL.STACK_TYPE_REMOTE, "Global")) return false;

        if(isPaused(plasmaSwitchboardConstants.CRAWLJOB_REMOTE_TRIGGERED_CRAWL)) return false;
        
        // we don't want to crawl a global URL globally, since WE are the global part. (from this point of view)
        final String stats = "REMOTETRIGGEREDCRAWL[" + noticeURL.stackSize(NoticedURL.STACK_TYPE_CORE) + ", " + noticeURL.stackSize(NoticedURL.STACK_TYPE_LIMIT) + ", " + noticeURL.stackSize(NoticedURL.STACK_TYPE_OVERHANG) + ", "
                        + noticeURL.stackSize(NoticedURL.STACK_TYPE_REMOTE) + "]";
        try {
            final CrawlEntry urlEntry = noticeURL.pop(NoticedURL.STACK_TYPE_REMOTE, true, sb.webIndex.profilesActiveCrawls);
            final String profileHandle = urlEntry.profileHandle();
            // System.out.println("DEBUG plasmaSwitchboard.processCrawling:
            // profileHandle = " + profileHandle + ", urlEntry.url = " +
            // urlEntry.url());
            generateCrawl(urlEntry, stats, profileHandle);
            return true;
        } catch (final IOException e) {
            log.logSevere(stats + ": CANNOT FETCH ENTRY: " + e.getMessage(), e);
            if (e.getMessage().indexOf("hash is null") > 0) noticeURL.clear(NoticedURL.STACK_TYPE_REMOTE);
            return true;
        }
    }
    
    private void processLocalCrawling(final CrawlEntry entry, final String stats) {
        // work off one Crawl stack entry
        if ((entry == null) || (entry.url() == null)) {
            log.logInfo(stats + ": urlEntry = null");
            return;
        }
        new crawlWorker(entry);
        
        log.logInfo(stats + ": enqueued for load " + entry.url() + " [" + entry.url().hash() + "]");
        return;
    }
    
    public indexDocumentMetadata loadResourceFromWeb(
            final yacyURL url, 
            final int socketTimeout,
            final boolean keepInMemory,
            final boolean forText,
            final boolean global
    ) throws IOException {
        
        final CrawlEntry centry = new CrawlEntry(
                sb.webIndex.seedDB.mySeed().hash, 
                url, 
                "", 
                "", 
                new Date(),
                new Date(),
                (forText) ?
                    ((global) ?
                        sb.webIndex.defaultTextSnippetGlobalProfile.handle() :
                        sb.webIndex.defaultTextSnippetLocalProfile.handle())
                    :
                    ((global) ?
                        sb.webIndex.defaultMediaSnippetGlobalProfile.handle() :
                        sb.webIndex.defaultMediaSnippetLocalProfile.handle()), // crawl profile
                0, 
                0, 
                0);
        
        return loader.load(centry, (forText) ? plasmaParser.PARSER_MODE_CRAWLER : plasmaParser.PARSER_MODE_IMAGE);
    }
    
    public int size() {
        return workers.size();
    }
    
    protected final class crawlWorker extends Thread {
        
        public CrawlEntry entry;
        private final Integer code;
        
        public crawlWorker(final CrawlEntry entry) {
            this.entry = entry;
            this.entry.setStatus("worker-initialized", serverProcessorJob.STATUS_INITIATED);
            this.code = Integer.valueOf(entry.hashCode());
            if (!workers.containsKey(code)) {
                workers.put(code, this);
                this.start();
            }
        }
        
        public void run() {
            try {
                // checking robots.txt for http(s) resources
                this.entry.setStatus("worker-checkingrobots", serverProcessorJob.STATUS_STARTED);
                if ((entry.url().getProtocol().equals("http") || entry.url().getProtocol().equals("https")) && sb.robots.isDisallowed(entry.url())) {
                    if (log.isFine()) log.logFine("Crawling of URL '" + entry.url().toString() + "' disallowed by robots.txt.");
                    final ZURL.Entry eentry = errorURL.newEntry(
                            this.entry,
                            sb.webIndex.seedDB.mySeed().hash,
                            new Date(),
                            1,
                            "denied by robots.txt");
                    eentry.store();
                    errorURL.push(eentry);         
                } else {
                    // starting a load from the internet
                    this.entry.setStatus("worker-loading", serverProcessorJob.STATUS_RUNNING);
                    final String result = loader.process(this.entry, plasmaParser.PARSER_MODE_CRAWLER);
                    if (result != null) {
                        final ZURL.Entry eentry = errorURL.newEntry(
                                this.entry,
                                sb.webIndex.seedDB.mySeed().hash,
                                new Date(),
                                1,
                                "cannot load: " + result);
                        eentry.store();
                        errorURL.push(eentry);
                    } else {
                        this.entry.setStatus("worker-processed", serverProcessorJob.STATUS_FINISHED);
                    }
                }
            } catch (final Exception e) {
                final ZURL.Entry eentry = errorURL.newEntry(
                        this.entry,
                        sb.webIndex.seedDB.mySeed().hash,
                        new Date(),
                        1,
                        e.getMessage() + " - in worker");
                eentry.store();
                errorURL.push(eentry);
                e.printStackTrace();
            } finally {
                workers.remove(code);
                this.entry.setStatus("worker-finalized", serverProcessorJob.STATUS_FINISHED);
            }
        }
        
    }
    
}
