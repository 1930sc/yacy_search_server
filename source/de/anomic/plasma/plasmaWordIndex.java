// plasmaWordIndex.java
// (C) 2005, 2006 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 2005 on http://www.anomic.de
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import de.anomic.crawler.CrawlProfile;
import de.anomic.crawler.IndexingStack;
import de.anomic.htmlFilter.htmlFilterContentScraper;
import de.anomic.http.httpdProxyCacheEntry;
import de.anomic.index.indexCollectionRI;
import de.anomic.index.indexContainer;
import de.anomic.index.indexContainerOrder;
import de.anomic.index.indexRAMRI;
import de.anomic.index.indexRI;
import de.anomic.index.indexRWIEntry;
import de.anomic.index.indexRWIRowEntry;
import de.anomic.index.indexReferenceBlacklist;
import de.anomic.index.indexRepositoryReference;
import de.anomic.index.indexURLReference;
import de.anomic.index.indexWord;
import de.anomic.index.indexRepositoryReference.Export;
import de.anomic.kelondro.index.RowCollection;
import de.anomic.kelondro.order.Base64Order;
import de.anomic.kelondro.order.ByteOrder;
import de.anomic.kelondro.order.CloneableIterator;
import de.anomic.kelondro.order.MergeIterator;
import de.anomic.kelondro.order.Order;
import de.anomic.kelondro.order.RotateIterator;
import de.anomic.kelondro.util.MemoryControl;
import de.anomic.kelondro.util.kelondroException;
import de.anomic.server.serverProfiling;
import de.anomic.server.logging.serverLog;
import de.anomic.tools.iso639;
import de.anomic.xml.RSSFeed;
import de.anomic.xml.RSSMessage;
import de.anomic.yacy.yacyNewsPool;
import de.anomic.yacy.yacyPeerActions;
import de.anomic.yacy.yacySeedDB;
import de.anomic.yacy.yacyURL;

public final class plasmaWordIndex implements indexRI {

    // environment constants
    public  static final long wCacheMaxAge    = 1000 * 60 * 30; // milliseconds; 30 minutes
    public  static final int  wCacheMaxChunk  =  800;           // maximum number of references for each urlhash
    public  static final int  lowcachedivisor =  900;
    public  static final int  maxCollectionPartition = 7;       // should be 7
    

    public static final String CRAWL_PROFILE_PROXY                 = "proxy";
    public static final String CRAWL_PROFILE_REMOTE                = "remote";
    public static final String CRAWL_PROFILE_SNIPPET_LOCAL_TEXT    = "snippetLocalText";
    public static final String CRAWL_PROFILE_SNIPPET_GLOBAL_TEXT   = "snippetGlobalText";
    public static final String CRAWL_PROFILE_SNIPPET_LOCAL_MEDIA   = "snippetLocalMedia";
    public static final String CRAWL_PROFILE_SNIPPET_GLOBAL_MEDIA  = "snippetGlobalMedia";
    public static final String DBFILE_ACTIVE_CRAWL_PROFILES        = "crawlProfilesActive.heap";
    public static final String DBFILE_PASSIVE_CRAWL_PROFILES       = "crawlProfilesPassive.heap";
    
    public static final long CRAWL_PROFILE_PROXY_RECRAWL_CYCLE = 60L * 24L;
    public static final long CRAWL_PROFILE_SNIPPET_LOCAL_TEXT_RECRAWL_CYCLE = 60L * 24L * 30L;
    public static final long CRAWL_PROFILE_SNIPPET_GLOBAL_TEXT_RECRAWL_CYCLE = 60L * 24L * 30L;
    public static final long CRAWL_PROFILE_SNIPPET_LOCAL_MEDIA_RECRAWL_CYCLE = 60L * 24L * 30L;
    public static final long CRAWL_PROFILE_SNIPPET_GLOBAL_MEDIA_RECRAWL_CYCLE = 60L * 24L * 30L;
    
    
    private final ByteOrder        indexOrder = Base64Order.enhancedCoder;
    private final indexRAMRI               dhtCache;
    private final indexCollectionRI        collections;          // new database structure to replace AssortmentCluster and FileCluster
    private final serverLog                log;
    private indexRepositoryReference       referenceURL;
    public  final yacySeedDB               seedDB;
    public        yacyNewsPool             newsPool;
    private final File                     primaryRoot, secondaryRoot;
    public        IndexingStack            queuePreStack;
    public        CrawlProfile             profilesActiveCrawls, profilesPassiveCrawls;
    public  CrawlProfile.entry             defaultProxyProfile;
    public  CrawlProfile.entry             defaultRemoteProfile;
    public  CrawlProfile.entry             defaultTextSnippetLocalProfile, defaultTextSnippetGlobalProfile;
    public  CrawlProfile.entry             defaultMediaSnippetLocalProfile, defaultMediaSnippetGlobalProfile;
    private final File                     queuesRoot;
    public  yacyPeerActions                peerActions;

