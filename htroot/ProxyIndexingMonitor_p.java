// ProxyIndexingMonitor_p.java 
// ---------------------------
// part of the AnomicHTTPD caching proxy
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

// You must compile this file with
// javac -classpath .:../classes ProxyIndexingMonitor_p.java
// if the shell's current path is HTROOT

import java.io.File;
import java.io.IOException;

import de.anomic.http.httpRequestHeader;
import de.anomic.kelondro.util.Log;
import de.anomic.plasma.plasmaHTCache;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.plasma.plasmaSwitchboardConstants;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;

public class ProxyIndexingMonitor_p {

//  private static SimpleDateFormat dayFormatter = new SimpleDateFormat("yyyy/MM/dd", Locale.US);
//  private static String daydate(Date date) {
//      if (date == null) return ""; else return dayFormatter.format(date);
//  }

    public static serverObjects respond(final httpRequestHeader header, final serverObjects post, final serverSwitch<?> env) {
        // return variable that accumulates replacements
        final plasmaSwitchboard sb = (plasmaSwitchboard) env;
        final serverObjects prop = new serverObjects();

//      int showIndexedCount = 20;
//      boolean se = false;

        String oldProxyCachePath, newProxyCachePath;
        String oldProxyCacheSize, newProxyCacheSize;

        prop.put("info", "0");
        prop.put("info_message", "");

        if (post != null) {

            if (post.containsKey("proxyprofileset")) try {
                // read values and put them in global settings
                int newProxyPrefetchDepth = post.getInt("proxyPrefetchDepth", 0);
                if (newProxyPrefetchDepth < 0) newProxyPrefetchDepth = 0; 
                if (newProxyPrefetchDepth > 20) newProxyPrefetchDepth = 20; // self protection ?
                env.setConfig("proxyPrefetchDepth", Integer.toString(newProxyPrefetchDepth));
                final boolean proxyStoreHTCache = post.containsKey("proxyStoreHTCache");
                env.setConfig("proxyStoreHTCache", (proxyStoreHTCache) ? "true" : "false");
                final boolean proxyIndexingRemote = post.containsKey("proxyIndexingRemote");
                env.setConfig("proxyIndexingRemote", proxyIndexingRemote ? "true" : "false");
                final boolean proxyIndexingLocalText = post.containsKey("proxyIndexingLocalText");
                env.setConfig("proxyIndexingLocalText", proxyIndexingLocalText ? "true" : "false");
                final boolean proxyIndexingLocalMedia = post.containsKey("proxyIndexingLocalMedia");
                env.setConfig("proxyIndexingLocalMedia", proxyIndexingLocalMedia ? "true" : "false");
                
                // added proxyCache, proxyCacheSize - Borg-0300
                // proxyCache - check and create the directory
                oldProxyCachePath = env.getConfig(plasmaSwitchboardConstants.HTCACHE_PATH, plasmaSwitchboardConstants.HTCACHE_PATH_DEFAULT);
                newProxyCachePath = post.get("proxyCache", plasmaSwitchboardConstants.HTCACHE_PATH_DEFAULT);
                newProxyCachePath = newProxyCachePath.replace('\\', '/');
                if (newProxyCachePath.endsWith("/")) {
                    newProxyCachePath = newProxyCachePath.substring(0, newProxyCachePath.length() - 1);
                }
                env.setConfig(plasmaSwitchboardConstants.HTCACHE_PATH, newProxyCachePath);
                final File cache = env.getConfigPath(plasmaSwitchboardConstants.HTCACHE_PATH, oldProxyCachePath);
                if (!cache.isDirectory() && !cache.isFile()) cache.mkdirs();

                // proxyCacheSize 
                oldProxyCacheSize = getStringLong(env.getConfig(plasmaSwitchboardConstants.PROXY_CACHE_SIZE, "64"));
                newProxyCacheSize = getStringLong(post.get(plasmaSwitchboardConstants.PROXY_CACHE_SIZE, "64"));
                if (getLong(newProxyCacheSize) < 4) { newProxyCacheSize = "4"; }
                env.setConfig(plasmaSwitchboardConstants.PROXY_CACHE_SIZE, newProxyCacheSize);
                plasmaHTCache.setCacheSize(Long.parseLong(newProxyCacheSize) * 1024 * 1024);                

                // implant these settings also into the crawling profile for the proxy
                if (sb.webIndex.defaultProxyProfile == null) {
                    prop.put("info", "1"); //delete DATA/PLASMADB/crawlProfiles0.db
                } else {
                    try {
                        sb.webIndex.profilesActiveCrawls.changeEntry(sb.webIndex.defaultProxyProfile, "generalDepth", Integer.toString(newProxyPrefetchDepth));
                        sb.webIndex.profilesActiveCrawls.changeEntry(sb.webIndex.defaultProxyProfile, "storeHTCache", (proxyStoreHTCache) ? "true": "false");
                        sb.webIndex.profilesActiveCrawls.changeEntry(sb.webIndex.defaultProxyProfile, "remoteIndexing",proxyIndexingRemote ? "true":"false");
                        sb.webIndex.profilesActiveCrawls.changeEntry(sb.webIndex.defaultProxyProfile, "indexText",proxyIndexingLocalText ? "true":"false");
                        sb.webIndex.profilesActiveCrawls.changeEntry(sb.webIndex.defaultProxyProfile, "indexMedia",proxyIndexingLocalMedia ? "true":"false");
                        
                        prop.put("info", "2");//new proxyPrefetchdepth
                        prop.put("info_message", newProxyPrefetchDepth);
                        prop.put("info_caching", proxyStoreHTCache ? "1" : "0");
                        prop.put("info_indexingLocalText", proxyIndexingLocalText ? "1" : "0");
                        prop.put("info_indexingLocalMedia", proxyIndexingLocalMedia ? "1" : "0");
                        prop.put("info_indexingRemote", proxyIndexingRemote ? "1" : "0");

                        // proxyCache - only display on change
                        if (oldProxyCachePath.equals(newProxyCachePath)) {
                            prop.put("info_path", "0");
                            prop.putHTML("info_path_return", oldProxyCachePath);
                        } else {
                            prop.put("info_path", "1");
                            prop.putHTML("info_path_return", newProxyCachePath);
                        }
                        // proxyCacheSize - only display on change
                        if (oldProxyCacheSize.equals(newProxyCacheSize)) {
                            prop.put("info_size", "0");
                            prop.put("info_size_return", oldProxyCacheSize);
                        } else {
                            prop.put("info_size", "1");
                            prop.put("info_size_return", newProxyCacheSize);
                        }
                        // proxyCache, proxyCacheSize we need a restart
                        prop.put("info_restart", "0");
                        prop.put("info_restart_return", "0");
                        if (!oldProxyCachePath.equals(newProxyCachePath)) prop.put("info_restart", "1");

                    } catch (final IOException e) {
                        prop.put("info", "3"); //Error: errmsg
                        prop.putHTML("info_error", e.getMessage());
                    }
                }

            } catch (final Exception e) {
                prop.put("info", "2"); //Error: errmsg
                prop.putHTML("info_error", e.getMessage());
                Log.logSevere("SERVLET", "ProxyIndexingMonitor.case3", e);
            }
        }

        prop.put("proxyPrefetchDepth", env.getConfigLong("proxyPrefetchDepth", 0));
        prop.put("proxyStoreHTCacheChecked", env.getConfig("proxyStoreHTCache", "").equals("true") ? "1" : "0");
        prop.put("proxyIndexingRemote", env.getConfig("proxyIndexingRemote", "").equals("true") ? "1" : "0");
        prop.put("proxyIndexingLocalText", env.getConfig("proxyIndexingLocalText", "").equals("true") ? "1" : "0");
        prop.put("proxyIndexingLocalMedia", env.getConfig("proxyIndexingLocalMedia", "").equals("true") ? "1" : "0");
        prop.put("proxyCache", env.getConfig(plasmaSwitchboardConstants.HTCACHE_PATH, plasmaSwitchboardConstants.HTCACHE_PATH_DEFAULT));
        prop.put("proxyCacheSize", env.getConfigLong(plasmaSwitchboardConstants.PROXY_CACHE_SIZE, 64));
        // return rewrite properties
        return prop;
    }

    public static long getLong(final String value) {
        try {
            return Long.parseLong(value);
        } catch (final Exception e) {
            return 0;
        }
    }

    public static String getStringLong(final String value) {
        try {
            return Long.toString(Long.parseLong(value));
        } catch (final Exception e) {
            return "0";
        }
    }

}