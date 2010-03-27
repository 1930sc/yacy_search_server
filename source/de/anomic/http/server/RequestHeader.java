// RequestHeader.java 
// -----------------------
// (C) 2008 by Michael Peter Christen; mc@yacy.net
// first published on http://yacy.net
// Frankfurt, Germany, 22.08.2008
//
// last major change: $LastChangedDate$ by $LastChangedBy$
// Revision: $LastChangedRevision$
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

package de.anomic.http.server;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.yacy.kelondro.data.meta.DigestURI;
import net.yacy.kelondro.util.DateFormatter;

import de.anomic.server.serverCore;

public class RequestHeader extends HeaderFramework {

    // request header properties    
    
    public static final String CONNECTION = "Connection";
    public static final String PROXY_CONNECTION = "Proxy-Connection";
    public static final String KEEP_ALIVE = "Keep-Alive";
    
    public static final String AUTHORIZATION = "Authorization";
    public static final String WWW_AUTHENTICATE = "WWW-Authenticate";
    public static final String PROXY_AUTHORIZATION = "Proxy-Authorization";
    public static final String PROXY_AUTHENTICATE = "Proxy-Authenticate";

    public static final String UPGRADE = "Upgrade"; 
    public static final String TE = "TE";
    
    public static final String X_CACHE = "X-Cache";
    public static final String X_CACHE_LOOKUP = "X-Cache-Lookup";
    
    public static final String COOKIE = "Cookie";
    
    public static final String IF_MODIFIED_SINCE = "If-Modified-Since";
    public static final String IF_RANGE = "If-Range";
    public static final String REFERER = "Referer";
    
    private static final long serialVersionUID = 0L;

    private static final Pattern P_20 = Pattern.compile(" ", Pattern.LITERAL);
    private static final Pattern P_7B = Pattern.compile("{", Pattern.LITERAL);
    private static final Pattern P_7D = Pattern.compile("}", Pattern.LITERAL);
    private static final Pattern P_7C = Pattern.compile("|", Pattern.LITERAL);
    private static final Pattern P_5C = Pattern.compile("\\", Pattern.LITERAL);
    private static final Pattern P_5E = Pattern.compile("^", Pattern.LITERAL);
    private static final Pattern P_5B = Pattern.compile("[", Pattern.LITERAL);
    private static final Pattern P_5D = Pattern.compile("]", Pattern.LITERAL);
    private static final Pattern P_60 = Pattern.compile("`", Pattern.LITERAL);
    
    public RequestHeader() {
        super();
    }
    
    public RequestHeader(final HashMap<String, String> reverseMappingCache) {
        super(reverseMappingCache);
    }
    
    public RequestHeader(final HashMap<String, String> reverseMappingCache, final Map<String, String> othermap)  {
        super(reverseMappingCache, othermap);
    }
    
    public String referer() {
        return get(REFERER, "");
    }
    
    public String refererHost() {
        final String referer = referer();
        if (referer.length() == 0) return referer;
        DigestURI url;
        try {
            url = new DigestURI(referer, null);
            return url.getHost();
        } catch (MalformedURLException e1) {
            return referer;
        }
    }
    
    public Date ifModifiedSince() {
        return headerDate(IF_MODIFIED_SINCE);
    }
    
    public Object ifRange() {
        if (containsKey(IF_RANGE)) {
            final Date rangeDate = DateFormatter.parseHTTPDate(get(IF_RANGE));
            if (rangeDate != null) 
                return rangeDate;
            
            return get(IF_RANGE);
        } 
        return null;
    }
    
    public String userAgent() {
        return get(USER_AGENT, "");
    }
    
    public boolean acceptGzip() {
        return ((containsKey(ACCEPT_ENCODING)) &&
                ((get(ACCEPT_ENCODING)).toUpperCase().indexOf("GZIP")) != -1);        
    }
    
    public static Properties parseRequestLine(final String s, final Properties prop, final String virtualHost) {
        final int p = s.indexOf(' ');
        if (p >= 0) {
            final String cmd = s.substring(0,p);
            final String args = s.substring(p+1);
            return parseRequestLine(cmd,args, prop,virtualHost);
        }
        return prop;
    }
    