    public plasmaWordIndex(
            final String networkName,
            final serverLog log,
            final File indexPrimaryRoot,
            final File indexSecondaryRoot,
            final int entityCacheMaxSize,
            final boolean useCommons, 
            final int redundancy,
            final int partitionExponent) {
        if (networkName == null || networkName.length() == 0) {
            log.logSevere("no network name given - shutting down");
            System.exit(0);
        }
        this.log = log;
        this.primaryRoot = new File(indexPrimaryRoot, networkName);
        this.secondaryRoot = new File(indexSecondaryRoot, networkName);
        File indexPrimaryTextLocation = new File(this.primaryRoot, "TEXT");
        if (!indexPrimaryTextLocation.exists()) {
            // patch old index locations; the secondary path is patched in plasmaCrawlLURL
            final File oldPrimaryPath = new File(new File(indexPrimaryRoot, "PUBLIC"), "TEXT");
            final File oldPrimaryTextLocation = new File(new File(indexPrimaryRoot, "PUBLIC"), "TEXT");
            if (oldPrimaryPath.exists() && oldPrimaryTextLocation.exists()) {
                // move the text folder from the old location to the new location
                assert !indexPrimaryTextLocation.exists();
                indexPrimaryTextLocation.mkdirs();
                if (oldPrimaryTextLocation.renameTo(indexPrimaryTextLocation)) {
                    if (!oldPrimaryPath.delete()) oldPrimaryPath.deleteOnExit();
                } else {
                    indexPrimaryTextLocation = oldPrimaryTextLocation; // emergency case: stay with old directory
                }
            }
        }

        final File textindexcache = new File(indexPrimaryTextLocation, "RICACHE");
        if (!(textindexcache.exists())) textindexcache.mkdirs();
        if (new File(textindexcache, "index.dhtin.blob").exists()) {
        	// migration of the both caches into one
        	this.dhtCache = new indexRAMRI(textindexcache, indexRWIRowEntry.urlEntryRow, entityCacheMaxSize, wCacheMaxChunk, wCacheMaxAge, "index.dhtout.blob", log);
            indexRAMRI dhtInCache  = new indexRAMRI(textindexcache, indexRWIRowEntry.urlEntryRow, entityCacheMaxSize, wCacheMaxChunk, wCacheMaxAge, "index.dhtin.blob", log);
            for (indexContainer c: dhtInCache) {
        		this.dhtCache.addEntries(c);
            }
            new File(textindexcache, "index.dhtin.blob").delete();
        } else {
        	// read in new BLOB
        	this.dhtCache = new indexRAMRI(textindexcache, indexRWIRowEntry.urlEntryRow, entityCacheMaxSize, wCacheMaxChunk, wCacheMaxAge, "index.dhtout.blob", log);            
        }
        
        // create collections storage path
        final File textindexcollections = new File(indexPrimaryTextLocation, "RICOLLECTION");
        if (!(textindexcollections.exists())) textindexcollections.mkdirs();
        this.collections = new indexCollectionRI(textindexcollections, "collection", maxCollectionPartition, indexRWIRowEntry.urlEntryRow, useCommons);

        // create LURL-db
        referenceURL = new indexRepositoryReference(this.secondaryRoot);
        
        // make crawl profiles database and default profiles
        this.queuesRoot = new File(this.primaryRoot, "QUEUES");
        this.queuesRoot.mkdirs();
        this.log.logConfig("Initializing Crawl Profiles");
        final File profilesActiveFile = new File(queuesRoot, DBFILE_ACTIVE_CRAWL_PROFILES);
        if (!profilesActiveFile.exists()) {
            // migrate old file
            final File oldFile = new File(new File(queuesRoot.getParentFile().getParentFile().getParentFile(), "PLASMADB"), "crawlProfilesActive1.db");
            if (oldFile.exists()) oldFile.renameTo(profilesActiveFile);
        }
        try {
            this.profilesActiveCrawls = new CrawlProfile(profilesActiveFile);
        } catch (IOException e) {
            profilesActiveFile.delete();
            try {
                this.profilesActiveCrawls = new CrawlProfile(profilesActiveFile);
            } catch (IOException e1) {
                e1.printStackTrace();
                this.profilesActiveCrawls = null;
            }
        }
        initActiveCrawlProfiles();
        log.logConfig("Loaded active crawl profiles from file " + profilesActiveFile.getName() +
                ", " + this.profilesActiveCrawls.size() + " entries" +
                ", " + profilesActiveFile.length()/1024);
        final File profilesPassiveFile = new File(queuesRoot, DBFILE_PASSIVE_CRAWL_PROFILES);
        if (!profilesPassiveFile.exists()) {
            // migrate old file
            final File oldFile = new File(new File(queuesRoot.getParentFile().getParentFile().getParentFile(), "PLASMADB"), "crawlProfilesPassive1.db");
            if (oldFile.exists()) oldFile.renameTo(profilesPassiveFile);
        }
        try {
            this.profilesPassiveCrawls = new CrawlProfile(profilesPassiveFile);
        } catch (IOException e) {
            profilesPassiveFile.delete();
            try {
                this.profilesPassiveCrawls = new CrawlProfile(profilesPassiveFile);
            } catch (IOException e1) {
                e1.printStackTrace();
                this.profilesPassiveCrawls = null;
            }
        }
        log.logConfig("Loaded passive crawl profiles from file " + profilesPassiveFile.getName() +
                ", " + this.profilesPassiveCrawls.size() + " entries" +
                ", " + profilesPassiveFile.length()/1024);
        
        // init queues
        final File preStackFile = new File(queuesRoot, "urlNoticePreStack");
        if (!preStackFile.exists()) {
            // migrate old file
            final File oldFile = new File(new File(queuesRoot.getParentFile().getParentFile().getParentFile(), "PLASMADB"), "switchboardQueue.stack");
            if (oldFile.exists()) oldFile.renameTo(preStackFile);
        }
        this.queuePreStack = new IndexingStack(this, preStackFile, this.profilesActiveCrawls);
        
        // create or init seed cache
        final File networkRoot = new File(this.primaryRoot, "NETWORK");
        networkRoot.mkdirs();
        final File mySeedFile = new File(networkRoot, yacySeedDB.DBFILE_OWN_SEED);
        final File oldSeedFile = new File(new File(indexPrimaryRoot.getParentFile(), "YACYDB"), "mySeed.txt");
        if (oldSeedFile.exists()) oldSeedFile.renameTo(mySeedFile);
        seedDB = new yacySeedDB(
                new File(networkRoot, "seed.new.heap"),
                new File(networkRoot, "seed.old.heap"),
                new File(networkRoot, "seed.pot.heap"),
                mySeedFile,
                redundancy,
                partitionExponent
                );
        
        // create or init news database
        newsPool = new yacyNewsPool(networkRoot);
        
        // deploy peer actions
        this.peerActions = new yacyPeerActions(seedDB, newsPool);
    }
    
