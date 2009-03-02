// plasmaSearchEvent.java
// (C) 2005 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 10.10.2005 on http://yacy.net
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

package de.anomic.plasma;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import de.anomic.crawler.ResultURLs;
import de.anomic.index.URLMetadata;
import de.anomic.kelondro.order.Bitfield;
import de.anomic.kelondro.text.Reference;
import de.anomic.kelondro.text.ReferenceContainer;
import de.anomic.kelondro.text.ReferenceVars;
import de.anomic.kelondro.util.MemoryControl;
import de.anomic.kelondro.util.SetTools;
import de.anomic.kelondro.util.SortStack;
import de.anomic.kelondro.util.SortStore;
import de.anomic.kelondro.util.Log;
import de.anomic.plasma.plasmaSnippetCache.MediaSnippet;
import de.anomic.server.serverProfiling;
import de.anomic.yacy.yacySearch;
import de.anomic.yacy.yacySeed;
import de.anomic.yacy.yacyURL;
import de.anomic.yacy.dht.FlatWordPartitionScheme;

public final class plasmaSearchEvent {
    
    public static final String INITIALIZATION = "initialization";
    public static final String COLLECTION = "collection";
    public static final String JOIN = "join";
    public static final String PRESORT = "presort";
    public static final String URLFETCH = "urlfetch";
    public static final String NORMALIZING = "normalizing";
    public static final String FINALIZATION = "finalization";
    
    public static int workerThreadCount = 10;
    public static String lastEventID = "";
    private static ConcurrentHashMap<String, plasmaSearchEvent> lastEvents = new ConcurrentHashMap<String, plasmaSearchEvent>(); // a cache for objects from this class: re-use old search requests
    public static final long eventLifetime = 60000; // the time an event will stay in the cache, 1 Minute
    private static final int max_results_preparation = 300;
    
    private long eventTime;
    plasmaSearchQuery query;
    private final plasmaWordIndex wordIndex;
    plasmaSearchRankingProcess rankedCache; // ordered search results, grows dynamically as all the query threads enrich this container
    private final Map<String, TreeMap<String, String>> rcAbstracts; // cache for index abstracts; word:TreeMap mapping where the embedded TreeMap is a urlhash:peerlist relation
    private yacySearch[] primarySearchThreads, secondarySearchThreads;
    private Thread localSearchThread;
    private final TreeMap<String, String> preselectedPeerHashes;
    //private Object[] references;
    public  TreeMap<String, String> IAResults;
    public  TreeMap<String, Integer> IACount;
    public  String IAmaxcounthash, IAneardhthash;
    private resultWorker[] workerThreads;
    SortStore<ResultEntry> result;
    SortStore<plasmaSnippetCache.MediaSnippet> images; // container to sort images by size
    HashMap<String, String> failedURLs; // a mapping from a urlhash to a fail reason string
    TreeSet<String> snippetFetchWordHashes; // a set of word hashes that are used to match with the snippets
    long urlRetrievalAllTime;
    long snippetComputationAllTime;
    ResultURLs crawlResults;
    
