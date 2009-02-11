// HttpClient.java
// (C) 2008 by Daniel Raap; danielr@users.berlios.de
// first published 2.4.2008 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2008-03-14 01:16:04 +0100 (Fr, 14 Mrz 2008) $
// $LastChangedRevision: 4558 $
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
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA

package de.anomic.http;

import java.io.IOException;

import de.anomic.kelondro.util.Log;

/**
 * Client who does http requests
 * 
 * some methods must be implemented (the "socket-layer")
 */
public abstract class HttpClient {

    /**
     * provide system information for client identification
     */
    private static final String systemOST = System.getProperty("os.arch", "no-os-arch") + " " +
            System.getProperty("os.name", "no-os-name") + " " + System.getProperty("os.version", "no-os-version") +
            "; " + "java " + System.getProperty("java.version", "no-java-version") + "; " + generateLocation();

    /**
     * generating the location string
     * 
     * @return
     */
    public static String generateLocation() {
        String loc = System.getProperty("user.timezone", "nowhere");
        final int p = loc.indexOf("/");
        if (p > 0) {
            loc = loc.substring(0, p);
        }
        loc = loc + "/" + System.getProperty("user.language", "dumb");
        return loc;
    }

    /**
     * @return the systemOST
     */
    public static String getSystemOST() {
        return systemOST;
    }
    
    /**
     * Gets a page (as raw bytes) addressing vhost at host in uri with specified header and timeout
     * 
     * @param uri
     * @param header
     * @param vhost
     * @param timeout in milliseconds
     * @return
     */
    public static byte[] wget(final String uri) {
        return wget(uri, new httpRequestHeader(), 10000, null);
    }
    public static byte[] wget(final String uri, final httpRequestHeader header, final int timeout) {
        return wget(uri, header, timeout, null);
    }
    public static byte[] wget(final String uri, final httpRequestHeader header, final int timeout, final String vhost) {
        assert uri != null : "precondition violated: uri != null";
        addHostHeader(header, vhost);
        final JakartaCommonsHttpClient client = new JakartaCommonsHttpClient(timeout, header);

        // do the request
        try {
            final JakartaCommonsHttpResponse response = client.GET(uri);
            return response.getData();
        } catch (final IOException e) {
            Log.logWarning("HTTPC", "wget(" + uri + ") failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * adds a Host-header to the header if vhost is not null
     * 
     * @param header
     * @param vhost
     * @return
     */
    private static void addHostHeader(httpRequestHeader header, final String vhost) {
        if (vhost != null) {
            if (header != null) {
                header = new httpRequestHeader();
            }
            // set host-header
            header.add(httpRequestHeader.HOST, vhost);
        }
    }

    /**
     * Gets a page-header
     * 
     * @param uri
     * @return
     */
    public static httpResponseHeader whead(final String uri) {
        return whead(uri, null);
    }

    /**
     * Gets a page-header
     * 
     * @param uri
     * @param header request header
     * @return null on error
     */
    public static httpResponseHeader whead(final String uri, final httpRequestHeader header) {
        final JakartaCommonsHttpClient client = new JakartaCommonsHttpClient(10000, header);
        JakartaCommonsHttpResponse response = null;
        try {
            response = client.HEAD(uri);
            return response.getResponseHeader();
        } catch (final IOException e) {
            Log.logWarning("HTTPC", "whead(" + uri + ") failed: " + e.getMessage());
            return null;
        } finally {
            if (response != null) {
                response.closeStream();
            }
        }
    }
}