    public void clearCache() {
        referenceURL.clearCache();
    }

    public void clear() {
        dhtCache.clear();
        collections.clear();
        try {
            referenceURL.clear();
        } catch (final IOException e) {
            e.printStackTrace();
        }
        queuePreStack.clear();
    }
    
    private void initActiveCrawlProfiles() {
        this.defaultProxyProfile = null;
        this.defaultRemoteProfile = null;
        this.defaultTextSnippetLocalProfile = null;
        this.defaultTextSnippetGlobalProfile = null;
        this.defaultMediaSnippetLocalProfile = null;
        this.defaultMediaSnippetGlobalProfile = null;
        final Iterator<CrawlProfile.entry> i = this.profilesActiveCrawls.profiles(true);
        CrawlProfile.entry profile;
        String name;
        try {
            while (i.hasNext()) {
                profile = i.next();
                name = profile.name();
                if (name.equals(CRAWL_PROFILE_PROXY)) this.defaultProxyProfile = profile;
                if (name.equals(CRAWL_PROFILE_REMOTE)) this.defaultRemoteProfile = profile;
                if (name.equals(CRAWL_PROFILE_SNIPPET_LOCAL_TEXT)) this.defaultTextSnippetLocalProfile = profile;
                if (name.equals(CRAWL_PROFILE_SNIPPET_GLOBAL_TEXT)) this.defaultTextSnippetGlobalProfile = profile;
                if (name.equals(CRAWL_PROFILE_SNIPPET_LOCAL_MEDIA)) this.defaultMediaSnippetLocalProfile = profile;
                if (name.equals(CRAWL_PROFILE_SNIPPET_GLOBAL_MEDIA)) this.defaultMediaSnippetGlobalProfile = profile;
            }
        } catch (final Exception e) {
            this.profilesActiveCrawls.clear();
            this.defaultProxyProfile = null;
            this.defaultRemoteProfile = null;
            this.defaultTextSnippetLocalProfile = null;
            this.defaultTextSnippetGlobalProfile = null;
            this.defaultMediaSnippetLocalProfile = null;
            this.defaultMediaSnippetGlobalProfile = null;
        }
        
        if (this.defaultProxyProfile == null) {
            // generate new default entry for proxy crawling
            this.defaultProxyProfile = this.profilesActiveCrawls.newEntry("proxy", null, CrawlProfile.KEYWORDS_PROXY, CrawlProfile.MATCH_ALL, CrawlProfile.MATCH_NEVER,
                    0 /*Integer.parseInt(getConfig(PROXY_PREFETCH_DEPTH, "0"))*/,
                    this.profilesActiveCrawls.getRecrawlDate(CRAWL_PROFILE_PROXY_RECRAWL_CYCLE), -1, -1, false,
                    true /*getConfigBool(PROXY_INDEXING_LOCAL_TEXT, true)*/,
                    true /*getConfigBool(PROXY_INDEXING_LOCAL_MEDIA, true)*/,
                    true, true,
                    false /*getConfigBool(PROXY_INDEXING_REMOTE, false)*/, true, true, true);
        }
        if (this.defaultRemoteProfile == null) {
            // generate new default entry for remote crawling
            defaultRemoteProfile = this.profilesActiveCrawls.newEntry(CRAWL_PROFILE_REMOTE, null, CrawlProfile.KEYWORDS_REMOTE, CrawlProfile.MATCH_ALL, CrawlProfile.MATCH_NEVER, 0,
                    -1, -1, -1, true, true, true, false, true, false, true, true, false);
        }
        if (this.defaultTextSnippetLocalProfile == null) {
            // generate new default entry for snippet fetch and optional crawling
            defaultTextSnippetLocalProfile = this.profilesActiveCrawls.newEntry(CRAWL_PROFILE_SNIPPET_LOCAL_TEXT, null, CrawlProfile.KEYWORDS_SNIPPET, CrawlProfile.MATCH_ALL, CrawlProfile.MATCH_NEVER, 0,
            		this.profilesActiveCrawls.getRecrawlDate(CRAWL_PROFILE_SNIPPET_LOCAL_TEXT_RECRAWL_CYCLE), -1, -1, true, false, false, false, false, false, true, true, false);
        }
        if (this.defaultTextSnippetGlobalProfile == null) {
            // generate new default entry for snippet fetch and optional crawling
            defaultTextSnippetGlobalProfile = this.profilesActiveCrawls.newEntry(CRAWL_PROFILE_SNIPPET_GLOBAL_TEXT, null, CrawlProfile.KEYWORDS_SNIPPET, CrawlProfile.MATCH_ALL, CrawlProfile.MATCH_NEVER, 0,
            		this.profilesActiveCrawls.getRecrawlDate(CRAWL_PROFILE_SNIPPET_GLOBAL_TEXT_RECRAWL_CYCLE), -1, -1, true, true, true, true, true, false, true, true, false);
        }
        if (this.defaultMediaSnippetLocalProfile == null) {
            // generate new default entry for snippet fetch and optional crawling
            defaultMediaSnippetLocalProfile = this.profilesActiveCrawls.newEntry(CRAWL_PROFILE_SNIPPET_LOCAL_MEDIA, null, CrawlProfile.KEYWORDS_SNIPPET, CrawlProfile.MATCH_ALL, CrawlProfile.MATCH_NEVER, 0,
            		this.profilesActiveCrawls.getRecrawlDate(CRAWL_PROFILE_SNIPPET_LOCAL_MEDIA_RECRAWL_CYCLE), -1, -1, true, false, false, false, false, false, true, true, false);
        }
        if (this.defaultMediaSnippetGlobalProfile == null) {
            // generate new default entry for snippet fetch and optional crawling
            defaultMediaSnippetGlobalProfile = this.profilesActiveCrawls.newEntry(CRAWL_PROFILE_SNIPPET_GLOBAL_MEDIA, null, CrawlProfile.KEYWORDS_SNIPPET, CrawlProfile.MATCH_ALL, CrawlProfile.MATCH_NEVER, 0,
            		this.profilesActiveCrawls.getRecrawlDate(CRAWL_PROFILE_SNIPPET_GLOBAL_MEDIA_RECRAWL_CYCLE), -1, -1, true, false, true, true, true, false, true, true, false);
        }
    }
    