    @SuppressWarnings("unchecked")
    private plasmaSearchEvent(final plasmaSearchQuery query,
                             final plasmaWordIndex wordIndex,
                             final ResultURLs crawlResults,
                             final TreeMap<String, String> preselectedPeerHashes,
                             final boolean generateAbstracts) {
        this.eventTime = System.currentTimeMillis(); // for lifetime check
        this.wordIndex = wordIndex;
        this.crawlResults = crawlResults;
        this.query = query;
        this.rcAbstracts = (query.queryHashes.size() > 1) ? new TreeMap<String, TreeMap<String, String>>() : null; // generate abstracts only for combined searches
        this.primarySearchThreads = null;
        this.secondarySearchThreads = null;
        this.preselectedPeerHashes = preselectedPeerHashes;
        this.IAResults = new TreeMap<String, String>();
        this.IACount = new TreeMap<String, Integer>();
        this.IAmaxcounthash = null;
        this.IAneardhthash = null;
        this.urlRetrievalAllTime = 0;
        this.snippetComputationAllTime = 0;
        this.workerThreads = null;
        this.localSearchThread = null;
        this.result = new SortStore<ResultEntry>(-1); // this is the result, enriched with snippets, ranked and ordered by ranking
        this.images = new SortStore<plasmaSnippetCache.MediaSnippet>(-1);
        this.failedURLs = new HashMap<String, String>(); // a map of urls to reason strings where a worker thread tried to work on, but failed.
        
        // snippets do not need to match with the complete query hashes,
        // only with the query minus the stopwords which had not been used for the search
        final TreeSet<String> filtered = SetTools.joinConstructive(query.queryHashes, plasmaSwitchboard.stopwords);
        this.snippetFetchWordHashes = (TreeSet<String>) query.queryHashes.clone();
        if ((filtered != null) && (filtered.size() > 0)) {
            SetTools.excludeDestructive(this.snippetFetchWordHashes, plasmaSwitchboard.stopwords);
        }
        
        final long start = System.currentTimeMillis();
        if ((query.domType == plasmaSearchQuery.SEARCHDOM_GLOBALDHT) ||
            (query.domType == plasmaSearchQuery.SEARCHDOM_CLUSTERALL)) {
            // do a global search
            this.rankedCache = new plasmaSearchRankingProcess(wordIndex, query, max_results_preparation, 16);
            
            final int fetchpeers = 12;

            // the result of the fetch is then in the rcGlobal
            final long timer = System.currentTimeMillis();
            Log.logFine("SEARCH_EVENT", "STARTING " + fetchpeers + " THREADS TO CATCH EACH " + query.displayResults() + " URLs");
            this.primarySearchThreads = yacySearch.primaryRemoteSearches(
                    plasmaSearchQuery.hashSet2hashString(query.queryHashes),
                    plasmaSearchQuery.hashSet2hashString(query.excludeHashes),
                    "",
                    query.prefer,
                    query.urlMask,
                    query.targetlang,
                    query.displayResults(),
                    query.maxDistance,
                    wordIndex,
                    crawlResults,
                    rankedCache,
                    rcAbstracts,
                    fetchpeers,
                    plasmaSwitchboard.urlBlacklist,
                    query.ranking,
                    query.constraint,
                    (query.domType == plasmaSearchQuery.SEARCHDOM_GLOBALDHT) ? null : preselectedPeerHashes);
            serverProfiling.update("SEARCH", new plasmaProfiling.searchEvent(query.id(true), "remote search thread start", this.primarySearchThreads.length, System.currentTimeMillis() - timer));
            
            // meanwhile do a local search
            localSearchThread = new localSearchProcess();
            localSearchThread.start();
           
            // finished searching
            Log.logFine("SEARCH_EVENT", "SEARCH TIME AFTER GLOBAL-TRIGGER TO " + primarySearchThreads.length + " PEERS: " + ((System.currentTimeMillis() - start) / 1000) + " seconds");
        } else {
            // do a local search
            this.rankedCache = new plasmaSearchRankingProcess(wordIndex, query, max_results_preparation, 2);
            this.rankedCache.execQuery();
            //plasmaWordIndex.Finding finding = wordIndex.retrieveURLs(query, false, 2, ranking, process);
            
            if (generateAbstracts) {
                // compute index abstracts
                final long timer = System.currentTimeMillis();
                int maxcount = -1;
                long mindhtdistance = Long.MAX_VALUE, l;
                String wordhash;
                for (Map.Entry<String, ReferenceContainer> entry : this.rankedCache.searchContainerMaps()[0].entrySet()) {
                    wordhash = entry.getKey();
                    final ReferenceContainer container = entry.getValue();
                    assert (container.getWordHash().equals(wordhash));
                    if (container.size() > maxcount) {
                        IAmaxcounthash = wordhash;
                        maxcount = container.size();
                    }
                    l = FlatWordPartitionScheme.std.dhtDistance(wordhash, null, wordIndex.seedDB.mySeed());
                    if (l < mindhtdistance) {
                        // calculate the word hash that is closest to our dht position
                        mindhtdistance = l;
                        IAneardhthash = wordhash;
                    }
                    IACount.put(wordhash, Integer.valueOf(container.size()));
                    IAResults.put(wordhash, ReferenceContainer.compressIndex(container, null, 1000).toString());
                }
                serverProfiling.update("SEARCH", new plasmaProfiling.searchEvent(query.id(true), "abstract generation", this.rankedCache.searchContainerMaps()[0].size(), System.currentTimeMillis() - timer));
            }
        }
        
        if (query.onlineSnippetFetch) {
            // start worker threads to fetch urls and snippets
            this.workerThreads = new resultWorker[workerThreadCount];
            for (int i = 0; i < workerThreadCount; i++) {
                this.workerThreads[i] = new resultWorker(i, 10000, 2);
                this.workerThreads[i].start();
            }
            serverProfiling.update("SEARCH", new plasmaProfiling.searchEvent(query.id(true), "online snippet fetch threads started", 0, 0));
        } else {
            final long timer = System.currentTimeMillis();
            // use only a single worker thread, thats enough
            resultWorker worker = new resultWorker(0, 3000, 0);
            worker.run();
            serverProfiling.update("SEARCH", new plasmaProfiling.searchEvent(query.id(true), "offline snippet fetch", result.size(), System.currentTimeMillis() - timer));
        }
        
        // clean up events
        cleanupEvents(false);
        serverProfiling.update("SEARCH", new plasmaProfiling.searchEvent(query.id(true), "event-cleanup", 0, 0));
        
        // store this search to a cache so it can be re-used
        if (MemoryControl.available() < 1024 * 1024 * 10) cleanupEvents(true);
        lastEventID = query.id(false);
        lastEvents.put(lastEventID, this);
    }