    public static Properties parseRequestLine(final String cmd, String args, final Properties prop, final String virtualHost) {
        
        // getting the last request line for debugging purposes
        final String prevRequestLine = prop.containsKey(CONNECTION_PROP_REQUESTLINE)?
                prop.getProperty(CONNECTION_PROP_REQUESTLINE) : "";
        
        // reset property from previous run   
        prop.clear();

        // storing informations about the request
        prop.setProperty(CONNECTION_PROP_METHOD, cmd);
        prop.setProperty(CONNECTION_PROP_REQUESTLINE, cmd + " " + args);
        prop.setProperty(CONNECTION_PROP_PREV_REQUESTLINE, prevRequestLine);
        
        // this parses a whole URL
        if (args.length() == 0) {
            prop.setProperty(CONNECTION_PROP_HOST, virtualHost);
            prop.setProperty(CONNECTION_PROP_PATH, "/");
            prop.setProperty(CONNECTION_PROP_HTTP_VER, HTTP_VERSION_0_9);
            prop.setProperty(CONNECTION_PROP_EXT, "");
            return prop;
        }
        
        // store the version propery "HTTP" and cut the query at both ends
        int sep = args.lastIndexOf(' ');
        if ((sep >= 0)&&(args.substring(sep + 1).toLowerCase().startsWith("http/"))) {
            // HTTP version is given
            prop.setProperty(CONNECTION_PROP_HTTP_VER, args.substring(sep + 1).trim());
            args = args.substring(0, sep).trim(); // cut off HTTP version mark
        } else {
            // HTTP version is not given, it will be treated as ver 0.9
            prop.setProperty(CONNECTION_PROP_HTTP_VER, HTTP_VERSION_0_9);
        }
        
        // replacing spaces in the url string correctly
        args = P_20.matcher(args).replaceAll(Matcher.quoteReplacement("%20"));
        // replace unwise characters (see RFC 2396, 2.4.3), which may not be escaped
        args = P_7B.matcher(args).replaceAll(Matcher.quoteReplacement("%7B"));
        args = P_7D.matcher(args).replaceAll(Matcher.quoteReplacement("%7D"));
        args = P_7C.matcher(args).replaceAll(Matcher.quoteReplacement("%7C"));
        args = P_5C.matcher(args).replaceAll(Matcher.quoteReplacement("%5C"));
        args = P_5E.matcher(args).replaceAll(Matcher.quoteReplacement("%5E"));
        args = P_5B.matcher(args).replaceAll(Matcher.quoteReplacement("%5B"));
        args = P_5D.matcher(args).replaceAll(Matcher.quoteReplacement("%5D"));
        args = P_60.matcher(args).replaceAll(Matcher.quoteReplacement("%60"));
        
        // properties of the query are stored with the prefix "&"
        // additionally, the values URL and ARGC are computed
        
        String argsString = "";
        sep = args.indexOf('?');
        if (sep >= 0) {
            // there are values attached to the query string
            argsString = args.substring(sep + 1); // cut head from tail of query
            args = args.substring(0, sep);
        }
        prop.setProperty(CONNECTION_PROP_URL, args); // store URL
        //System.out.println("HTTPD: ARGS=" + argsString);
        if (argsString.length() != 0) prop.setProperty(CONNECTION_PROP_ARGS, argsString); // store arguments in original form
        
        // finally find host string
        String path;
        if (args.toUpperCase().startsWith("HTTP://")) {
            // a host was given. extract it and set path
            args = args.substring(7);
            sep = args.indexOf('/');
            if (sep < 0) {
                // this is a malformed url, something like
                // http://index.html
                // we are lazy and guess that it means
                // /index.html
                // which is a localhost access to the file servlet
                prop.setProperty(CONNECTION_PROP_HOST, args);
                path = "/";
            } else {
                // THIS IS THE "GOOD" CASE
                // a perfect formulated url
                final String dstHostSocket = args.substring(0, sep);
                prop.setProperty(CONNECTION_PROP_HOST, (HTTPDemon.isThisHostName(dstHostSocket)?virtualHost:dstHostSocket));
                path = args.substring(sep); // yes, including beginning "/"
            }
        } else {
            // no host in url. set path
            if (args.length() > 0 && args.charAt(0) == '/') {
                // thats also fine, its a perfect localhost access
                // in this case, we simulate a
                // http://localhost/s
                // access by setting a virtual host
                prop.setProperty(CONNECTION_PROP_HOST, virtualHost);
                path = args;
            } else {
                // the client 'forgot' to set a leading '/'
                // this is the same case as above, with some lazyness
                prop.setProperty(CONNECTION_PROP_HOST, virtualHost);
                path = "/" + args;
            }
        }
        prop.setProperty(CONNECTION_PROP_PATH, path);

        // find out file extension (we already stripped ?-parameters from args)
        String ext = "";  // default when no file extension
        sep = path.lastIndexOf('.');
        if (sep >= 0) {
            final int ancpos = path.indexOf("#", sep + 1);
            if (ancpos  >= sep) {
                // ex: /foo/bar.html#xy => html
                ext = path.substring(sep + 1, ancpos).toLowerCase();
            } else {
                // ex: /foo/bar.php => php
                ext = path.substring(sep + 1).toLowerCase();
            }
        }
        prop.setProperty(CONNECTION_PROP_EXT, ext);
        
        return prop;
    }    
    
    public static RequestHeader readHeader(final Properties prop, final serverCore.Session theSession) throws IOException {
        
        // reading all headers
        final RequestHeader header = new RequestHeader(HTTPDemon.reverseMappingCache);
        int p;
        String line;
        while ((line = theSession.readLineAsString()) != null) {
            if (line.length() == 0) break; // this separates the header of the HTTP request from the body
            // parse the header line: a property separated with the ':' sign
            if ((p = line.indexOf(':')) >= 0) {
                // store a property
                header.add(line.substring(0, p).trim(), line.substring(p + 1).trim());
            }
        }
        
        /* 
         * doing some header validation here ...
         */
        final String httpVersion = prop.getProperty(HeaderFramework.CONNECTION_PROP_HTTP_VER, "HTTP/0.9");
        if (httpVersion.equals("HTTP/1.1") && !header.containsKey(HeaderFramework.HOST)) {
            // the HTTP/1.1 specification requires that an HTTP/1.1 server must reject any  
            // HTTP/1.1 message that does not contain a Host header.            
            HTTPDemon.sendRespondError(prop,theSession.out,0,400,null,null,null);
            throw new IOException("400 Bad request");
        }     
        
        return header;
    }
    
    
}