    private void resetProfiles() {
        final File pdb = new File(this.queuesRoot, DBFILE_ACTIVE_CRAWL_PROFILES);
        if (pdb.exists()) pdb.delete();
        try {
            profilesActiveCrawls = new CrawlProfile(pdb);
        } catch (IOException e) {
            e.printStackTrace();
        }
        initActiveCrawlProfiles();
    }
    
    
    public boolean cleanProfiles() throws InterruptedException {
        if (queuePreStack.size() > 0) return false;
        final Iterator<CrawlProfile.entry> iter = profilesActiveCrawls.profiles(true);
        CrawlProfile.entry entry;
        boolean hasDoneSomething = false;
        try {
            while (iter.hasNext()) {
                // check for interruption
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Shutdown in progress");
                
                // getting next profile
                entry = iter.next();
                if (!((entry.name().equals(CRAWL_PROFILE_PROXY))  ||
                      (entry.name().equals(CRAWL_PROFILE_REMOTE)) ||
                      (entry.name().equals(CRAWL_PROFILE_SNIPPET_LOCAL_TEXT))  ||
                      (entry.name().equals(CRAWL_PROFILE_SNIPPET_GLOBAL_TEXT)) ||
                      (entry.name().equals(CRAWL_PROFILE_SNIPPET_LOCAL_MEDIA)) ||
                      (entry.name().equals(CRAWL_PROFILE_SNIPPET_GLOBAL_MEDIA)))) {
                    profilesPassiveCrawls.newEntry(entry.map());
                    iter.remove();
                    hasDoneSomething = true;
                }
            }
        } catch (final kelondroException e) {
            resetProfiles();
            hasDoneSomething = true;
        }
        return hasDoneSomething;
    }
    
    public File getLocation(final boolean primary) {
        return (primary) ? this.primaryRoot : this.secondaryRoot;
    }

    public void putURL(final indexURLReference entry) throws IOException {
        this.referenceURL.store(entry);
    }
    
    public indexURLReference getURL(final String urlHash, final indexRWIEntry searchedWord, final long ranking) {
        return this.referenceURL.load(urlHash, searchedWord, ranking);
    }
    
    public boolean removeURL(final String urlHash) {
        return this.referenceURL.remove(urlHash);
    }
        
    public boolean existsURL(final String urlHash) {
        return this.referenceURL.exists(urlHash);
    }
    
    public int countURL() {
        return this.referenceURL.size();
    }
    
    public Export exportURL(final File f, final String filter, final int format, final boolean dom) {
        return this.referenceURL.export(f, filter, format, dom);
    }
    
    public Export exportURL() {
        return this.referenceURL.export();
    }
    
    public CloneableIterator<indexURLReference> entriesURL(final boolean up, final String firstHash) throws IOException {
        return this.referenceURL.entries(up, firstHash);
    }
    
    public Iterator<indexRepositoryReference.hostStat> statistics(int count) throws IOException {
        return this.referenceURL.statistics(count);
    }
    
    public int deleteDomain(String urlfragment) throws IOException {
        return this.referenceURL.deleteDomain(urlfragment);
    }
    
    public indexRepositoryReference.BlacklistCleaner getURLCleaner(final indexReferenceBlacklist blacklist) {
        return this.referenceURL.getBlacklistCleaner(blacklist); // thread is not already started after this is called!
    }
    
    public int getURLwriteCacheSize() {
        return this.referenceURL.writeCacheSize();
    }
    
    public int minMem() {
        return 1024*1024 /* indexing overhead */ + dhtCache.minMem() + collections.minMem();
    }

    public int maxURLinDHTCache() {
        return dhtCache.maxURLinCache();
    }

    public long minAgeOfDHTCache() {
        return dhtCache.minAgeOfCache();
    }

    public long maxAgeOfDHTCache() {
        return dhtCache.maxAgeOfCache();
    }

    public int dhtCacheSize() {
        return dhtCache.size();
    }
    
    public long dhtCacheSizeBytes() {
        // calculate the real size in bytes of DHT-In/Out-Cache
        long cacheBytes = 0;
        final long entryBytes = indexRWIRowEntry.urlEntryRow.objectsize;
        final indexRAMRI cache = (dhtCache);
        synchronized (cache) {
            final Iterator<indexContainer> it = cache.wordContainers(null, false);
            while (it.hasNext()) cacheBytes += it.next().size() * entryBytes;
        }
        return cacheBytes;
    }

    public void setMaxWordCount(final int maxWords) {
        dhtCache.setMaxWordCount(maxWords);
    }