    private class localSearchProcess extends Thread {
        
        public localSearchProcess() {
        }
        
        public void run() {
            // do a local search
            
            // sort the local containers and truncate it to a limited count,
            // so following sortings together with the global results will be fast
            rankedCache.execQuery();
        }
    }

    public static void cleanupEvents(final boolean all) {
        // remove old events in the event cache
        final Iterator<plasmaSearchEvent> i = lastEvents.values().iterator();
        plasmaSearchEvent cleanEvent;
        while (i.hasNext()) {
            cleanEvent = i.next();
            if ((all) || (cleanEvent.eventTime + eventLifetime < System.currentTimeMillis())) {
                // execute deletion of failed words
                int rw = cleanEvent.failedURLs.size();
                if (rw > 0) {
                    final Set<String> removeWords = cleanEvent.query.queryHashes;
                    removeWords.addAll(cleanEvent.query.excludeHashes);
                    cleanEvent.wordIndex.removeEntriesMultiple(removeWords, cleanEvent.failedURLs.keySet());
                    Log.logInfo("SearchEvents", "cleaning up event " + cleanEvent.query.id(true) + ", removed " + rw + " URL references on " + removeWords.size() + " words");
                }                
                
                // remove the event
                i.remove();
            }
        }
    }
    
    ResultEntry obtainResultEntry(final URLMetadata page, final int snippetFetchMode) {

        // a search result entry needs some work to produce a result Entry:
        // - check if url entry exists in LURL-db
        // - check exclusions, constraints, masks, media-domains
        // - load snippet (see if page exists) and check if snippet contains searched word

        // Snippet Fetching can has 3 modes:
        // 0 - do not fetch snippets
        // 1 - fetch snippets offline only
        // 2 - online snippet fetch
        
        // load only urls if there was not yet a root url of that hash
        // find the url entry

        long startTime = System.currentTimeMillis();
        final URLMetadata.Components comp = page.comp();
        final String pagetitle = comp.dc_title().toLowerCase();
        if (comp.url() == null) {
            registerFailure(page.hash(), "url corrupted (null)");
            return null; // rare case where the url is corrupted
        }
        final String pageurl = comp.url().toString().toLowerCase();
        final String pageauthor = comp.dc_creator().toLowerCase();
        final long dbRetrievalTime = System.currentTimeMillis() - startTime;
        
        // check exclusion
        if ((plasmaSearchQuery.matches(pagetitle, query.excludeHashes)) ||
            (plasmaSearchQuery.matches(pageurl, query.excludeHashes)) ||
            (plasmaSearchQuery.matches(pageauthor, query.excludeHashes))) {
            return null;
        }
            
        // check url mask
        if (!(pageurl.matches(query.urlMask))) {
            return null;
        }
            
        // check constraints
        if ((query.constraint != null) &&
            (query.constraint.get(plasmaCondenser.flag_cat_indexof)) &&
            (!(comp.dc_title().startsWith("Index of")))) {
            final Iterator<String> wi = query.queryHashes.iterator();
            while (wi.hasNext()) wordIndex.removeReference(wi.next(), page.hash());
            registerFailure(page.hash(), "index-of constraint not fullfilled");
            return null;
        }
        
        if ((query.contentdom == plasmaSearchQuery.CONTENTDOM_AUDIO) && (page.laudio() == 0)) {
            registerFailure(page.hash(), "contentdom-audio constraint not fullfilled");
            return null;
        }
        if ((query.contentdom == plasmaSearchQuery.CONTENTDOM_VIDEO) && (page.lvideo() == 0)) {
            registerFailure(page.hash(), "contentdom-video constraint not fullfilled");
            return null;
        }
        if ((query.contentdom == plasmaSearchQuery.CONTENTDOM_IMAGE) && (page.limage() == 0)) {
            registerFailure(page.hash(), "contentdom-image constraint not fullfilled");
            return null;
        }
        if ((query.contentdom == plasmaSearchQuery.CONTENTDOM_APP) && (page.lapp() == 0)) {
            registerFailure(page.hash(), "contentdom-app constraint not fullfilled");
            return null;
        }

        if (snippetFetchMode == 0) {
            return new ResultEntry(page, wordIndex, null, null, dbRetrievalTime, 0); // result without snippet
        }
        
        // load snippet
        if (query.contentdom == plasmaSearchQuery.CONTENTDOM_TEXT) {
            // attach text snippet
            startTime = System.currentTimeMillis();
            final plasmaSnippetCache.TextSnippet snippet = plasmaSnippetCache.retrieveTextSnippet(comp, snippetFetchWordHashes, (snippetFetchMode == 2), ((query.constraint != null) && (query.constraint.get(plasmaCondenser.flag_cat_indexof))), 180, 3000, (snippetFetchMode == 2) ? Integer.MAX_VALUE : 30000, query.isGlobal());
            final long snippetComputationTime = System.currentTimeMillis() - startTime;
            Log.logInfo("SEARCH_EVENT", "text snippet load time for " + comp.url() + ": " + snippetComputationTime + ", " + ((snippet.getErrorCode() < 11) ? "snippet found" : ("no snippet found (" + snippet.getError() + ")")));
            
            if (snippet.getErrorCode() < 11) {
                // we loaded the file and found the snippet
                return new ResultEntry(page, wordIndex, snippet, null, dbRetrievalTime, snippetComputationTime); // result with snippet attached
            } else if (snippetFetchMode == 1) {
                // we did not demand online loading, therefore a failure does not mean that the missing snippet causes a rejection of this result
                // this may happen during a remote search, because snippet loading is omitted to retrieve results faster
                return new ResultEntry(page, wordIndex, null, null, dbRetrievalTime, snippetComputationTime); // result without snippet
            } else {
                // problems with snippet fetch
                registerFailure(page.hash(), "no text snippet for URL " + comp.url());
                if (!wordIndex.seedDB.mySeed().isVirgin()) plasmaSnippetCache.failConsequences(snippet, query.id(false));
                return null;
            }
        } else {
            // attach media information
            startTime = System.currentTimeMillis();
            final ArrayList<MediaSnippet> mediaSnippets = plasmaSnippetCache.retrieveMediaSnippets(comp.url(), snippetFetchWordHashes, query.contentdom, (snippetFetchMode == 2), 6000, query.isGlobal());
            final long snippetComputationTime = System.currentTimeMillis() - startTime;
            Log.logInfo("SEARCH_EVENT", "media snippet load time for " + comp.url() + ": " + snippetComputationTime);
            
            if ((mediaSnippets != null) && (mediaSnippets.size() > 0)) {
                // found media snippets, return entry
                return new ResultEntry(page, wordIndex, null, mediaSnippets, dbRetrievalTime, snippetComputationTime);
            } else if (snippetFetchMode == 1) {
                return new ResultEntry(page, wordIndex, null, null, dbRetrievalTime, snippetComputationTime);
            } else {
                // problems with snippet fetch
                registerFailure(page.hash(), "no media snippet for URL " + comp.url());
                return null;
            }
        }
        // finished, no more actions possible here
    }
    
