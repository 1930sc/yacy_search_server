
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Set;

import de.anomic.crawler.HTTPLoader;
import de.anomic.htmlFilter.htmlFilterContentScraper;
import de.anomic.http.httpRequestHeader;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;
import de.anomic.yacy.yacyURL;

public class getpageinfo_p {
    
    public static serverObjects respond(final httpRequestHeader header, final serverObjects post, final serverSwitch<?> env) {
        final plasmaSwitchboard sb = (plasmaSwitchboard) env;
        final serverObjects prop = new serverObjects();
        
        // avoid UNRESOLVED PATTERN        
        prop.put("title", "");        
        prop.put("desc", "");
        prop.put("lang", "");
        prop.put("robots-allowed", "3"); //unknown
        prop.put("sitemap", "");
        prop.put("favicon","");        
        
        // default actions
        String actions="title,robots";
        
        if(post!=null && post.containsKey("url")){
            if(post.containsKey("actions"))
                actions=post.get("actions");
            String url=post.get("url");
			if(url.toLowerCase().startsWith("ftp://")){
				prop.put("robots-allowed", "1");
				prop.putXML("title", "FTP: "+url);
                return prop;
			} else if (!(url.toLowerCase().startsWith("http://") || url.toLowerCase().startsWith("https://"))) {
                url = "http://" + url;
            }
            if (actions.indexOf("title")>=0) {
                try {
                    final yacyURL u = new yacyURL(url, null);
                    final httpRequestHeader reqHeader = new httpRequestHeader();
                    reqHeader.put(httpRequestHeader.USER_AGENT, HTTPLoader.yacyUserAgent); // do not set the crawler user agent, because this page was loaded by manual entering of the url
                    final htmlFilterContentScraper scraper = htmlFilterContentScraper.parseResource(u, reqHeader);
                    
                    // put the document title 
                    prop.putXML("title", scraper.getTitle());
                    
                    // put the favicon that belongs to the document
                    prop.put("favicon", (scraper.getFavicon()==null) ? "" : scraper.getFavicon().toString());
                    
                    // put keywords
                    final String list[]=scraper.getKeywords();
                    int count = 0;
                    for(int i=0;i<list.length;i++){
                    	String tag = list[i];
                    	if (!tag.equals("")) {                   	                 	
                    		prop.putXML("tags_"+count+"_tag", tag);
                    		count++;
                    	}
                    }
                    prop.put("tags", count);
                    // put description                    
                    prop.putXML("desc", scraper.getDescription());
                    // put language
                    Set<String> languages = scraper.getContentLanguages();
                    prop.putXML("lang", (languages == null) ? "unknown" : languages.iterator().next());

                } catch (final MalformedURLException e) { /* ignore this */
                } catch (final IOException e) { /* ignore this */
                }
            }
            if(actions.indexOf("robots")>=0){
                try {
                    final yacyURL theURL = new yacyURL(url, null);
                    
                	// determine if crawling of the current URL is allowed
                	prop.put("robots-allowed", sb.robots.isDisallowed(theURL) ? "0" : "1");
                    
                    // get the sitemap URL of the domain
                    final yacyURL sitemapURL = sb.robots.getSitemapURL(theURL);
                    prop.putXML("sitemap", (sitemapURL==null)?"":sitemapURL.toString());
                } catch (final MalformedURLException e) {}
            }
            
        }
        // return rewrite properties
        return prop;
    }
    
}