    public void dhtFlushControl(final indexRAMRI theCache) {
        // check for forced flush
        int cs = cacheSize();
        if (cs > 0) {
            // flush elements that are too big. This flushing depends on the fact that the flush rule
            // selects the biggest elements first for flushing. If it does not for any reason, the following
            // loop would not terminate.
            serverProfiling.update("wordcache", Long.valueOf(cs));
            // To ensure termination an additional counter is used
            int l = 0;
            while (theCache.size() > 0 && (l++ < 100) && (theCache.maxURLinCache() > wCacheMaxChunk)) {
                flushCacheOne(theCache);
            }
            // next flush more entries if the size exceeds the maximum size of the cache
            while (theCache.size() > 0 &&
            		((theCache.size() > theCache.getMaxWordCount()) ||
                    (MemoryControl.available() < collections.minMem()))) {
                flushCacheOne(theCache);
            }
            if (cacheSize() != cs) serverProfiling.update("wordcache", Long.valueOf(cacheSize()));
        }
    }
    
    public static indexContainer emptyContainer(final String wordHash, final int elementCount) {
    	return new indexContainer(wordHash, indexRWIRowEntry.urlEntryRow, elementCount);
    }

    public void addEntry(final String wordHash, final indexRWIRowEntry entry, final long updateTime) {
        // add the entry
        dhtCache.addEntry(wordHash, entry, updateTime, true);
        dhtFlushControl(this.dhtCache);
    }
    
    public void addEntries(final indexContainer entries) {
        assert (entries.row().objectsize == indexRWIRowEntry.urlEntryRow.objectsize);
 
        // add the entry
        dhtCache.addEntries(entries);
        dhtFlushControl(this.dhtCache);
    }
    
    public void flushCacheFor(int time) {
    	flushCacheUntil(System.currentTimeMillis() + time);
    }
    
    private synchronized void flushCacheUntil(long timeout) {
    	while (System.currentTimeMillis() < timeout && dhtCache.size() > 0) {
    		flushCacheOne(dhtCache);
    	}
    }
    
    private synchronized void flushCacheOne(final indexRAMRI ram) {
    	if (ram.size() > 0) collections.addEntries(flushContainer(ram));
    }
    
    private indexContainer flushContainer(final indexRAMRI ram) {
        String wordHash;
        indexContainer c;
        wordHash = ram.maxScoreWordHash();
        c = ram.getContainer(wordHash, null);
        if ((c != null) && (c.size() > wCacheMaxChunk)) {
            return ram.deleteContainer(wordHash);
        } else {
            return ram.deleteContainer(ram.bestFlushWordHash());
        }
    }
    
    
    /**
     * this is called by the switchboard to put in a new page into the index
     * use all the words in one condenser object to simultanous create index entries
     * 
     * @param url
     * @param urlModified
     * @param document
     * @param condenser
     * @param language
     * @param doctype
     * @param outlinksSame
     * @param outlinksOther
     * @return
     */
    public int addPageIndex(final yacyURL url, final Date urlModified, final plasmaParserDocument document, final plasmaCondenser condenser, final String language, final char doctype, final int outlinksSame, final int outlinksOther) {
        int wordCount = 0;
        final int urlLength = url.toNormalform(true, true).length();
        final int urlComps = htmlFilterContentScraper.urlComps(url.toString()).length;
        
        // iterate over all words of context text
        final Iterator<Map.Entry<String, indexWord>> i = condenser.words().entrySet().iterator();
        Map.Entry<String, indexWord> wentry;
        String word;
        indexRWIRowEntry ientry;
        indexWord wprop;
        while (i.hasNext()) {
            wentry = i.next();
            word = wentry.getKey();
            wprop = wentry.getValue();
            assert (wprop.flags != null);
            ientry = new indexRWIRowEntry(url.hash(),
                        urlLength, urlComps, (document == null) ? urlLength : document.dc_title().length(),
                        wprop.count,
                        condenser.RESULT_NUMB_WORDS,
                        condenser.RESULT_NUMB_SENTENCES,
                        wprop.posInText,
                        wprop.posInPhrase,
                        wprop.numOfPhrase,
                        urlModified.getTime(),
                        System.currentTimeMillis(),
                        language,
                        doctype,
                        outlinksSame, outlinksOther,
                        wprop.flags);
            addEntry(indexWord.word2hash(word), ientry, System.currentTimeMillis());
            wordCount++;
        }
        
        return wordCount;
    }

    public boolean hasContainer(final String wordHash) {
        if (dhtCache.hasContainer(wordHash)) return true;
        if (collections.hasContainer(wordHash)) return true;
        return false;
    }
    
    public indexContainer getContainer(final String wordHash, final Set<String> urlselection) {
        if ((wordHash == null) || (wordHash.length() != yacySeedDB.commonHashLength)) {
            // wrong input
            return null;
        }
        
        // get from cache
        indexContainer container;
        container = dhtCache.getContainer(wordHash, urlselection);
        
        // get from collection index
        if (container == null) {
            container = collections.getContainer(wordHash, urlselection);
        } else {
            container.addAllUnique(collections.getContainer(wordHash, urlselection));
        }
        
        if (container == null) return null;
        
        // check doubles
        final int beforeDouble = container.size();
        final ArrayList<RowCollection> d = container.removeDoubles();
        RowCollection set;
        for (int i = 0; i < d.size(); i++) {
            // for each element in the double-set, take that one that is the most recent one
            set = d.get(i);
            indexRWIRowEntry e, elm = null;
            long lm = 0;
            for (int j = 0; j < set.size(); j++) {
                e = new indexRWIRowEntry(set.get(j, true));
                if ((elm == null) || (e.lastModified() > lm)) {
                    elm = e;
                    lm = e.lastModified();
                }
            }
            if(elm != null) {
                container.addUnique(elm.toKelondroEntry());
            }
        }
        if (container.size() < beforeDouble) System.out.println("*** DEBUG DOUBLECHECK - removed " + (beforeDouble - container.size()) + " index entries from word container " + container.getWordHash());

        return container;
    }