    private boolean anyWorkerAlive() {
        if (this.workerThreads == null) return false;
        for (int i = 0; i < workerThreadCount; i++) {
           if ((this.workerThreads[i] != null) &&
        	   (this.workerThreads[i].isAlive()) &&
        	   (this.workerThreads[i].busytime() < 3000)) return true;
        }
        return false;
    }
    
    boolean anyRemoteSearchAlive() {
        // check primary search threads
        if ((this.primarySearchThreads != null) && (this.primarySearchThreads.length != 0)) {
            for (int i = 0; i < this.primarySearchThreads.length; i++) {
                if ((this.primarySearchThreads[i] != null) && (this.primarySearchThreads[i].isAlive())) return true;
            }
        }
        // maybe a secondary search thread is alive, check this
        if ((this.secondarySearchThreads != null) && (this.secondarySearchThreads.length != 0)) {
            for (int i = 0; i < this.secondarySearchThreads.length; i++) {
                if ((this.secondarySearchThreads[i] != null) && (this.secondarySearchThreads[i].isAlive())) return true;
            }
        }
        return false;
    }
    
    private int countFinishedRemoteSearch() {
        int count = 0;
        // check only primary search threads
        if ((this.primarySearchThreads != null) && (this.primarySearchThreads.length != 0)) {
            for (int i = 0; i < this.primarySearchThreads.length; i++) {
                if ((this.primarySearchThreads[i] == null) || (!(this.primarySearchThreads[i].isAlive()))) count++;
            }
        }
        return count;
    }
    
    public plasmaSearchQuery getQuery() {
        return query;
    }
    
    public yacySearch[] getPrimarySearchThreads() {
        return primarySearchThreads;
    }
    
    public yacySearch[] getSecondarySearchThreads() {
        return secondarySearchThreads;
    }
    
    public plasmaSearchRankingProcess getRankingResult() {
        return this.rankedCache;
    }
    
    public long getURLRetrievalTime() {
        return this.urlRetrievalAllTime;
    }
    
    public long getSnippetComputationTime() {
        return this.snippetComputationAllTime;
    }

    public static plasmaSearchEvent getEvent(final String eventID) {
        return lastEvents.get(eventID);
    }
    
