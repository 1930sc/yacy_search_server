import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

import de.anomic.crawler.CrawlEntry;
import de.anomic.crawler.IndexingStack;
import de.anomic.crawler.NoticedURL;
import de.anomic.http.httpRequestHeader;
import de.anomic.kelondro.util.kelondroException;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.plasma.plasmaSwitchboardConstants;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;
import de.anomic.yacy.yacySeed;

public class queues_p {
    
    public static final String STATE_RUNNING = "running";
    public static final String STATE_PAUSED = "paused";
    
    private static SimpleDateFormat dayFormatter = new SimpleDateFormat("yyyy/MM/dd", Locale.US);
    private static String daydate(final Date date) {
        if (date == null) return "";
        return dayFormatter.format(date);
    }
    
    public static serverObjects respond(final httpRequestHeader header, final serverObjects post, final serverSwitch<?> env) {
        // return variable that accumulates replacements
        final plasmaSwitchboard sb = (plasmaSwitchboard) env;
        //wikiCode wikiTransformer = new wikiCode(switchboard);
        final serverObjects prop = new serverObjects();
        if (post == null || !post.containsKey("html"))
            prop.setLocalized(false);
        prop.put("rejected", "0");
        //int showRejectedCount = 10;
        
        yacySeed initiator;
        
        //indexing queue
        prop.putNum("indexingSize", sb.getThread(plasmaSwitchboardConstants.INDEXER).getJobCount() + sb.webIndex.queuePreStack.getActiveQueueSize());
        prop.putNum("indexingMax", (int) sb.getConfigLong(plasmaSwitchboardConstants.INDEXER_SLOTS, 30));
        prop.putNum("urlpublictextSize", sb.webIndex.metadata().size());
        prop.putNum("rwipublictextSize", sb.webIndex.size());
        if ((sb.webIndex.queuePreStack.size() == 0) && (sb.webIndex.queuePreStack.getActiveQueueSize() == 0)) {
            prop.put("list", "0"); //is empty
        } else {
            IndexingStack.QueueEntry pcentry;
            long totalSize = 0;
            int i=0; //counter
            
            // getting all entries that are currently in process
            final ArrayList<IndexingStack.QueueEntry> entryList = new ArrayList<IndexingStack.QueueEntry>();
            entryList.addAll(sb.webIndex.queuePreStack.getActiveQueueEntries());
            final int inProcessCount = entryList.size();
            
            // getting all enqueued entries
            if ((sb.webIndex.queuePreStack.size() > 0)) {
                final Iterator<IndexingStack.QueueEntry> i1 = sb.webIndex.queuePreStack.entryIterator(false);
                while (i1.hasNext()) try {
                    entryList.add(i1.next());
                } catch (kelondroException e) {
                    e.printStackTrace();
                }
            }
            
            int size = (post == null) ? entryList.size() : post.getInt("num", entryList.size());
            if (size > entryList.size()) size = entryList.size();
            
            int ok = 0;
            for (i = 0; i < size; i++) {
                final boolean inProcess = i < inProcessCount;
                pcentry = entryList.get(i);
                if ((pcentry != null) && (pcentry.url() != null)) {
                    final long entrySize = pcentry.size();
                    totalSize += entrySize;
                    initiator = sb.webIndex.peers().getConnected(pcentry.initiator());
                    prop.put("list-indexing_"+i+"_profile", (pcentry.profile() != null) ? pcentry.profile().name() : "deleted");
                    prop.putHTML("list-indexing_"+i+"_initiator", ((initiator == null) ? "proxy" : initiator.getName()));
                    prop.put("list-indexing_"+i+"_depth", pcentry.depth());
                    prop.put("list-indexing_"+i+"_modified", pcentry.getModificationDate());
                    prop.putXML("list-indexing_"+i+"_anchor", (pcentry.anchorName()==null) ? "" : pcentry.anchorName());
                    prop.putXML("list-indexing_"+i+"_url", pcentry.url().toNormalform(false, true));
                    prop.putNum("list-indexing_"+i+"_size", entrySize);
                    prop.put("list-indexing_"+i+"_inProcess", (inProcess) ? "1" : "0");
                    prop.put("list-indexing_"+i+"_hash", pcentry.urlHash());
                    ok++;
                }
            }
            prop.put("list-indexing", ok);
        }
        
        //loader queue
        prop.put("loaderSize", Integer.toString(sb.crawlQueues.size()));        
        prop.put("loaderMax", sb.getConfigLong(plasmaSwitchboardConstants.CRAWLER_THREADS_ACTIVE_MAX, 10));
        if (sb.crawlQueues.size() == 0) {
            prop.put("list-loader", "0");
        } else {
            final CrawlEntry[] w = sb.crawlQueues.activeWorkerEntries();
            int count = 0;
            for (int i = 0; i < w.length; i++)  {
                if (w[i] == null) continue;
                prop.put("list-loader_"+count+"_profile", w[i].profileHandle());
                initiator = sb.webIndex.peers().getConnected(w[i].initiator());
                prop.putHTML("list-loader_"+count+"_initiator", ((initiator == null) ? "proxy" : initiator.getName()));
                prop.put("list-loader_"+count+"_depth", w[i].depth());
                prop.putXML("list-loader_"+count+"_url", w[i].url().toString());
                count++;
            }
            prop.put("list-loader", count);
        }
        
        //local crawl queue
        prop.putNum("localCrawlSize", Integer.toString(sb.getThread(plasmaSwitchboardConstants.CRAWLJOB_LOCAL_CRAWL).getJobCount()));
        prop.put("localCrawlState", sb.crawlJobIsPaused(plasmaSwitchboardConstants.CRAWLJOB_LOCAL_CRAWL) ? STATE_PAUSED : STATE_RUNNING);
        int stackSize = sb.crawlQueues.noticeURL.stackSize(NoticedURL.STACK_TYPE_CORE);
        addNTable(sb, prop, "list-local", sb.crawlQueues.noticeURL.top(NoticedURL.STACK_TYPE_CORE, Math.min(10, stackSize)));

        //global crawl queue
        prop.putNum("limitCrawlSize", Integer.toString(sb.crawlQueues.limitCrawlJobSize()));
        prop.put("limitCrawlState", STATE_RUNNING);
        stackSize = sb.crawlQueues.noticeURL.stackSize(NoticedURL.STACK_TYPE_LIMIT);

        //global crawl queue
        prop.putNum("remoteCrawlSize", Integer.toString(sb.getThread(plasmaSwitchboardConstants.CRAWLJOB_REMOTE_TRIGGERED_CRAWL).getJobCount()));
        prop.put("remoteCrawlState", sb.crawlJobIsPaused(plasmaSwitchboardConstants.CRAWLJOB_REMOTE_TRIGGERED_CRAWL) ? STATE_PAUSED : STATE_RUNNING);
        stackSize = sb.crawlQueues.noticeURL.stackSize(NoticedURL.STACK_TYPE_LIMIT);

        if (stackSize == 0) {
            prop.put("list-remote", "0");
        } else {
            addNTable(sb, prop, "list-remote", sb.crawlQueues.noticeURL.top(NoticedURL.STACK_TYPE_LIMIT, Math.min(10, stackSize)));
        }

        // return rewrite properties
        return prop;
    }
    
    
    public static final void addNTable(final plasmaSwitchboard sb, final serverObjects prop, final String tableName, final ArrayList<CrawlEntry> crawlerList) {

        int showNum = 0;
        CrawlEntry urle;
        yacySeed initiator;
        for (int i = 0; i < crawlerList.size(); i++) {
            urle = crawlerList.get(i);
            if ((urle != null) && (urle.url() != null)) {
                initiator = sb.webIndex.peers().getConnected(urle.initiator());
                prop.put(tableName + "_" + showNum + "_profile", urle.profileHandle());
                prop.put(tableName + "_" + showNum + "_initiator", ((initiator == null) ? "proxy" : initiator.getName()));
                prop.put(tableName + "_" + showNum + "_depth", urle.depth());
                prop.put(tableName + "_" + showNum + "_modified", daydate(urle.loaddate()));
                prop.putXML(tableName + "_" + showNum + "_anchor", urle.name());
                prop.putXML(tableName + "_" + showNum + "_url", urle.url().toNormalform(false, true));
                prop.put(tableName + "_" + showNum + "_hash", urle.url().hash());
                showNum++;
            }
        }
        prop.put(tableName, showNum);

    }
}