    /**
     * return map of wordhash:indexContainer
     * 
     * @param wordHashes
     * @param urlselection
     * @param deleteIfEmpty
     * @param interruptIfEmpty
     * @return
     */
    public HashMap<String, indexContainer> getContainers(final Set<String> wordHashes, final Set<String> urlselection, final boolean interruptIfEmpty) {
        // retrieve entities that belong to the hashes
        final HashMap<String, indexContainer> containers = new HashMap<String, indexContainer>(wordHashes.size());
        String singleHash;
        indexContainer singleContainer;
            final Iterator<String> i = wordHashes.iterator();
            while (i.hasNext()) {
            
                // get next word hash:
                singleHash = i.next();
            
                // retrieve index
                singleContainer = getContainer(singleHash, urlselection);
            
                // check result
                if (((singleContainer == null) || (singleContainer.size() == 0)) && (interruptIfEmpty)) return new HashMap<String, indexContainer>(0);
            
                containers.put(singleHash, singleContainer);
            }
        return containers;
    }

    @SuppressWarnings("unchecked")
    public HashMap<String, indexContainer>[] localSearchContainers(final plasmaSearchQuery query, final Set<String> urlselection) {
        // search for the set of hashes and return a map of of wordhash:indexContainer containing the seach result

        // retrieve entities that belong to the hashes
        HashMap<String, indexContainer> inclusionContainers = (query.queryHashes.size() == 0) ? new HashMap<String, indexContainer>(0) : getContainers(
                        query.queryHashes,
                        urlselection,
                        true);
        if ((inclusionContainers.size() != 0) && (inclusionContainers.size() < query.queryHashes.size())) inclusionContainers = new HashMap<String, indexContainer>(0); // prevent that only a subset is returned
        final HashMap<String, indexContainer> exclusionContainers = (inclusionContainers.size() == 0) ? new HashMap<String, indexContainer>(0) : getContainers(
                query.excludeHashes,
                urlselection,
                true);
        return new HashMap[]{inclusionContainers, exclusionContainers};
    }
    
    public int size() {
        return java.lang.Math.max(collections.size(), dhtCache.size());
    }

    public int collectionsSize() {
        return collections.size();
    }
    
    public int cacheSize() {
        return dhtCache.size();
    }

    public void close() {
        dhtCache.close();
        collections.close();
        referenceURL.close();
        seedDB.close();
        newsPool.close();
        profilesActiveCrawls.close();
        queuePreStack.close();
        peerActions.close();
    }
    
    public indexContainer deleteContainer(final String wordHash) {
        final indexContainer c = new indexContainer(
                wordHash,
                indexRWIRowEntry.urlEntryRow,
                dhtCache.sizeContainer(wordHash));
        c.addAllUnique(dhtCache.deleteContainer(wordHash));
        c.addAllUnique(collections.deleteContainer(wordHash));
        return c;
    }
    
    public boolean removeEntry(final String wordHash, final String urlHash) {
        boolean removed = false;
        removed = removed | (dhtCache.removeEntry(wordHash, urlHash));
        removed = removed | (collections.removeEntry(wordHash, urlHash));
        return removed;
    }
    
    public int removeEntryMultiple(final Set<String> wordHashes, final String urlHash) {
        // remove the same url hashes for multiple words
        // this is mainly used when correcting a index after a search
        final Iterator<String> i = wordHashes.iterator();
        int count = 0;
        while (i.hasNext()) {
            if (removeEntry(i.next(), urlHash)) count++;
        }
        return count;
    }
    
    public int removeEntries(final String wordHash, final Set<String> urlHashes) {
        int removed = 0;
        removed += dhtCache.removeEntries(wordHash, urlHashes);
        removed += collections.removeEntries(wordHash, urlHashes);
        return removed;
    }
    
    public String removeEntriesExpl(final String wordHash, final Set<String> urlHashes) {
        String removed = "";
        removed += dhtCache.removeEntries(wordHash, urlHashes) + ", ";
        removed += collections.removeEntries(wordHash, urlHashes);
        return removed;
    }
    
    public void removeEntriesMultiple(final Set<String> wordHashes, final Set<String> urlHashes) {
        // remove the same url hashes for multiple words
        // this is mainly used when correcting a index after a search
        final Iterator<String> i = wordHashes.iterator();
        while (i.hasNext()) {
            removeEntries(i.next(), urlHashes);
        }
    }
    
    public int removeWordReferences(final Set<String> words, final String urlhash) {
        // sequentially delete all word references
        // returns number of deletions
        final Iterator<String> iter = words.iterator();
        int count = 0;
        while (iter.hasNext()) {
            // delete the URL reference in this word index
            if (removeEntry(indexWord.word2hash(iter.next()), urlhash)) count++;
        }
        return count;
    }
    
    public synchronized TreeSet<indexContainer> indexContainerSet(final String startHash, final boolean ram, final boolean rot, int count) {
        // creates a set of indexContainers
        // this does not use the dhtInCache
        final Order<indexContainer> containerOrder = new indexContainerOrder(indexOrder.clone());
        containerOrder.rotate(emptyContainer(startHash, 0));
        final TreeSet<indexContainer> containers = new TreeSet<indexContainer>(containerOrder);
        final Iterator<indexContainer> i = wordContainers(startHash, ram, rot);
        if (ram) count = Math.min(dhtCache.size(), count);
        indexContainer container;
        // this loop does not terminate using the i.hasNex() predicate when rot == true
        // because then the underlying iterator is a rotating iterator without termination
        // in this case a termination must be ensured with a counter
        // It must also be ensured that the counter is in/decreased every loop
        while ((count > 0) && (i.hasNext())) {
            container = i.next();
            if ((container != null) && (container.size() > 0)) {
                containers.add(container);
            }
            count--; // decrease counter even if the container was null or empty to ensure termination
        }
        return containers; // this may return less containers as demanded
    }