    public static plasmaSearchEvent getEvent(
            final plasmaSearchQuery query,
            final plasmaSearchRankingProfile ranking,
            final plasmaWordIndex wordIndex,
            final ResultURLs crawlResults,
            final TreeMap<String, String> preselectedPeerHashes,
            final boolean generateAbstracts) {
        
        String id = query.id(false);
        plasmaSearchEvent event = lastEvents.get(id);
        if (plasmaSwitchboard.getSwitchboard().crawlQueues.noticeURL.size() > 0 && event != null && System.currentTimeMillis() - event.eventTime > 60000) {
            // if a local crawl is ongoing, don't use the result from the cache to use possibly more results that come from the current crawl
            // to prevent that this happens during a person switches between the different result pages, a re-search happens no more than
            // once a minute
            lastEvents.remove(id);
            event = null;
        } else {
            if (event != null) {
                //re-new the event time for this event, so it is not deleted next time too early
                event.eventTime = System.currentTimeMillis();
                // replace the query, because this contains the current result offset
                event.query = query;
            }
        }
        if (event == null) {
            // generate a new event
            event = new plasmaSearchEvent(query, wordIndex, crawlResults, preselectedPeerHashes, generateAbstracts);
        } else {
            // if worker threads had been alive, but did not succeed, start them again to fetch missing links
            if ((!event.anyWorkerAlive()) &&
                (((query.contentdom == plasmaSearchQuery.CONTENTDOM_IMAGE) && (event.images.size() + 30 < query.neededResults())) ||
                 (event.result.size() < query.neededResults() + 10)) &&
                 (event.query.onlineSnippetFetch) &&
                (event.getRankingResult().getLocalResourceSize() + event.getRankingResult().getRemoteResourceSize() > event.result.size())) {
                // set new timeout
                event.eventTime = System.currentTimeMillis();
                // start worker threads to fetch urls and snippets
                event.workerThreads = new resultWorker[workerThreadCount];
                resultWorker worker;
                for (int i = 0; i < workerThreadCount; i++) {
                    worker = event.new resultWorker(i, 6000, (query.onlineSnippetFetch) ? 2 : 0);
                    worker.start();
                    event.workerThreads[i] = worker;
                }
            }
        }
    
        return event;
    }

    private class resultWorker extends Thread {
        
        private final long timeout; // the date until this thread should try to work
        private long lastLifeSign; // when the last time the run()-loop was executed
        private final int id;
        private int snippetMode;
        
        public resultWorker(final int id, final long maxlifetime, int snippetMode) {
            this.id = id;
            this.snippetMode = snippetMode;
            this.lastLifeSign = System.currentTimeMillis();
            this.timeout = System.currentTimeMillis() + Math.max(1000, maxlifetime);
        }

        public void run() {

            // start fetching urls and snippets
            URLMetadata page;
            final int fetchAhead = snippetMode == 0 ? 0 : 10;
            while (System.currentTimeMillis() < this.timeout) {
                this.lastLifeSign = System.currentTimeMillis();

                // check if we have enough
                if ((query.contentdom == plasmaSearchQuery.CONTENTDOM_IMAGE) && (images.size() >= query.neededResults() + fetchAhead)) break;
                if ((query.contentdom != plasmaSearchQuery.CONTENTDOM_IMAGE) && (result.size() >= query.neededResults() + fetchAhead)) break;

                // get next entry
                page = rankedCache.bestURL(true);
                if (page == null) {
                	if (!anyRemoteSearchAlive()) break; // we cannot expect more results
                    // if we did not get another entry, sleep some time and try again
                    try {Thread.sleep(100);} catch (final InterruptedException e1) {}
                    continue;
                }
                if (result.exists(page.hash().hashCode())) continue;
                if (failedURLs.get(page.hash()) != null) continue;
                
                // try secondary search
                prepareSecondarySearch(); // will be executed only once
                
                final ResultEntry resultEntry = obtainResultEntry(page, snippetMode);
                if (resultEntry == null) continue; // the entry had some problems, cannot be used
                urlRetrievalAllTime += resultEntry.dbRetrievalTime;
                snippetComputationAllTime += resultEntry.snippetComputationTime;
                //System.out.println("+++DEBUG-resultWorker+++ fetched " + resultEntry.urlstring());
                
                // place the result to the result vector
                if (!result.exists(resultEntry)) {
                    result.push(resultEntry, Long.valueOf(rankedCache.getOrder().cardinal(resultEntry.word())));
                    rankedCache.addReferences(resultEntry);
                }
                //System.out.println("DEBUG SNIPPET_LOADING: thread " + id + " got " + resultEntry.url());
            }
            Log.logInfo("SEARCH", "resultWorker thread " + id + " terminated");
        }
        
        public long busytime() {
        	return System.currentTimeMillis() - this.lastLifeSign;
        }
    }
    
    private void registerFailure(final String urlhash, final String reason) {
        this.failedURLs.put(urlhash, reason);
        Log.logInfo("search", "sorted out hash " + urlhash + " during search: " + reason);
    }
    
    /*
    public ResultEntry oneResult(final int item) {
    	return oneResult(item, System.currentTimeMillis() + 100);
    }
    
    public ResultEntry oneResult(final int item, long timeout) {
        // check if we already retrieved this item (happens if a search pages is accessed a second time)
        serverProfiling.update("SEARCH", new plasmaProfiling.searchEvent(query.id(true), "obtain one result entry - start", 0, 0));
        if (this.result.sizeStore() > item) {
            // we have the wanted result already in the result array .. return that
            return this.result.element(item).element;
        }
        
        if ((query.domType == plasmaSearchQuery.SEARCHDOM_GLOBALDHT) ||
            (query.domType == plasmaSearchQuery.SEARCHDOM_CLUSTERALL)) {
            // this is a search using remote search threads. Also the local search thread is started as background process
            while (
            	localSearchThread != null &&
            	localSearchThread.isAlive() &&
            	System.currentTimeMillis() < timeout) {
                // in case that the local search takes longer than some other remote search requests, 
                // do some sleeps to give the local process a chance to contribute
                try {Thread.sleep(10);} catch (final InterruptedException e) {}
            }
            // now wait until as many remote worker threads have finished, as we want to display results
            while (
            	this.primarySearchThreads != null &&
            	anyWorkerAlive() &&
            	countWorkerFinished() <= item &&
            	System.currentTimeMillis() < timeout &&
                (result.size() <= item || countFinishedRemoteSearch() <= item)) {
                try {Thread.sleep(10);} catch (final InterruptedException e) {}
            }
            
        }
        // finally wait until enough results are there produced from the snippet fetch process
        while (anyWorkerAlive() && result.size() <= item) {
            try {Thread.sleep(10);} catch (final InterruptedException e) {}
        }
        
        // finally, if there is something, return the result
        if (this.result.size() <= item) return null;
        return this.result.element(item).element;
    }
     */
    
    public ResultEntry oneResult(final int item) {
        // check if we already retrieved this item (happens if a search
        // pages is accessed a second time)
        serverProfiling.update("SEARCH", new plasmaProfiling.searchEvent(query.id(true), "obtain one result entry - start", 0, 0));
        if (this.result.sizeStore() > item) {
            // we have the wanted result already in the result array .. return that
            return this.result.element(item).element;
        }
        if ((query.domType == plasmaSearchQuery.SEARCHDOM_GLOBALDHT) ||
             (query.domType == plasmaSearchQuery.SEARCHDOM_CLUSTERALL)) {
            // this is a search using remote search threads. Also the local
            // search thread is started as background process
            if ((localSearchThread != null) && (localSearchThread.isAlive())) {
                // in case that the local search takes longer than some other
                // remote search requests, do some sleeps to give the local process
                // a chance to contribute
                try {Thread.sleep(item * 50L);} catch (final InterruptedException e) {}
            }
            // now wait until as many remote worker threads have finished, as we
            // want to display results
            while (this.primarySearchThreads != null &&
                   this.primarySearchThreads.length > item &&
                   anyWorkerAlive() &&
                   (result.size() <= item || countFinishedRemoteSearch() <= item)) {
                try {Thread.sleep(item * 50L);} catch (final InterruptedException e) {}
            }

        }
        // finally wait until enough results are there produced from the
        // snippet fetch process
        while ((anyWorkerAlive()) && (result.size() <= item)) {
            try {Thread.sleep(item * 50L);} catch (final InterruptedException e) {}
        }

        // finally, if there is something, return the result
        if (this.result.size() <= item) return null;
        return this.result.element(item).element;
    }
    
    private int resultCounter = 0;
    public ResultEntry nextResult() {
        final ResultEntry re = oneResult(resultCounter);
        resultCounter++;
        return re;
    }
    
    public plasmaSnippetCache.MediaSnippet oneImage(final int item) {
        // check if we already retrieved this item (happens if a search pages is accessed a second time)
        if (this.images.sizeStore() > item) {
            // we have the wanted result already in the result array .. return that
            return this.images.element(item).element;
        }
        
        // feed some results from the result stack into the image stack
        final int count = Math.min(5, Math.max(1, 10 * this.result.size() / (item + 1)));
        for (int i = 0; i < count; i++) {
            // generate result object
            final plasmaSearchEvent.ResultEntry result = nextResult();
            plasmaSnippetCache.MediaSnippet ms;
            if (result != null) {
                // iterate over all images in the result
                final ArrayList<plasmaSnippetCache.MediaSnippet> imagemedia = result.mediaSnippets();
                if (imagemedia != null) {
                    for (int j = 0; j < imagemedia.size(); j++) {
                        ms = imagemedia.get(j);
                        images.push(ms, Long.valueOf(ms.ranking));
                    }
                }
            }
        }
        
        // now take the specific item from the image stack
        if (this.images.size() <= item) return null;
        return this.images.element(item).element;
    }
    