    public indexURLReference storeDocument(final IndexingStack.QueueEntry entry, final plasmaParserDocument document, final plasmaCondenser condenser) throws IOException {
        final long startTime = System.currentTimeMillis();

        // CREATE INDEX
        
        // load some document metadata
        final String dc_title = document.dc_title();
        final yacyURL referrerURL = entry.referrerURL();
        final Date docDate = entry.getModificationDate();
        
        // do a identification of the language
        String language = condenser.language(); // this is a statistical analysation of the content: will be compared with other attributes
        String bymetadata = document.languageByMetadata(); // the languageByMetadata may return null if there was no declaration
        if (language == null) {
            // no statistics available, we take either the metadata (if given) or the TLD
            language = (bymetadata == null) ? entry.url().language() : bymetadata;
            System.out.println("*** DEBUG LANGUAGE-BY-STATISTICS: " + entry.url() + " FAILED, taking " + ((bymetadata == null) ? "TLD" : "metadata") + ": " + language);
        } else {
            if (bymetadata == null) {
                // two possible results: compare and report conflicts
                if (language.equals(entry.url().language()))
                    System.out.println("*** DEBUG LANGUAGE-BY-STATISTICS: " + entry.url() + " CONFIRMED - TLD IDENTICAL: " + language);
                else {
                    String error = "*** DEBUG LANGUAGE-BY-STATISTICS: " + entry.url() + " CONFLICTING: " + language + " (the language given by the TLD is " + entry.url().language() + ")";
                    // see if we have a hint in the url that the statistic was right
                    String u = entry.url().toNormalform(true, false).toLowerCase();
                    if (!u.contains("/" + language + "/") && !u.contains("/" + iso639.country(language).toLowerCase() + "/")) {
                        // no confirmation using the url, use the TLD
                        language = entry.url().language();
                        System.out.println(error + ", corrected using the TLD");
                    } else {
                        // this is a strong hint that the statistics was in fact correct
                        System.out.println(error + ", but the url proves that the statistic is correct");
                    }
                }
            } else {
                // here we have three results: we can do a voting
                if (language.equals(bymetadata)) {
                    System.out.println("*** DEBUG LANGUAGE-BY-STATISTICS: " + entry.url() + " CONFIRMED - METADATA IDENTICAL: " + language);
                } else if (language.equals(entry.url().language())) {
                    System.out.println("*** DEBUG LANGUAGE-BY-STATISTICS: " + entry.url() + " CONFIRMED - TLD IS IDENTICAL: " + language);
                } else if (bymetadata.equals(entry.url().language())) {
                    System.out.println("*** DEBUG LANGUAGE-BY-STATISTICS: " + entry.url() + " CONFLICTING: " + language + " BUT METADATA AND TLD ARE IDENTICAL: " + bymetadata + ")");
                    language = bymetadata;
                } else {
                    System.out.println("*** DEBUG LANGUAGE-BY-STATISTICS: " + entry.url() + " CONFLICTING: ALL DIFFERENT! statistic: " + language + ", metadata: " + bymetadata + ", TLD: + " + entry.url().language() + ". taking metadata.");
                    language = bymetadata;
                }
            }
        }
        
        // create a new loaded URL db entry
        final long ldate = System.currentTimeMillis();
        final indexURLReference newEntry = new indexURLReference(
                entry.url(),                               // URL
                dc_title,                                  // document description
                document.dc_creator(),                     // author
                document.dc_subject(' '),                  // tags
                "",                                        // ETag
                docDate,                                   // modification date
                new Date(),                                // loaded date
                new Date(ldate + Math.max(0, ldate - docDate.getTime()) / 2), // freshdate, computed with Proxy-TTL formula 
                (referrerURL == null) ? null : referrerURL.hash(),            // referer hash
                new byte[0],                               // md5
                (int) entry.size(),                        // size
                condenser.RESULT_NUMB_WORDS,               // word count
                httpdProxyCacheEntry.docType(document.dc_format()), // doctype
                condenser.RESULT_FLAGS,                    // flags
                language,                                  // language
                document.inboundLinks(),                   // inbound links
                document.outboundLinks(),                  // outbound links
                document.getAudiolinks().size(),           // laudio
                document.getImages().size(),               // limage
                document.getVideolinks().size(),           // lvideo
                document.getApplinks().size()              // lapp
        );
        
        // STORE URL TO LOADED-URL-DB
        putURL(newEntry);
        
        final long storageEndTime = System.currentTimeMillis();
        
        // STORE PAGE INDEX INTO WORD INDEX DB
        final int words = addPageIndex(
                entry.url(),                                  // document url
                docDate,                                      // document mod date
                document,                                     // document content
                condenser,                                    // document condenser
                language,                                     // document language
                httpdProxyCacheEntry.docType(document.dc_format()),  // document type
                document.inboundLinks(),                      // inbound links
                document.outboundLinks()                      // outbound links
        );
            
        final long indexingEndTime = System.currentTimeMillis();
        
        if (log.isInfo()) {
            // TODO: UTF-8 docDescription seems not to be displayed correctly because
            // of string concatenation
            log.logInfo("*Indexed " + words + " words in URL " + entry.url() +
                    " [" + entry.urlHash() + "]" +
                    "\n\tDescription:  " + dc_title +
                    "\n\tMimeType: "  + document.dc_format() + " | Charset: " + document.getCharset() + " | " +
                    "Size: " + document.getTextLength() + " bytes | " +
                    "Anchors: " + ((document.getAnchors() == null) ? 0 : document.getAnchors().size()) +
                    "\n\tLinkStorageTime: " + (storageEndTime - startTime) + " ms | " +
                    "indexStorageTime: " + (indexingEndTime - storageEndTime) + " ms");
            RSSFeed.channels((entry.initiator().equals(seedDB.mySeed().hash)) ? RSSFeed.LOCALINDEXING : RSSFeed.REMOTEINDEXING).addMessage(new RSSMessage("Indexed web page", dc_title, entry.url().toNormalform(true, false)));
        }
        
        // finished
        return newEntry;
    }
    