    public ArrayList<SortStack<ResultEntry>.stackElement> completeResults(final long waitingtime) {
        final long timeout = System.currentTimeMillis() + waitingtime;
        while ((result.size() < query.neededResults()) && (anyWorkerAlive()) && (System.currentTimeMillis() < timeout)) {
            try {Thread.sleep(100);} catch (final InterruptedException e) {}
            //System.out.println("+++DEBUG-completeResults+++ sleeping " + 200);
        }
        return this.result.list(this.result.size());
    }
    
    boolean secondarySearchStartet = false;
    
    void prepareSecondarySearch() {
        if (secondarySearchStartet) return; // don't do this twice
        
        if ((rcAbstracts == null) || (rcAbstracts.size() != query.queryHashes.size())) return; // secondary search not possible (yet)
        this.secondarySearchStartet = true;
        
        /*        
        // catch up index abstracts and join them; then call peers again to submit their urls
        System.out.println("DEBUG-INDEXABSTRACT: " + rcAbstracts.size() + " word references catched, " + query.queryHashes.size() + " needed");

        Iterator i = rcAbstracts.entrySet().iterator();
        Map.Entry entry;
        while (i.hasNext()) {
            entry = (Map.Entry) i.next();
            System.out.println("DEBUG-INDEXABSTRACT: hash " + (String) entry.getKey() + ": " + ((query.queryHashes.contains((String) entry.getKey())) ? "NEEDED" : "NOT NEEDED") + "; " + ((TreeMap) entry.getValue()).size() + " entries");
        }
         */
        final TreeMap<String, String> abstractJoin = (rcAbstracts.size() == query.queryHashes.size()) ? SetTools.joinConstructive(rcAbstracts.values(), true) : new TreeMap<String, String>();
        if (abstractJoin.size() != 0) {
            //System.out.println("DEBUG-INDEXABSTRACT: index abstracts delivered " + abstractJoin.size() + " additional results for secondary search");
            // generate query for secondary search
            final TreeMap<String, String> secondarySearchURLs = new TreeMap<String, String>(); // a (peerhash:urlhash-liststring) mapping
            Iterator<Map.Entry<String, String>> i1 = abstractJoin.entrySet().iterator();
            Map.Entry<String, String> entry1;
            String url, urls, peer, peers;
            final String mypeerhash = wordIndex.seedDB.mySeed().hash;
            boolean mypeerinvolved = false;
            int mypeercount;
            while (i1.hasNext()) {
                entry1 = i1.next();
                url = entry1.getKey();
                peers = entry1.getValue();
                //System.out.println("DEBUG-INDEXABSTRACT: url " + url + ": from peers " + peers);
                mypeercount = 0;
                for (int j = 0; j < peers.length(); j = j + 12) {
                    peer = peers.substring(j, j + 12);
                    if ((peer.equals(mypeerhash)) && (mypeercount++ > 1)) continue;
                    //if (peers.indexOf(peer) < j) continue; // avoid doubles that may appear in the abstractJoin
                    urls = secondarySearchURLs.get(peer);
                    urls = (urls == null) ? url : urls + url;
                    secondarySearchURLs.put(peer, urls);
                }
                if (mypeercount == 1) mypeerinvolved = true;
            }
            
            // compute words for secondary search and start the secondary searches
            i1 = secondarySearchURLs.entrySet().iterator();
            String words;
            secondarySearchThreads = new yacySearch[(mypeerinvolved) ? secondarySearchURLs.size() - 1 : secondarySearchURLs.size()];
            int c = 0;
            while (i1.hasNext()) {
                entry1 = i1.next();
                peer = entry1.getKey();
                if (peer.equals(mypeerhash)) continue; // we dont need to ask ourself
                urls = entry1.getValue();
                words = wordsFromPeer(peer, urls);
                //System.out.println("DEBUG-INDEXABSTRACT ***: peer " + peer + "   has urls: " + urls);
                //System.out.println("DEBUG-INDEXABSTRACT ***: peer " + peer + " from words: " + words);
                secondarySearchThreads[c++] = yacySearch.secondaryRemoteSearch(
                        words, "", urls, wordIndex, crawlResults, this.rankedCache, peer, plasmaSwitchboard.urlBlacklist,
                        query.ranking, query.constraint, preselectedPeerHashes);

            }
        //} else {
            //System.out.println("DEBUG-INDEXABSTRACT: no success using index abstracts from remote peers");
        }
    }
    
    private String wordsFromPeer(final String peerhash, final String urls) {
        Map.Entry<String, TreeMap<String, String>> entry;
        String word, peerlist, url, wordlist = "";
        TreeMap<String, String> urlPeerlist;
        int p;
        boolean hasURL;
        synchronized (rcAbstracts) {
            final Iterator<Map.Entry <String, TreeMap<String, String>>> i = rcAbstracts.entrySet().iterator();
            while (i.hasNext()) {
                entry = i.next();
                word = entry.getKey();
                urlPeerlist = entry.getValue();
                hasURL = true;
                for (int j = 0; j < urls.length(); j = j + 12) {
                    url = urls.substring(j, j + 12);
                    peerlist = urlPeerlist.get(url);
                    p = (peerlist == null) ? -1 : peerlist.indexOf(peerhash);
                    if ((p < 0) || (p % 12 != 0)) {
                        hasURL = false;
                        break;
                    }
                }
                if (hasURL) wordlist += word;
            }
        }
        return wordlist;
    }
 
    public void remove(final String urlhash) {
        // removes the url hash reference from last search result
        /*indexRWIEntry e =*/ this.rankedCache.remove(urlhash);
        //assert e != null;
    }
    
    public Set<String> references(final int count) {
        // returns a set of words that are computed as toplist
        return this.rankedCache.getReferences(count);
    }
    
    public static class ResultEntry {
        // payload objects
        private final URLMetadata urlentry;
        private final URLMetadata.Components urlcomps; // buffer for components
        private String alternative_urlstring;
        private String alternative_urlname;
        private final plasmaSnippetCache.TextSnippet textSnippet;
        private final ArrayList<plasmaSnippetCache.MediaSnippet> mediaSnippets;
        
        // statistic objects
        public long dbRetrievalTime, snippetComputationTime;
        
        public ResultEntry(final URLMetadata urlentry, final plasmaWordIndex wordIndex,
                           final plasmaSnippetCache.TextSnippet textSnippet,
                           final ArrayList<plasmaSnippetCache.MediaSnippet> mediaSnippets,
                           final long dbRetrievalTime, final long snippetComputationTime) {
            this.urlentry = urlentry;
            this.urlcomps = urlentry.comp();
            this.alternative_urlstring = null;
            this.alternative_urlname = null;
            this.textSnippet = textSnippet;
            this.mediaSnippets = mediaSnippets;
            this.dbRetrievalTime = dbRetrievalTime;
            this.snippetComputationTime = snippetComputationTime;
            final String host = urlcomps.url().getHost();
            if (host.endsWith(".yacyh")) {
                // translate host into current IP
                int p = host.indexOf(".");
                final String hash = yacySeed.hexHash2b64Hash(host.substring(p + 1, host.length() - 6));
                final yacySeed seed = wordIndex.seedDB.getConnected(hash);
                final String filename = urlcomps.url().getFile();
                String address = null;
                if ((seed == null) || ((address = seed.getPublicAddress()) == null)) {
                    // seed is not known from here
                    wordIndex.removeWordReferences(
                        plasmaCondenser.getWords(
                            ("yacyshare " +
                             filename.replace('?', ' ') +
                             " " +
                             urlcomps.dc_title())).keySet(),
                             urlentry.hash());
                    wordIndex.removeURL(urlentry.hash()); // clean up
                    throw new RuntimeException("index void");
                }
                alternative_urlstring = "http://" + address + "/" + host.substring(0, p) + filename;
                alternative_urlname = "http://share." + seed.getName() + ".yacy" + filename;
                if ((p = alternative_urlname.indexOf("?")) > 0) alternative_urlname = alternative_urlname.substring(0, p);
            }
        }
        public int hashCode() {
            return urlentry.hash().hashCode();
        }
        public String hash() {
            return urlentry.hash();
        }
        public yacyURL url() {
            return urlcomps.url();
        }
        public Bitfield flags() {
            return urlentry.flags();
        }
        public String urlstring() {
            return (alternative_urlstring == null) ? urlcomps.url().toNormalform(false, true) : alternative_urlstring;
        }
        public String urlname() {
            return (alternative_urlname == null) ? yacyURL.unescape(urlcomps.url().toNormalform(false, true)) : alternative_urlname;
        }
        public String title() {
            return urlcomps.dc_title();
        }
        public plasmaSnippetCache.TextSnippet textSnippet() {
            return this.textSnippet;
        }
        public ArrayList<plasmaSnippetCache.MediaSnippet> mediaSnippets() {
            return this.mediaSnippets;
        }
        public Date modified() {
            return urlentry.moddate();
        }
        public int filesize() {
            return urlentry.size();
        }
        public int limage() {
            return urlentry.limage();
        }
        public int laudio() {
            return urlentry.laudio();
        }
        public int lvideo() {
            return urlentry.lvideo();
        }
        public int lapp() {
            return urlentry.lapp();
        }
        public ReferenceVars word() {
            final Reference word = urlentry.word();
            assert word instanceof ReferenceVars;
            return (ReferenceVars) word;
        }
        public boolean hasTextSnippet() {
            return (this.textSnippet != null) && (this.textSnippet.getErrorCode() < 11);
        }
        public boolean hasMediaSnippets() {
            return (this.mediaSnippets != null) && (this.mediaSnippets.size() > 0);
        }
        public String resource() {
            // generate transport resource
            if ((textSnippet == null) || (!textSnippet.exists())) {
                return urlentry.toString();
            }
            return urlentry.toString(textSnippet.getLineRaw());
        }
    }
}