    public synchronized CloneableIterator<indexContainer> wordContainers(final String startHash, final boolean ram, final boolean rot) {
        final CloneableIterator<indexContainer> i = wordContainers(startHash, ram);
        if (rot) {
            return new RotateIterator<indexContainer>(i, new String(Base64Order.zero(startHash.length())), dhtCache.size() + ((ram) ? 0 : collections.size()));
        }
        return i;
    }

    public synchronized CloneableIterator<indexContainer> wordContainers(final String startWordHash, final boolean ram) {
        final Order<indexContainer> containerOrder = new indexContainerOrder(indexOrder.clone());
        containerOrder.rotate(emptyContainer(startWordHash, 0));
        if (ram) {
            return dhtCache.wordContainers(startWordHash, false);
        }
        return new MergeIterator<indexContainer>(
                dhtCache.wordContainers(startWordHash, false),
                collections.wordContainers(startWordHash, false),
                containerOrder,
                indexContainer.containerMergeMethod,
                true);
    }
    

    //  The Cleaner class was provided as "UrldbCleaner" by Hydrox
    public synchronized ReferenceCleaner getReferenceCleaner(final String startHash) {
        return new ReferenceCleaner(startHash);
    }
    
    public class ReferenceCleaner extends Thread {
        
        private final String startHash;
        private boolean run = true;
        private boolean pause = false;
        public int rwiCountAtStart = 0;
        public String wordHashNow = "";
        public String lastWordHash = "";
        public int lastDeletionCounter = 0;
        
        public ReferenceCleaner(final String startHash) {
            this.startHash = startHash;
            this.rwiCountAtStart = size();
        }
        
        public void run() {
            serverLog.logInfo("INDEXCLEANER", "IndexCleaner-Thread started");
            indexContainer container = null;
            indexRWIRowEntry entry = null;
            yacyURL url = null;
            final HashSet<String> urlHashs = new HashSet<String>();
            Iterator<indexContainer> indexContainerIterator = indexContainerSet(startHash, false, false, 100).iterator();
            while (indexContainerIterator.hasNext() && run) {
                waiter();
                container = indexContainerIterator.next();
                final Iterator<indexRWIRowEntry> containerIterator = container.entries();
                wordHashNow = container.getWordHash();
                while (containerIterator.hasNext() && run) {
                    waiter();
                    entry = containerIterator.next();
                    // System.out.println("Wordhash: "+wordHash+" UrlHash:
                    // "+entry.getUrlHash());
                    final indexURLReference ue = referenceURL.load(entry.urlHash(), entry, 0);
                    if (ue == null) {
                        urlHashs.add(entry.urlHash());
                    } else {
                        url = ue.comp().url();
                        if ((url == null) || (plasmaSwitchboard.urlBlacklist.isListed(indexReferenceBlacklist.BLACKLIST_CRAWLER, url) == true)) {
                            urlHashs.add(entry.urlHash());
                        }
                    }
                }
                if (urlHashs.size() > 0) {
                    final int removed = removeEntries(container.getWordHash(), urlHashs);
                    serverLog.logFine("INDEXCLEANER", container.getWordHash() + ": " + removed + " of " + container.size() + " URL-entries deleted");
                    lastWordHash = container.getWordHash();
                    lastDeletionCounter = urlHashs.size();
                    urlHashs.clear();
                }
                if (!containerIterator.hasNext()) {
                    // We may not be finished yet, try to get the next chunk of wordHashes
                    final TreeSet<indexContainer> containers = indexContainerSet(container.getWordHash(), false, false, 100);
                    indexContainerIterator = containers.iterator();
                    // Make sure we don't get the same wordhash twice, but don't skip a word
                    if ((indexContainerIterator.hasNext()) && (!container.getWordHash().equals(indexContainerIterator.next().getWordHash()))) {
                        indexContainerIterator = containers.iterator();
                    }
                }
            }
            serverLog.logInfo("INDEXCLEANER", "IndexCleaner-Thread stopped");
        }
        
        public void abort() {
            synchronized(this) {
                run = false;
                this.notifyAll();
            }
        }

        public void pause() {
            synchronized (this) {
                if (!pause) {
                    pause = true;
                    serverLog.logInfo("INDEXCLEANER", "IndexCleaner-Thread paused");
                }
            }
        }

        public void endPause() {
            synchronized (this) {
                if (pause) {
                    pause = false;
                    this.notifyAll();
                    serverLog.logInfo("INDEXCLEANER", "IndexCleaner-Thread resumed");
                }
            }
        }

        public void waiter() {
            synchronized (this) {
                if (this.pause) {
                    try {
                        this.wait();
                    } catch (final InterruptedException e) {
                        this.run = false;
                        return;
                    }
                }
            }
        }
    }
    
}
